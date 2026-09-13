package mozhi.parseltongue.arena;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TrialService {
    private static final Logger LOG = LoggerFactory.getLogger(TrialService.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<String, Trial> trials = new LinkedHashMap<>();
    private final PythonMatchRunner runner;
    private final ObjectMapper mapper;
    private final StrategyService strategies;
    private final PythonStrategyRunner strategyRunner;

    private static class Trial {
        final String id = UUID.randomUUID().toString();
        final long owner;
        final Instant createdAt = Instant.now();
        String status = "QUEUED";
        String error;
        String replay;
        JsonNode result;
        Trial(long owner) { this.owner = owner; }
        boolean active() { return status.equals("QUEUED") || status.equals("RUNNING"); }
    }

    public record Status(String id, String status, String error, JsonNode result, Instant createdAt) {}

    public TrialService(PythonMatchRunner runner, ObjectMapper mapper, StrategyService strategies, PythonStrategyRunner strategyRunner) {
        this.runner = runner;
        this.mapper = mapper;
        this.strategies = strategies;
        this.strategyRunner = strategyRunner;
    }

    public synchronized Status create(long owner, TrialRequest request) {
        prune();
        if (trials.values().stream().filter(Trial::active).count() >= 8
                || trials.values().stream().filter(t -> t.owner == owner && t.active()).count() >= 2) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "比赛队列已满，请稍后再试");
        }
        var selected = strategies.matchStrategies(owner, request);
        Trial trial = new Trial(owner);
        trials.put(trial.id, trial);
        executor.submit(() -> execute(trial, request, selected));
        return view(trial);
    }

    private void execute(Trial trial, TrialRequest request, Map<String, Map<String, String>> selected) {
        synchronized (this) { trial.status = "RUNNING"; }
        try {
            String replay = selected.isEmpty() ? runner.run(request) : strategyRunner.match(request, selected);
            JsonNode json = mapper.readTree(replay);
            if (!json.path("frames").isArray() || !json.path("result").isObject()) {
                throw new IllegalStateException("Invalid worker output");
            }
            synchronized (this) {
                trial.replay = replay;
                trial.result = json.get("result");
                trial.status = "SUCCEEDED";
                prune();
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.error("Trial {} failed", trial.id, exception);
            synchronized (this) {
                trial.status = "FAILED";
                trial.error = "比赛暂时无法完成，请稍后重试";
            }
        }
    }

    public synchronized Status status(long owner, String id) { return view(find(owner, id)); }

    public synchronized JsonNode replay(long owner, String id) {
        Trial trial = find(owner, id);
        if (!trial.status.equals("SUCCEEDED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "比赛尚未完成");
        }
        return mapper.readTree(trial.replay);
    }

    private Trial find(long owner, String id) {
        prune();
        Trial trial = trials.get(id);
        if (trial == null || trial.owner != owner) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "比赛不存在或已过期");
        }
        return trial;
    }

    private Status view(Trial trial) {
        return new Status(trial.id, trial.status, trial.error, trial.result, trial.createdAt);
    }

    private void prune() {
        trials.values().removeIf(t -> !t.active() && t.createdAt.isBefore(Instant.now().minusSeconds(1800)));
        long chars = trials.values().stream().mapToLong(t -> t.replay == null ? 0 : t.replay.length()).sum();
        var iterator = trials.values().iterator();
        while (iterator.hasNext() && (trials.size() >= 24 || chars > 16 * 1024 * 1024)) {
            Trial trial = iterator.next();
            if (!trial.active()) {
                chars -= trial.replay == null ? 0 : trial.replay.length();
                iterator.remove();
            }
        }
    }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }
}
