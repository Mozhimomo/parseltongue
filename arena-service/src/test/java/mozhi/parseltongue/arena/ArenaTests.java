package mozhi.parseltongue.arena;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ArenaTests {
    private static final HttpServer GATEWAY = gateway();
    private static final java.nio.file.Path TEST_SNAKES = tempDirectory();
    private static java.nio.file.Path tempDirectory() { try { return java.nio.file.Files.createTempDirectory("arena-snakes-test-"); } catch (java.io.IOException e) { throw new RuntimeException(e); } }
    private static final String BODY = """
            {"agents":["straight","cautious","greedy","forager"],"seed":42,"maxTicks":50}
            """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static HttpServer gateway() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/users/me", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                int status = "Bearer player1".equals(authorization) || "Bearer player2".equals(authorization) ? 200 : 401;
                String id = "Bearer player2".equals(authorization) ? "2" : "1";
                byte[] body = (status == 200 ? "{\"code\":0,\"data\":{\"id\":" + id + "}}" : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:arena-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("arena.snake-directory", () -> TEST_SNAKES.toString());
        registry.add("arena.gateway-url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
    }

    @AfterAll
    static void close() { GATEWAY.stop(0); }

    @Test
    void requiresSessionAndRejectsRevokedTokens() throws Exception {
        mvc.perform(get("/api/game/agents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/game/agents").header("Authorization", "Bearer revoked"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void exposesOnlyFourBuiltinStrategies() throws Exception {
        mvc.perform(get("/api/game/agents").header("Authorization", "Bearer player1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[3].id").value("forager"));
    }

    @Test
    void validatesStrategiesDimensionsAndUnknownFields() throws Exception {
        for (String invalid : List.of(BODY.replace("straight", "../evil.py"),
                BODY.replace("50", "2001"), BODY.replace("42", "-1"),
                BODY.replace("\"straight\",", ""), BODY.replace("\"seed\":42", "\"seed\":null"),
                BODY.replace("\"seed\":42", "\"seed\":42,\"code\":\"print(123)\""))) {
            mvc.perform(post("/api/game/trials").header("Authorization", "Bearer player1")
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void realPythonMatchProducesPrivateReplay() throws Exception {
        String created = mvc.perform(post("/api/game/trials").header("Authorization", "Bearer player1")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("data").path("id").asText();
        String taskStatus = "";
        for (int i = 0; i < 200; i++) {
            String response = mvc.perform(get("/api/game/trials/" + id).header("Authorization", "Bearer player1"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            taskStatus = mapper.readTree(response).path("data").path("status").asText();
            if (taskStatus.equals("SUCCEEDED") || taskStatus.equals("FAILED")) break;
            Thread.sleep(50);
        }
        assertEquals("SUCCEEDED", taskStatus, "Python worker must be installed and runnable");
        mvc.perform(get("/api/game/trials/" + id + "/replay").header("Authorization", "Bearer player1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.seed").value(42))
                .andExpect(jsonPath("$.data.frames[0].state.snakes.length()").value(4))
                .andExpect(jsonPath("$.data.frames[1].state.tick").value(1))
                .andExpect(jsonPath("$.data.result.ticks").isNumber());
        mvc.perform(get("/api/game/trials/" + id).header("Authorization", "Bearer player2"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/game/trials/" + id + "/replay").header("Authorization", "Bearer player2"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/game/trials/" + id + "/replay").header("Authorization", "Bearer revoked"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingMatchDoesNotLeakDetails() throws Exception {
        mvc.perform(get("/api/game/trials/missing/replay").header("Authorization", "Bearer player1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void perUserQueueLimitAndPendingReplayAreEnforced() throws Exception {
        var runner = mock(PythonMatchRunner.class);
        var release = new CountDownLatch(1);
        when(runner.run(any())).thenAnswer(call -> {
            release.await(5, TimeUnit.SECONDS);
            return "{\"frames\":[],\"result\":{}}";
        });
        var service = new TrialService(runner, mapper, mock(StrategyService.class), mock(PythonStrategyRunner.class));
        var request = new TrialRequest(List.of("straight", "straight", "straight", "straight"), 1, 10);
        try {
            var one = service.create(1, request);
            service.create(1, request);
            var busy = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.create(1, request));
            assertEquals(429, busy.getStatusCode().value());
            var pending = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.replay(1, one.id()));
            assertEquals(409, pending.getStatusCode().value());
        } finally {
            release.countDown();
            service.stop();
        }
    }

    @Test
    void workerFailureIsReportedAsFailedTask() throws Exception {
        var runner = mock(PythonMatchRunner.class);
        when(runner.run(any())).thenThrow(new java.io.IOException("test failure"));
        var service = new TrialService(runner, mapper, mock(StrategyService.class), mock(PythonStrategyRunner.class));
        try {
            var task = service.create(1, new TrialRequest(List.of("straight", "straight", "straight", "straight"), 1, 1));
            for (int i = 0; i < 100 && !service.status(1, task.id()).status().equals("FAILED"); i++) Thread.sleep(10);
            assertEquals("FAILED", service.status(1, task.id()).status());
            assertFalse(service.status(1, task.id()).error().contains("test failure"));
        } finally { service.stop(); }
    }
}
