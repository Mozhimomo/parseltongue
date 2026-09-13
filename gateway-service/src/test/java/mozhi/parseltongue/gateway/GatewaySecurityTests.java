package mozhi.parseltongue.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class GatewaySecurityTests {

    private static final String JWT_SECRET =
            "dGVzdC1qd3Qtc2VjcmV0LW11c3QtYmUtYXQtbGVhc3QtMzItYnl0ZXM=";
    private static final DisposableServer BACKEND = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> response
                    .status(200)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .sendString(Mono.just("{\"ok\":true}")))
            .bindNow();

    private final WebTestClient webTestClient;

    private static final DisposableServer ARENA = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> response.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .sendString(Mono.just("{\"arena\":true}"))).bindNow();

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + BACKEND.port());
        registry.add("ARENA_SERVICE_URL", () -> "http://127.0.0.1:" + ARENA.port());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.disposeNow();
        ARENA.disposeNow();
    }

    @Autowired
    GatewaySecurityTests(@LocalServerPort int port) {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void protectedApiRequiresAccessToken() {
        webTestClient.get()
                .uri("/api/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(40100)
                .jsonPath("$.data").doesNotExist();
    }

    @Test
    void loginIsForwardedWithoutAccessToken() {
        webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.ok").isEqualTo(true);
    }

    @Test
    void validAccessTokenAllowsProtectedApiThroughGateway() throws Exception {
        String accessToken = createAccessToken();
        webTestClient.get()
                .uri("/api/users/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ok").isEqualTo(true);
    }

    @Test
    void corsPreflightIsHandledByGateway() {
        webTestClient.options()
                .uri("/api/users/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");
    }

    @Test
    void missingRouteUsesGatewayErrorFormat() throws Exception {
        String accessToken = createAccessToken();
        webTestClient.get()
                .uri("/missing")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(40400);
    }

    @Test
    void healthEndpointIsPublic() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void publicGatewayRejectsInternalModelCallsEvenWithUserJwt() throws Exception {
        String token = createAccessToken();
        webTestClient.post().uri("/internal/llm/generations")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
    }

    private String createAccessToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .claim("sid", "test-session")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(Base64.getDecoder().decode(JWT_SECRET)));
        return token.serialize();
    }

    @Test
    void gameRoutesRequireTokenAndReachArenaInsteadOfAuth() throws Exception {
        String token = createAccessToken();
        webTestClient.get().uri("/api/game/agents").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/game/agents").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.arena").isEqualTo(true);
    }
}
