package mozhi.parseltongue.arena;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class SessionVerifier {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI identityUri;
    private final ObjectMapper mapper;

    public SessionVerifier(@Value("${arena.gateway-url}") String gatewayUrl, ObjectMapper mapper) {
        this.identityUri = URI.create(gatewayUrl.replaceAll("/+$", "") + "/api/users/me");
        this.mapper = mapper;
    }

    public long verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 8192) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        try {
            // Reuse the existing session-backed endpoint THROUGH the gateway.
            // Never trust a client-supplied user ID, or only validate JWT expiry.
            var response = client.send(HttpRequest.newBuilder(identityUri).timeout(Duration.ofSeconds(5))
                    .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法核验登录状态");
            }
            var json = mapper.readTree(response.body());
            var id = json.path("data").path("id");
            if (json.path("code").asInt(-1) != 0 || !id.isIntegralNumber() || id.asLong() <= 0) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证服务响应无效");
            }
            return id.asLong();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "登录核验被中断");
        } catch (IOException | tools.jackson.core.JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法连接认证服务");
        }
    }
}
