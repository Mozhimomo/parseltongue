package mozhi.parseltongue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        Jwt jwt,
        Session session,
        Cookie cookie
) {
    public record Jwt(String secret, Duration accessTokenTtl) {
    }

    public record Session(Duration ttl) {
    }

    public record Cookie(String refreshTokenName, boolean secure, String sameSite) {
    }
}
