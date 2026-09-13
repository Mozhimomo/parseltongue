package mozhi.parseltongue.internalgateway.config;

import mozhi.parseltongue.internalgateway.web.GatewayErrorWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class InternalGatewayConfig {
    private final byte[] callerAuthorization;
    private final String llmToken;

    public InternalGatewayConfig(@Value("${gateway.internal.caller-token}") String callerToken,
                                 @Value("${gateway.internal.llm-token}") String llmToken) {
        if (callerToken.isBlank() || llmToken.isBlank() || callerToken.length() < 32 || llmToken.length() < 32
                || callerToken.equals(llmToken)) {
            throw new IllegalStateException("内部网关需要两个不同的、至少 32 字符的服务令牌");
        }
        this.callerAuthorization = ("Bearer " + callerToken).getBytes(StandardCharsets.UTF_8);
        this.llmToken = llmToken;
    }

    @Bean
    SecurityWebFilterChain internalSecurity(ServerHttpSecurity http, GatewayErrorWriter errors) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .addFilterAt((exchange, chain) -> {
                    var request = exchange.getRequest();
                    String path = request.getPath().value();
                    if (request.getMethod() == HttpMethod.GET && "/actuator/health".equals(path)) {
                        return chain.filter(exchange);
                    }
                    boolean allowed = (request.getMethod() == HttpMethod.POST && "/internal/llm/generations".equals(path))
                            || (request.getMethod() == HttpMethod.GET && "/internal/llm/models".equals(path));
                    if (!allowed) {
                        return errors.write(exchange, 403, 40300, "内部入口不允许访问该接口");
                    }
                    String authorization = request.getHeaders().getFirst("Authorization");
                    if (authorization == null || !MessageDigest.isEqual(callerAuthorization,
                            authorization.getBytes(StandardCharsets.UTF_8))) {
                        return errors.write(exchange, 401, 40100, "无效的调用服务凭据");
                    }
                    return chain.filter(exchange);
                }, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .pathMatchers(HttpMethod.POST, "/internal/llm/generations").permitAll()
                        .pathMatchers(HttpMethod.GET, "/internal/llm/models").permitAll()
                        .anyExchange().denyAll())
                .build();
    }

    @Bean
    GlobalFilter llmGatewayCredentials() {
        return (exchange, chain) -> chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.setBearerAuth(llmToken);
                    headers.remove("Cookie");
                    headers.remove("X-User-Id");
                    headers.remove("X-Service-Id");
                }).build()).build());
    }
}
