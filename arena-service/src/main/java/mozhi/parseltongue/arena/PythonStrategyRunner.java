package mozhi.parseltongue.arena;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Trusted controller: one disposable E2B VM per validation or complete match. */
@Component
public class PythonStrategyRunner {
    private final String python;
    private final Path worker;
    private final ObjectMapper mapper;
    private final Map<String, String> sandboxEnvironment;

    public PythonStrategyRunner(@Value("${arena.e2b.python:${arena.python}}") String python,
                                @Value("${arena.worker-directory:}") String directory, ObjectMapper mapper,
                                @Value("${arena.e2b.api-key:}") String apiKey,
                                @Value("${arena.e2b.template:base}") String template) {
        this.python = python;
        this.mapper = mapper;
        this.sandboxEnvironment = Map.of("E2B_API_KEY", apiKey, "E2B_TEMPLATE", template);
        Path root = Path.of("").toAbsolutePath();
        if (!directory.isBlank()) root = Path.of(directory).toAbsolutePath();
        else {
            while (root.getParent() != null && !Files.isDirectory(root.resolve("match-worker"))) root = root.getParent();
            root = root.resolve("match-worker");
        }
        worker = root;
    }

    public JsonNode preflight() throws IOException, InterruptedException {
        return run(Map.of("mode", "preflight"), Duration.ofSeconds(30));
    }

    public JsonNode validate(String source) throws IOException, InterruptedException {
        return run(Map.of("mode", "validate", "source", source), Duration.ofSeconds(130));
    }

    public String match(TrialRequest request, Map<String, Map<String, String>> strategies) throws IOException, InterruptedException {
        JsonNode result = run(Map.of("mode", "match", "agents", request.agents(),
                "seed", request.seed(), "maxTicks", request.maxTicks(), "strategies", strategies), Duration.ofSeconds(130));
        if (result.has("accepted") && !result.path("accepted").asBoolean()) {
            String code = result.path("code").asText("E2B_JOB_FAILED").replaceAll("[^A-Za-z0-9_-]", "");
            throw new IOException("E2B match failed: " + code.substring(0, Math.min(code.length(), 60)));
        }
        return mapper.writeValueAsString(result);
    }

    private JsonNode run(Map<String, ?> job, Duration timeout) throws IOException, InterruptedException {
        Path output = Files.createTempFile("snake-result-", ".json");
        Path errors = Files.createTempFile("snake-error-", ".txt");
        Process process = null;
        String jobId = UUID.randomUUID().toString().replace("-", "");
        try {
            var builder = new ProcessBuilder(python, "-I", worker.resolve("strategy_job.py").toString(), "--job-id", jobId)
                    .directory(output.getParent().toFile())
                    .redirectOutput(output.toFile()).redirectError(errors.toFile());
            configureEnvironment(builder);
            process = builder.start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(mapper.writeValueAsBytes(job));
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > deadline || Files.size(output) > 8 * 1024 * 1024 || Files.size(errors) > 65536)
                    return mapper.valueToTree(Map.of("accepted", false, "code", "JOB_BUDGET", "stage", "coordinator", "infrastructure", true, "error", "Execution exceeded time/output limit"));
            }
            if (process.exitValue() != 0 || Files.size(output) > 8 * 1024 * 1024)
                throw new IOException("Generated snake worker failed");
            return mapper.readTree(Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            try {
                cleanup(jobId);
            } finally {
                if (process != null) {
                    process.getInputStream().close();
                    process.getErrorStream().close();
                }
                // Retain cloud job IDs on failed cleanup, but release local logs.
                for (Path file : List.of(output, errors)) {
                    try { Files.deleteIfExists(file); }
                    catch (IOException ignored) { file.toFile().deleteOnExit(); }
                }
            }
        }
    }

    private void configureEnvironment(ProcessBuilder builder) {
        // The API key belongs only to the trusted SDK controller. It is never
        // uploaded or supplied in the sandbox's envs or command arguments.
        builder.environment().keySet().removeIf(key -> !Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "HOME")
                .contains(key.toUpperCase(Locale.ROOT)));
        builder.environment().putAll(sandboxEnvironment);
    }

    private void cleanup(String jobId) throws IOException {
        var builder = new ProcessBuilder(python, "-I", worker.resolve("strategy_job.py").toString(),
                "cleanup", "--job-id", jobId).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        configureEnvironment(builder);
        Process cleanup = builder.start();
        try {
            if (!cleanup.waitFor(15, TimeUnit.SECONDS) || cleanup.exitValue() != 0)
                throw new IOException("Sandbox cleanup could not be confirmed; job=" + jobId);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Sandbox cleanup interrupted; job=" + jobId);
        } finally {
            if (cleanup.isAlive()) cleanup.destroyForcibly();
        }
    }
}
