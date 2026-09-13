package mozhi.parseltongue.arena;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All SQL is parameterized. Public reads fetch metadata only; code remains private. */
@Repository
public class StrategyRepository {
    private final StrategyMapper mapper;
    private final TransactionTemplate transactions;

    public record Version(String source, String sha256, String validation, String model, int number) {}

    public StrategyRepository(StrategyMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    StrategyService.StoredSnake create(long owner, StrategyController.Request request) {
        return transactions.execute(tx -> {
            mapper.lockAdmission();
            if (mapper.countGenerating() >= 8
                    || mapper.countGeneratingByOwner(owner) > 0
                    || mapper.countRecentByOwner(owner, Instant.now().minusSeconds(600)) >= 10)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请等待当前的蛇创建完成，或稍后再试");
            var snake = new StrategyService.StoredSnake(UUID.randomUUID().toString(), owner, request.name().strip(),
                    request.description().strip(), "GENERATING", null, Instant.now());
            insert(snake);
            return snake;
        });
    }

    private void insert(StrategyService.StoredSnake snake) {
        mapper.insert(snake, Instant.now());
    }

    List<StrategyService.StoredSnake> list(long owner) {
        return mapper.list(owner);
    }

    boolean exists(String id) { return mapper.countById(id) > 0; }

    StrategyService.StoredSnake find(long owner, String id) {
        var snake = mapper.find(owner, id);
        if (snake == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这条蛇不存在");
        return snake;
    }

    Version version(long owner, String id) {
        Version version = mapper.version(owner, id);
        if (version == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "这条蛇还没有创建完成");
        if (!sha256(version.source()).equals(version.sha256()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法加载这条蛇");
        return version;
    }

    void complete(String id, String source, String validation, String model) {
        if (source == null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > 24576)
            throw new IllegalArgumentException("Invalid strategy source size");
        transactions.executeWithoutResult(tx -> {
            String status = mapper.lockStatus(id);
            if (!"GENERATING".equals(status)) throw new IllegalStateException("Generation is no longer active");
            mapper.insertVersion(id, source, sha256(source), validation, model, Instant.now());
            mapper.markReady(id, Instant.now());
        });
    }

    void saveArtifact(String id, String kind, String json) {
        checkArtifactKind(kind);
        mapper.saveArtifact(id, kind, json, Instant.now());
    }

    String artifact(String id, String kind) {
        checkArtifactKind(kind);
        return mapper.artifact(id, kind);
    }

    private static void checkArtifactKind(String kind) {
        if (!"security".equals(kind) && !"intent".equals(kind)) throw new IllegalArgumentException("Unknown artifact");
    }

    void fail(String id, String error) {
        mapper.fail(id, error, Instant.now());
    }

    int recoverInterrupted() {
        // Generation remains a single-instance in-process queue; do not silently
        // replay paid model requests after restart. Persisted completed snakes survive.
        return mapper.recoverInterrupted("生成中断，请重新创建这条蛇", Instant.now());
    }

    void diagnostic(String id, int attempt, String stage, String code, String detail, Instant time) {
        mapper.diagnostic(id, attempt, bounded(stage, 40), bounded(code, 80), bounded(detail, 500), time);
    }

    List<Map<String, Object>> diagnostics(String id) {
        return mapper.diagnostics(id);
    }

    boolean importLegacy(StrategyService.StoredSnake snake, String source, String validation, String security, String intent,
                         List<LegacyStrategyImporter.Diagnostic> diagnostics) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            mapper.lockAdmission();
            if (mapper.countById(snake.id()) > 0) return false;
            boolean ready = "READY".equals(snake.status()) && source != null && !source.isBlank()
                    && source.getBytes(StandardCharsets.UTF_8).length <= 24576;
            insert(new StrategyService.StoredSnake(snake.id(), snake.owner(), snake.name(), snake.description(),
                    "GENERATING", null, snake.createdAt()));
            if (security != null) saveArtifact(snake.id(), "security", security);
            if (intent != null) saveArtifact(snake.id(), "intent", intent);
            if (ready) complete(snake.id(), source, validation == null ? "{}" : validation, "legacy-import");
            else fail(snake.id(), "FAILED".equals(snake.status()) && snake.error() != null ? bounded(snake.error(), 255) : "生成中断，请重新创建这条蛇");
            for (var item : diagnostics) diagnostic(snake.id(), item.attempt(), item.stage(), item.code(), item.detail(), item.time());
            return true;
        }));
    }

    private static String bounded(String text, int size) { return text == null ? "" : text.substring(0, Math.min(text.length(), size)); }
    static String sha256(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
