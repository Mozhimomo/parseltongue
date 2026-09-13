package mozhi.parseltongue.arena;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/game/snakes")
public class StrategyController {
    private final StrategyService strategies;
    public StrategyController(StrategyService strategies) { this.strategies = strategies; }
    public record Request(@NotBlank @Size(max=40) String name,
                          @NotBlank @Size(max=8000) String description) {}
    @GetMapping
    public GameController.Envelope<List<StrategyService.Status>> list(@RequestAttribute("gameUserId") long user) {
        return GameController.Envelope.ok(strategies.list(user));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GameController.Envelope<StrategyService.Status> create(@RequestAttribute("gameUserId") long user,
                                                                  @Valid @RequestBody Request request) {
        return GameController.Envelope.ok(strategies.create(user, request));
    }
    @GetMapping("/{id}")
    public GameController.Envelope<StrategyService.Status> status(@RequestAttribute("gameUserId") long user, @PathVariable String id) {
        return GameController.Envelope.ok(strategies.status(user, id));
    }
}
