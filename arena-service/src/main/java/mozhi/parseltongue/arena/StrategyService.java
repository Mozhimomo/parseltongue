package mozhi.parseltongue.arena;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Complete generation lifecycle; only product metadata leaves this service. */
@Service
public class StrategyService {
    private static final Logger LOG = LoggerFactory.getLogger(StrategyService.class);
    private static final Set<String> BUILTINS = Set.of("straight", "cautious", "greedy", "forager");
    private static final Pattern FENCE = Pattern.compile("(?s)```(?:python|py)?\\s*\\n(.*?)```");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI gateway;
    private final String token, model, intentModel, securityModel;
    private final ObjectMapper mapper;
    private final PythonStrategyRunner runner;
    private final StrategyRepository repository;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    // Database metadata; also understands the legacy JSON format. Never expose owner.
    public record StoredSnake(String id, long owner, String name, String description, String status,
                              String error, Instant createdAt) {}
    public record Status(String id, String name, String description, String status, String error, Instant createdAt) {}

    public StrategyService(@Value("${arena.internal-gateway-url}") String gateway,
                           @Value("${arena.generation-service-token}") String token,
                           @Value("${arena.generation-model:high-capability}") String model,
                           @Value("${arena.intent-model:low-cost}") String intentModel,
                           @Value("${arena.security-model:low-cost}") String securityModel,
                           @Value("${arena.snake-directory:}") String directory,
                           ObjectMapper mapper, PythonStrategyRunner runner, StrategyRepository repository) throws IOException {
        this.gateway = URI.create(gateway.replaceAll("/+$", "") + "/");
        this.token = token; this.model = model; this.intentModel = intentModel; this.mapper = mapper; this.runner = runner;
        this.securityModel = securityModel;
        this.repository = repository;
        Path root = Path.of("").toAbsolutePath();
        if (root.getFileName().toString().equals("arena-service")) root = root.getParent();
        Path legacy = directory.isBlank() ? root.resolve(".local/snakes") : Path.of(directory).toAbsolutePath();
        int imported = LegacyStrategyImporter.run(legacy, mapper, repository);
        int interrupted = repository.recoverInterrupted();
        LOG.info("Strategy library initialized imported={} interrupted={}", imported, interrupted);
    }

    public synchronized Status create(long owner, StrategyController.Request request) {
        StrategyIntent.checkRequest(request);
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法创建，请稍后再试");
        StoredSnake snake = repository.create(owner, request);
        try { executor.submit(() -> generate(snake)); }
        catch (RejectedExecutionException error) {
            repository.fail(snake.id(), "创建服务暂时不可用，请稍后重试");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法创建，请稍后再试");
        }
        return view(snake);
    }

