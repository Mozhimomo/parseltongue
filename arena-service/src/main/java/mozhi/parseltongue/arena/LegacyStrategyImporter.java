package mozhi.parseltongue.arena;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/** One-way, idempotent import. Never deletes or overwrites the original files. */
final class LegacyStrategyImporter {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyStrategyImporter.class);
    record Diagnostic(int attempt, String stage, String code, String detail, Instant time) {}

    static int run(Path directory, ObjectMapper mapper, StrategyRepository repository) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        int imported = 0;
        try (var files = Files.list(directory)) {
            for (Path metadata : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                final StrategyService.StoredSnake snake;
                try {
                    snake = mapper.readValue(read(metadata, 65536), StrategyService.StoredSnake.class);
                    if (!metadata.getFileName().toString().equals(UUID.fromString(snake.id()) + ".json")
                            || snake.owner() <= 0 || snake.createdAt() == null || !Set.of("READY", "FAILED", "GENERATING").contains(snake.status()))
                        throw new IllegalArgumentException("Invalid legacy metadata");
                    if (snake.name() == null || snake.name().length() > 40 || snake.description() == null || snake.description().length() > 8000)
                        throw new IllegalArgumentException("Invalid legacy text");
                } catch (RuntimeException | IOException error) {
                    LOG.warn("Skipping invalid legacy strategy metadata file={}", metadata.getFileName());
                    continue;
                }
                if (repository.exists(snake.id())) continue;
                String prefix = snake.id();
                var diagnostics = new ArrayList<Diagnostic>();
                String log = optional(directory.resolve(prefix + ".diagnostic"), 262144);
                if (log != null) for (String line : log.lines().toList()) {
                    try {
                        var item = mapper.readTree(line);
                        diagnostics.add(new Diagnostic(item.path("attempt").asInt(), item.path("stage").asText(),
                                item.path("code").asText(), item.path("detail").asText(), Instant.parse(item.path("time").asText())));
                    } catch (RuntimeException error) { LOG.warn("Skipping invalid legacy diagnostic snake={}", prefix); }
                }
                if (repository.importLegacy(snake, optional(directory.resolve(prefix + ".py"), 24576),
                        json(directory.resolve(prefix + ".validation"), mapper), json(directory.resolve(prefix + ".security"), mapper),
                        json(directory.resolve(prefix + ".intent"), mapper), diagnostics)) imported++;
            }
        }
        return imported;
    }

    private static String read(Path file, long limit) throws IOException {
        if (Files.size(file) > limit) throw new IOException("Legacy strategy file exceeds size limit");
        return Files.readString(file);
    }
    private static String optional(Path file, long limit) {
        try { return Files.isRegularFile(file) ? read(file, limit) : null; }
        catch (IOException error) { LOG.warn("Skipping unreadable/oversized legacy artifact file={}", file.getFileName()); return null; }
    }
    private static String json(Path file, ObjectMapper mapper) throws IOException {
        String value = optional(file, 65536);
        if (value == null) return null;
        try { return mapper.writeValueAsString(mapper.readTree(value)); }
        catch (RuntimeException error) { LOG.warn("Skipping invalid legacy artifact file={}", file.getFileName()); return null; }
    }
}
