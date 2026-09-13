package mozhi.parseltongue.llm;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class GenerationController {
    private final GenerationService generations;
    private final ModelRegistry registry;
    public GenerationController(GenerationService generations, ModelRegistry registry) {
        this.generations = generations;
        this.registry = registry;
    }

    @GetMapping("/internal/llm/models")
    public Envelope<java.util.List<ModelRegistry.ModelDescriptor>> models() {
        return new Envelope<>(0, "ok", registry.models());
    }

    @PostMapping("/internal/llm/generations")
    public Envelope<GenerationResponse> generate(@Valid @RequestBody GenerationRequest request,
            @RequestAttribute("llmRequestId") String requestId) {
        return new Envelope<>(0, "ok", generations.generate(request, requestId));
    }

    public record Envelope<T>(int code, String message, T data) {}
}
