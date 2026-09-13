package mozhi.parseltongue.arena;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/game")
public class GameController {
    private final TrialService trials;
    private final StrategyService strategies;
    public GameController(TrialService trials, StrategyService strategies) {
        this.trials = trials;
        this.strategies = strategies;
    }
    public record Envelope<T>(int code, String message, T data) {
        static <T> Envelope<T> ok(T data) {
            return new Envelope<>(0, "success", data);
        }
    }
    public record Agent(String id, String name, String description) {}

    @GetMapping("/agents")
    public Envelope<List<Agent>> agents(@RequestAttribute("gameUserId") long user) {
        var agents = new java.util.ArrayList<>(List.of(
                new Agent("straight", "直行蛇", "保持方向，用于验证撞墙与淘汰"),
                new Agent("cautious", "避碰蛇", "前方安全时直行，否则尝试转向"),
                new Agent("greedy", "贪食蛇", "避开已占用格，朝最近的食物移动"),
                new Agent("forager", "寻路蛇", "搜索食物路径，评估空间并躲避对手蛇头")));
        strategies.list(user).stream().filter(s -> s.status().equals("READY"))
                .forEach(s -> agents.add(new Agent("snake:" + s.id(), s.name(), s.description())));
        return Envelope.ok(agents);
    }

    @PostMapping("/trials")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Envelope<TrialService.Status> create(@RequestAttribute("gameUserId") long user,
                                                @Valid @RequestBody TrialRequest request) {
        return Envelope.ok(trials.create(user, request));
    }

    @GetMapping("/trials/{id}")
    public Envelope<TrialService.Status> status(@RequestAttribute("gameUserId") long user, @PathVariable String id) {
        return Envelope.ok(trials.status(user, id));
    }

    @GetMapping("/trials/{id}/replay")
    public Envelope<tools.jackson.databind.JsonNode> replay(@RequestAttribute("gameUserId") long user,
                                                           @PathVariable String id) {
        return Envelope.ok(trials.replay(user, id));
    }
}
