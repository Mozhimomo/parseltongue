package mozhi.parseltongue.arena;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class PythonMatchRunner {
    private static final long MAX_OUTPUT = 8 * 1024 * 1024;
    private static final Set<String> BUILTINS = Set.of("straight", "cautious", "greedy", "forager");
    private final String python;
    private final String configuredDirectory;

    public PythonMatchRunner(@Value("${arena.python}") String python,
                             @Value("${arena.worker-directory:}") String directory) {
        this.python = python;
        this.configuredDirectory = directory;
    }

    public String run(TrialRequest request) throws IOException, InterruptedException {
        if (request.agents() == null || request.agents().size() != 4
                || !request.agents().stream().allMatch(BUILTINS::contains)
                || request.seed() == null || request.seed() < 0
                || request.maxTicks() == null || request.maxTicks() < 1 || request.maxTicks() > 2000) {
            throw new IllegalArgumentException("Only fixed built-in AI matches are supported");
        }
        Path root = workerDirectory();
        Path output = Files.createTempFile("parseltongue-match-", ".json");
        Path errors = Files.createTempFile("parseltongue-match-", ".err");
        Process process = null;
        try {
            // No shell, arbitrary module, source code, path or flags from the user.
            var command = new ArrayList<>(List.of(python, "-I", root.resolve("run_match.py").toString(),
                    "--seed", request.seed().toString(), "--max-ticks", request.maxTicks().toString(), "--ais"));
            command.addAll(request.agents());
            var builder = new ProcessBuilder(command).directory(root.toFile())
                    .redirectOutput(output.toFile()).redirectError(errors.toFile());
            // Built-ins are trusted; still avoid passing application credentials to Python.
            builder.environment().keySet().removeIf(key -> !Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP")
                    .contains(key.toUpperCase(java.util.Locale.ROOT)));
            process = builder.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > deadline || Files.size(output) > MAX_OUTPUT || Files.size(errors) > 65536) {
                    throw new IOException("Python match exceeded execution/output budget");
                }
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_OUTPUT) {
                throw new IOException("Python match failed; verify GAME_PYTHON and the worker installation");
            }
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            if (process != null) {
                process.getInputStream().close();
                process.getErrorStream().close();
            }
            for (Path file : List.of(output, errors)) {
                try { Files.deleteIfExists(file); }
                catch (IOException ignored) { file.toFile().deleteOnExit(); }
            }
        }
    }

    private Path workerDirectory() throws IOException {
        if (!configuredDirectory.isBlank()) {
            Path directory = Path.of(configuredDirectory).toAbsolutePath().normalize();
            if (Files.isRegularFile(directory.resolve("run_match.py"))) return directory;
            throw new IOException("GAME_WORKER_DIR does not contain run_match.py");
        }
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && candidate != null; i++, candidate = candidate.getParent()) {
            Path worker = candidate.resolve("match-worker");
            if (Files.isRegularFile(worker.resolve("run_match.py"))) return worker;
        }
        throw new IOException("Set GAME_WORKER_DIR to the match-worker directory");
    }
}