    private void generate(StoredSnake snake) {
        int currentAttempt = 0;
        String stage = "security_rules";
        try {
            List<String> signals = StrategySecurity.signals(snake.name(), snake.description());
            stage = "preflight";
            JsonNode preflight = runner.preflight();
            if (!preflight.path("accepted").asBoolean(false)) {
                diagnostic(snake.id(), 0, "preflight", preflight.path("code").asText("SANDBOX_UNAVAILABLE"), preflight.path("error").asText());
                fail(snake, "创建服务暂时不可用，请稍后重试");
                return;
            }
            stage = "security";
            StrategySecurity review = reviewSecurity(snake, signals);
            repository.saveArtifact(snake.id(), "security", mapper.writeValueAsString(Map.of(
                    "policy", "request-security-v1", "ruleSignals", signals, "review", review)));
            if (!review.allowed()) {
                diagnostic(snake.id(), 0, "security", "SECURITY_" + review.decision(), "Request did not pass security review");
                fail(snake, "这次未能创建成功，请调整描述后重试");
                return;
            }
            stage = "intent";
            StrategyIntent intent = interpret(snake);
            repository.saveArtifact(snake.id(), "intent", mapper.writeValueAsString(intent));
            if (!intent.supported()) {
                diagnostic(snake.id(), 0, "intent", "UNSUPPORTED_INTENT", "No usable snake gameplay intent");
                fail(snake, "请描述你希望蛇如何行动，例如寻找食物、躲避对手或主动进攻");
                return;
            }
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", StrategyPrompt.GENERATION));
            messages.add(Map.of("role", "user", "content", StrategyPrompt.userContent(
                    snake.name(), snake.description(), intent, mapper)));
            for (int attempt = 1; attempt <= 3; attempt++) {
                currentAttempt = attempt;
                stage = "model";
                JsonNode response = exchange(Map.of("taskId", snake.id() + "-code-" + attempt, "model", model, "messages", messages));
                String text = response.path("content").asText("");
                String problem;
                if (!response.path("finishReason").asText().equalsIgnoreCase("stop")) {
                    problem = "Output was incomplete. Return a shorter complete Python module.";
                    diagnostic(snake.id(), attempt, "model", "OUTPUT_INCOMPLETE", problem);
                } else {
                    try {
                        String source = extract(text);
                        stage = "validation";
                        JsonNode result = runner.validate(source);
                        if (result.path("infrastructure").asBoolean(false)) {
                            diagnostic(snake.id(), attempt, result.path("stage").asText("infrastructure"), result.path("code").asText("SANDBOX_UNAVAILABLE"), result.path("error").asText());
                            fail(snake, "创建服务暂时不可用，请稍后重试");
                            return;
                        }
                        if (result.path("accepted").asBoolean(false)) {
                            stage = "persistence";
                            repository.complete(snake.id(), source, mapper.writeValueAsString(result), model);
                            LOG.info("snake id={} ready attempts={} checks={}", snake.id(), attempt, result.path("testsPassed").asInt());
                            return;
                        }
                        problem = result.path("error").asText("Worker returned an invalid result");
                        diagnostic(snake.id(), attempt, result.path("stage").asText("validation"), result.path("code").asText("INVALID_RESULT"), problem);
                    } catch (IllegalArgumentException failure) {
                        problem = failure.getMessage();
                        diagnostic(snake.id(), attempt, "extract", "INVALID_SOURCE", problem);
                    }
                }
                LOG.info("snake id={} attempt={} needs correction", snake.id(), attempt);
                StrategyPrompt.addCorrection(messages, text, problem);
            }
            fail(snake, "这次未能创建成功，请调整描述后重试");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            diagnostic(snake.id(), currentAttempt, stage, failure.getClass().getSimpleName(), "Generation stopped at " + stage);
            LOG.warn("snake id={} generation failed type={}", snake.id(), failure.getClass().getSimpleName());
            fail(snake, "这次创建未完成，请稍后重试");
        }
    }

    private StrategySecurity reviewSecurity(StoredSnake snake, List<String> signals) throws IOException, InterruptedException {
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", StrategySecurity.PROMPT));
        messages.add(Map.of("role", "user", "content", mapper.writeValueAsString(Map.of(
                "name", snake.name(), "description", snake.description(), "ruleSignals", signals))));
        for (int attempt = 1; attempt <= 2; attempt++) {
            JsonNode response = exchange(Map.of("taskId", snake.id() + "-security-" + attempt,
                    "model", securityModel, "maxOutputTokens", 512, "messages", messages));
            try {
                if (!response.path("finishReason").asText().equalsIgnoreCase("stop"))
                    throw new IllegalArgumentException("Security output was incomplete");
                StrategySecurity review = StrategySecurity.parse(response.path("content").asText(), mapper);
                LOG.info("snake id={} security completed attempt={} decision={}", snake.id(), attempt, review.decision());
                return review;
            } catch (IllegalArgumentException error) {
                diagnostic(snake.id(), attempt, "security", "INVALID_SECURITY_OUTPUT", error.getMessage());
                if (attempt == 1) messages.add(Map.of("role", "user", "content",
                        "Return one valid JSON security verdict for the original input, matching the exact required schema."));
            }
        }
        // Invalid/absent review is never permission to proceed to intent or code.
        throw new IOException("Security review unavailable");
    }

