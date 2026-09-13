package mozhi.parseltongue.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmGatewayTests {
    private static final String TOKEN = "test-internal-gateway-token-32-characters";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<JsonNode> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static volatile CountDownLatch blocked;
    private static volatile CountDownLatch release;
    private static final HttpServer PROVIDER = provider();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired LlmProperties properties;

    static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/chat/completions", exchange -> {
                CALLS.incrementAndGet();
                var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                LAST_REQUEST.set(request);
                LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String prompt = request.path("messages").get(0).path("content").asText();
                if (prompt.equals("blocked")) {
                    blocked.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                int status = prompt.equals("error") ? 500 : 200;
                String body = status == 500 ? "{\"error\":{\"message\":\"sensitive-upstream-body\",\"type\":\"error\"}}" : """
                        {"id":"fake-completion","object":"chat.completion","created":1,"model":"deepseek-v4-flash",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"UP"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                        """;
                if (prompt.equals("empty")) body = body.replace("\"UP\"", "\"\"");
                if (prompt.equals("no-usage")) {
                    var json = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
                    json.remove("usage");
                    body = JSON.writeValueAsString(json);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("llm.gateway-token", () -> TOKEN);
        registry.add("llm.providers.deepseek.api-key", () -> "fake-provider-key");
        registry.add("llm.providers.deepseek.base-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
        registry.add("llm.max-concurrent-calls", () -> 1);
        registry.add("llm.max-input-characters", () -> 1000);
        registry.add("llm.timeout", () -> "2s");
    }

    @AfterAll static void close() { PROVIDER.stop(0); EXECUTOR.shutdownNow(); }

    private HttpRequest.Builder request(String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private HttpResponse<String> post(String body, String token) throws Exception {
        return http.send(request("/internal/llm/generations", token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String body(String model, String prompt) {
        return JSON.writeValueAsString(new GenerationRequest("test-task", model,
                List.of(new GenerationRequest.ChatMessage("user", prompt)), 32));
    }

    @Test void authenticatesBeforeCallingProvider() throws Exception {
        int before = CALLS.get();
        assertEquals(401, post(body("low-cost", "hello"), null).statusCode());
        assertEquals(401, post(body("low-cost", "hello"), "invalid").statusCode());
        assertEquals(before, CALLS.get());
    }

    @Test void bothRegisteredTiersUseFlashWithOpenAiWireFormat() throws Exception {
        for (String alias : List.of("low-cost", "high-capability")) {
            var response = post(body(alias, "hello"), TOKEN);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode data = JSON.readTree(response.body()).path("data");
            assertEquals(alias, data.path("model").asText());
            assertEquals("UP", data.path("content").asText());
            assertEquals("stop", data.path("finishReason").asText());
            assertEquals(12, data.path("usage").path("totalTokens").asInt());
            assertEquals(data.path("llmRequestId").asText(), response.headers().firstValue("X-Request-Id").orElseThrow());
            assertEquals("Bearer fake-provider-key", LAST_AUTH.get());
            assertEquals("deepseek-v4-flash", LAST_REQUEST.get().path("model").asText());
            assertEquals(32, LAST_REQUEST.get().path("max_tokens").asInt());
            assertEquals("disabled", LAST_REQUEST.get().path("thinking").path("type").asText());
            assertFalse(LAST_REQUEST.get().has("tools"));
            assertFalse(LAST_REQUEST.get().has("temperature"));
        }
    }

    @Test void registryExposesAliasesAndLimitsWithoutCredentials() throws Exception {
        var response = http.send(request("/internal/llm/models", TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(2, JSON.readTree(response.body()).path("data").size());
        assertFalse(response.body().contains("fake-provider-key"));
        assertFalse(response.body().contains("baseUrl"));
    }

    @Test void missingUsageIsUnknownInsteadOfZeroCost() throws Exception {
        var response = post(body("low-cost", "no-usage"), TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).path("data").path("usage").isNull());
    }

    @Test void rejectsInvalidMessagesModelAndProviderOverridesBeforeCallingProvider() throws Exception {
        int before = CALLS.get();
        String valid = body("low-cost", "hello");
        for (String invalid : List.of(valid.replace("low-cost", "unregistered"),
                valid.replace("\"user\"", "\"tool\""), valid.replace("32", "9999"),
                valid.replace("\"hello\"", "null"), valid.replace("\"user\"", "\"system\""),
                valid.replace("\"taskId\"", "\"apiKey\":\"override\",\"taskId\""),
                valid.replace("\"taskId\"", "\"baseUrl\":\"http://example.com\",\"taskId\""),
                "{\"taskId\":\"t\",\"model\":\"low-cost\",\"messages\":[null]}")) {
            assertEquals(400, post(invalid, TOKEN).statusCode(), invalid);
        }
        assertEquals(before, CALLS.get());
    }

    @Test void boundsCharactersAndBytesIncludingChunkedRequests() throws Exception {
        int before = CALLS.get();
        assertEquals(413, post(body("low-cost", "a".repeat(1001)), TOKEN).statusCode());
        assertEquals(413, post(body("low-cost", "a".repeat(140000)), TOKEN).statusCode());
        byte[] oversized = body("low-cost", "a".repeat(140000)).getBytes(StandardCharsets.UTF_8);
        var chunked = request("/internal/llm/generations", TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(oversized))).build();
        assertEquals(413, http.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(before, CALLS.get());
    }

    @Test void masksProviderFailuresAndDoesNotRetry() throws Exception {
        int before = CALLS.get();
        var response = post(body("low-cost", "error"), TOKEN);
        assertEquals(502, response.statusCode());
        assertFalse(response.body().contains("sensitive-upstream-body"));
        assertFalse(response.body().contains("fake-provider-key"));
        assertEquals(before + 1, CALLS.get());
        assertEquals(502, post(body("low-cost", "empty"), TOKEN).statusCode());
        assertEquals(200, post(body("low-cost", "hello"), TOKEN).statusCode());
    }

    @Test void limitsConcurrencyTimesOutAndReleasesCapacity() throws Exception {
        blocked = new CountDownLatch(1);
        release = new CountDownLatch(1);
        int before = CALLS.get();
        var first = CompletableFuture.supplyAsync(() -> {
            try { return post(body("low-cost", "blocked"), TOKEN); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            assertEquals(429, post(body("high-capability", "hello"), TOKEN).statusCode());
            assertEquals(504, first.get(6, TimeUnit.SECONDS).statusCode());
            assertEquals(before + 1, CALLS.get());
        } finally { release.countDown(); }
        assertEquals(200, post(body("low-cost", "hello"), TOKEN).statusCode());
    }

    @Test void healthIsPublicAndOtherRoutesAreClosed() throws Exception {
        assertEquals(200, http.send(request("/actuator/health", null).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(request("/actuator/env", TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(properties.toString().contains(TOKEN));
        assertFalse(properties.providers().get("deepseek").toString().contains("fake-provider-key"));
    }
}
