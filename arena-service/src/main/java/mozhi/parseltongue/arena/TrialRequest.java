package mozhi.parseltongue.arena;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TrialRequest(
        @NotNull @Size(min = 4, max = 4)
        List<@NotNull @Pattern(regexp = "straight|cautious|greedy|forager|snake:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String> agents,
        @NotNull @Min(0) @Max(2147483647) Integer seed,
        @NotNull @Min(1) @Max(2000) Integer maxTicks
) {}