    private StrategyIntent interpret(StoredSnake snake) throws IOException, InterruptedException {
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", StrategyIntent.PROMPT));
        messages.add(Map.of("role", "user", "content", mapper.writeValueAsString(Map.of(
                "name", snake.name(), "description", snake.description()))));
        for (int attempt = 1; attempt <= 2; attempt++) {
            JsonNode response = exchange(Map.of("taskId", snake.id() + "-intent-" + attempt,
                    "model", intentModel, "maxOutputTokens", 1024, "messages", messages));
            try {
                if (!response.path("finishReason").asText().equalsIgnoreCase("stop"))
                    throw new IllegalArgumentException("Intent output was incomplete");
                StrategyIntent intent = StrategyIntent.parse(response.path("content").asText(), mapper);
                LOG.info("snake id={} intent completed attempt={} supported={}", snake.id(), attempt, intent.supported());
                return intent;
            } catch (IllegalArgumentException error) {
                diagnostic(snake.id(), attempt, "intent", "INVALID_INTENT_OUTPUT", error.getMessage());
                // Retry malformed output once on the cheap model; never upgrade a
                // failed classification to an expensive code-generation request.
                if (attempt == 1) messages.add(Map.of("role", "user", "content",
                        "Your previous response did not match the required JSON schema. Reinterpret the original request and return a shorter valid JSON object only."));
            }
        }
        throw new IOException("Intent extraction failed");
    }

    static String extract(String text) {
        var matcher = FENCE.matcher(text.strip());
        String source = matcher.find() ? matcher.group(1).strip() : text.strip();
        if (source.isBlank() || (source + "\n").getBytes(StandardCharsets.UTF_8).length > 24576)
            throw new IllegalArgumentException("Return a nonempty Python module under 24 KiB");
        return source + "\n";
    }

    private JsonNode exchange(Map<String, ?> payload) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(gateway.resolve("internal/llm/generations"))
                .timeout(Duration.ofSeconds(75)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // Do not replay transport failures: upstream may already have completed the request.
        if (response.statusCode() != 200) {
            LOG.warn("Internal generation gateway status={}", response.statusCode());
            throw new IOException("Internal model gateway HTTP " + response.statusCode());
        }
        JsonNode envelope = mapper.readTree(response.body());
        if (envelope.path("code").asInt(-1) != 0 || !envelope.path("data").path("content").isTextual())
            throw new IOException("Invalid generation envelope");
        return envelope.path("data");
    }

    public List<Status> list(long owner) {
        return repository.list(owner).stream().map(StrategyService::view).toList();
    }
    public Status status(long owner, String id) { return view(repository.find(owner, id)); }
    public Map<String, Map<String, String>> matchStrategies(long owner, TrialRequest request) {
        Map<String, Map<String, String>> selected = new HashMap<>();
        for (int i = 0; i < request.agents().size(); i++) {
            String id = request.agents().get(i);
            if (BUILTINS.contains(id)) continue;
            StoredSnake snake = repository.find(owner, id.replaceFirst("^snake:", ""));
            if (!snake.status().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT, "这条蛇还没有创建完成");
            var version = repository.version(owner, snake.id());
            var data = new HashMap<>(Map.of("source", version.source(), "name", snake.name(), "sha256", version.sha256()));
            JsonNode proof = mapper.readTree(version.validation());
            for (String field : List.of("image", "policy", "runtime")) data.put(field, proof.path(field).asText(""));
            selected.put("s" + (i + 1), Map.copyOf(data));
        }
        return Map.copyOf(selected);
    }
    private void diagnostic(String id, int attempt, String stage, String code, String error) {
        String detail = error == null ? "" : error.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        detail = detail.substring(0, Math.min(detail.length(), 500));
        LOG.warn("snake id={} attempt={} stage={} code={} detail={}", id, attempt, stage, code, detail);
        try { repository.diagnostic(id, attempt, stage, code, detail, Instant.now()); }
        catch (RuntimeException failure) { LOG.error("snake id={} diagnostic persistence failed", id); }
    }

    private static Status view(StoredSnake s) { return new Status(s.id(), s.name(), s.description(), s.status(), s.error(), s.createdAt()); }
    private void fail(StoredSnake snake, String error) {
        try { repository.fail(snake.id(), error); }
        catch (RuntimeException failure) { LOG.error("snake id={} failed state could not be persisted", snake.id()); }
    }
    @PreDestroy public void stop() { executor.shutdownNow(); }
}
