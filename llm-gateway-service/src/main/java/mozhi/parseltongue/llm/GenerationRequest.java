package mozhi.parseltongue.llm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record GenerationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String taskId,
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String model,
        @NotEmpty @Size(max = 32) List<@NotNull @Valid ChatMessage> messages,
        @Min(1) @Max(32768) Integer maxOutputTokens) {
    public record ChatMessage(@NotBlank @Pattern(regexp = "system|user|assistant") String role,
                              @NotBlank @Size(max = 100000) String content) {}
}
