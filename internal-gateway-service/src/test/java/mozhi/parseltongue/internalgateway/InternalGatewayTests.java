package mozhi.parseltongue.internalgateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalGatewayTests {
    static final String CALLER = "test-generation-service-token-32-characters";
    static final String DOWNSTREAM = "test-llm-gateway-token-32-characters";
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicReference<String> AUTH = new AtomicReference<>();
    static final AtomicReference<String> COOKIE = new AtomicReference<>();
    static final AtomicReference<String> USER = new AtomicReference<>();
    static final DisposableServer LLM = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                CALLS.incrementAndGet();
                AUTH.set(request.requestHeaders().get("Authorization"));
                COOKIE.set(request.requestHeaders().get("Cookie"));
                USER.set(request.requestHeaders().get("X-User-Id"));
                return response.header("Content-Type", "application/json").sendString(Mono.just("{\"llm\":true}"));
            }).bindNow();
    private final WebTestClient client;

    @Autowired InternalGatewayTests(@LocalServerPort int port) {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("gateway.internal.caller-token", () -> CALLER);
        registry.add("gateway.internal.llm-token", () -> DOWNSTREAM);
        registry.add("LLM_SERVICE_URL", () -> "http://127.0.0.1:" + LLM.port());
    }

    @AfterAll static void close() { LLM.disposeNow(); }

    @Test void requiresCallerIdentityAndReplacesDownstreamCredentials() {
        int before = CALLS.get();
        client.post().uri("/internal/llm/generations").bodyValue("{}").exchange().expectStatus().isUnauthorized();
        client.post().uri("/internal/llm/generations").headers(h -> h.setBearerAuth(DOWNSTREAM))
                .bodyValue("{}").exchange().expectStatus().isUnauthorized();
        assertEquals(before, CALLS.get());
        client.post().uri("/internal/llm/generations").headers(h -> h.setBearerAuth(CALLER))
                .header("Cookie", "session=private").header("X-User-Id", "forged")
                .bodyValue("{}").exchange().expectStatus().isOk().expectBody().jsonPath("$.llm").isEqualTo(true);
        assertEquals("Bearer " + DOWNSTREAM, AUTH.get());
        assertNull(COOKIE.get());
        assertNull(USER.get());
    }

    @Test void internalGatewayHasNoPublicBusinessRoutes() {
        for (String path : new String[]{"/api/auth/login", "/api/game/agents", "/internal/llm/other"}) {
            client.post().uri(path).headers(h -> h.setBearerAuth(CALLER)).bodyValue("{}")
                    .exchange().expectStatus().isForbidden();
        }
        client.get().uri("/internal/llm/generations").headers(h -> h.setBearerAuth(CALLER))
                .exchange().expectStatus().isForbidden();
    }

    @Test void modelRegistryRequiresServiceToken() {
        client.get().uri("/internal/llm/models").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/llm/models").headers(h -> h.setBearerAuth(CALLER))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.llm").isEqualTo(true);
    }

    @Test void rejectsOversizedBodyAndExposesHealth() {
        client.post().uri("/internal/llm/generations").headers(h -> h.setBearerAuth(CALLER))
                .bodyValue("x".repeat(140000)).exchange().expectStatus().isEqualTo(413);
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
