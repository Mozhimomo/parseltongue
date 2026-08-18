package mozhi.parseltongue.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.auth")
public record GatewayAuthProperties(String jwtSecret) {
}
