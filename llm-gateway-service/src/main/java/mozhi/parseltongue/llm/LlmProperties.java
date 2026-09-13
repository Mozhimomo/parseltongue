package mozhi.parseltongue.llm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Validated
@ConfigurationProperties("llm")
public record LlmProperties(
        @NotBlank @Size(min = 32) String gatewayToken,
        @NotNull Duration timeout,
        @Min(1) @Max(128) int maxConcurrentCalls,
        @Min(1) @Max(100000) int maxInputCharacters,
        @NotEmpty Map<@Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String, @NotNull @Valid Provider> providers,
        @NotEmpty Map<@Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String, @NotNull @Valid ModelRoute> models) {

    @AssertTrue(message = "timeout must be between 1ms and 5 minutes")
    public boolean isTimeoutValid() {
        return timeout != null && timeout.toMillis() >= 1 && timeout.compareTo(Duration.ofMinutes(5)) <= 0;
    }

    // Never include configuration credentials in an automatically generated record toString().
    @Override public String toString() { return "LlmProperties[redacted]"; }

    public record ModelRoute(@NotBlank String provider, @NotBlank String providerModel,
                             @Min(1) @Max(32768) int maxOutputTokens,
                             boolean completionTokenLimit,
                             @Pattern(regexp = "|enabled|disabled") String thinking) {}

    public record Provider(@NotBlank String apiKey, @NotNull URI baseUrl) {
        @AssertTrue(message = "base-url must be an HTTP(S) URL without credentials, query or fragment")
        public boolean isBaseUrlValid() {
            return baseUrl != null && ("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))
                    && baseUrl.getHost() != null && baseUrl.getUserInfo() == null
                    && baseUrl.getQuery() == null && baseUrl.getFragment() == null;
        }
        @Override public String toString() { return "Provider[redacted]"; }
    }
}
