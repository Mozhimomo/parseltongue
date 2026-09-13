package mozhi.parseltongue.arena;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StrategyRepositoryTests {
    @org.junit.jupiter.api.AfterAll static void close() { TestStrategyDatabase.closeAll(); }
    private static final String CODE="def decide(o): return o['self']['direction']\n";
    private static StrategyController.Request request() { return new StrategyController.Request("持久蛇🐍", "避开对手，饥饿时找食物"); }

    @Test void metadataSourceAndArtifactsSurviveNewRepositoryAndRemainPrivate() {
        var database=TestStrategyDatabase.create();
        var store=database.repository();
        var snake=store.create(12,request());
        store.saveArtifact(snake.id(),"security","{\"decision\":\"ALLOW\"}");
        store.saveArtifact(snake.id(),"intent","{\"summary\":\"避碰\"}");
        store.complete(snake.id(),CODE,"{\"accepted\":true}","high-capability");
        var reopened=TestStrategyDatabase.repository(database.source());
        assertEquals("READY",reopened.find(12,snake.id()).status());
        assertEquals(request().name(),reopened.list(12).get(0).name());
        assertTrue(reopened.list(13).isEmpty());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->reopened.find(13,snake.id())).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->reopened.version(13,snake.id()));
        assertEquals(CODE,reopened.version(12,snake.id()).source());
        assertEquals(StrategyRepository.sha256(CODE),reopened.version(12,snake.id()).sha256());
        assertNotNull(reopened.artifact(snake.id(),"intent"));
        assertNotNull(reopened.artifact(snake.id(),"security"));
    }

    @Test void sourceAndReadyStateCommitAtomicallyAndPublishedVersionCannotBeOverwritten() {
        var database=TestStrategyDatabase.create();var store=database.repository();var snake=store.create(1,request());
        // Failure inside the transaction after entry must not publish a broken snake.
        assertThrows(RuntimeException.class,()->store.complete(snake.id(),CODE,"{}",null));
        assertEquals("GENERATING",store.find(1,snake.id()).status());
        assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM snake_strategy_versions",Integer.class));
        // Reject the second write, after source insertion, to verify both mapper calls share one transaction.
        database.jdbc().execute("ALTER TABLE snake_strategies ADD CONSTRAINT test_reject_ready CHECK (status <> 'READY')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->store.complete(snake.id(),CODE,"{}","high-capability"));
        assertEquals("GENERATING",store.find(1,snake.id()).status());
        assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM snake_strategy_versions",Integer.class));
        database.jdbc().execute("ALTER TABLE snake_strategies DROP CONSTRAINT test_reject_ready");
        store.complete(snake.id(),CODE,"{}","high-capability");
        assertThrows(IllegalStateException.class,()->store.complete(snake.id(),CODE+"#changed","{}","high-capability"));
        store.fail(snake.id(),"late failure");
        assertEquals("READY",store.find(1,snake.id()).status());
        assertEquals(CODE,store.version(1,snake.id()).source());
    }

    @Test void databaseAdmissionLockEnforcesOwnerLimitAcrossConcurrentConnections() throws Exception {
        var database=TestStrategyDatabase.create();
        var other=TestStrategyDatabase.repository(database.source());
        var threads=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var tasks=List.of(database.repository(),other).stream().map(store->threads.submit(()->{
                start.await();try {store.create(1,request());return true;}catch(ResponseStatusException error){assertEquals(429,error.getStatusCode().value());return false;}
            })).toList();
            start.countDown();int accepted=0;for(var future:tasks)if(future.get(5,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);
        } finally {threads.shutdownNow();}
    }

    @Test void databaseAdmissionLockEnforcesGlobalLimitAcrossConcurrentConnections() throws Exception {
        var database=TestStrategyDatabase.create();
        for (int owner=1;owner<=7;owner++) database.repository().create(owner,request());
        var other=TestStrategyDatabase.repository(database.source());
        var threads=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var tasks=List.of(database.repository(),other).stream().map(store->threads.submit(()->{
                start.await();
                try {store.create(store==other?9:8,request());return true;}
                catch(ResponseStatusException error){assertEquals(429,error.getStatusCode().value());return false;}
            })).toList();
            start.countDown();int accepted=0;for(var future:tasks)if(future.get(5,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);
            assertEquals(8,database.jdbc().queryForObject("SELECT COUNT(*) FROM snake_strategies WHERE status='GENERATING'",Integer.class));
        } finally {threads.shutdownNow();}
    }

    @Test void rateLimitIncludesFailedTasksAcrossRepositoriesAndExpiresOutsideWindow() {
        var database=TestStrategyDatabase.create();var store=database.repository();
        for (int attempt=0;attempt<10;attempt++) {
            var snake=store.create(1,request());store.fail(snake.id(),"fixture failure");
        }
        var reopened=TestStrategyDatabase.repository(database.source());
        assertEquals(429,assertThrows(ResponseStatusException.class,()->reopened.create(1,request())).getStatusCode().value());
        assertEquals("GENERATING",reopened.create(2,request()).status());
        database.jdbc().update("UPDATE snake_strategies SET created_at=? WHERE owner_id=1",
                java.sql.Timestamp.from(Instant.now().minusSeconds(601)));
        assertEquals("GENERATING",reopened.create(1,request()).status());
    }

    @Test void recoveryPreservesReadySnakesAndFailsOnlyIncompleteTasks() {
        var store=TestStrategyDatabase.create().repository();
        var ready=store.create(1,request());store.complete(ready.id(),CODE,"{}","high-capability");
        var pending=store.create(2,request());
        assertEquals(1,store.recoverInterrupted());
        assertEquals("FAILED",store.find(2,pending.id()).status());
        assertEquals("READY",store.find(1,ready.id()).status());
        assertEquals(CODE,store.version(1,ready.id()).source());
    }

    @Test void legacyImportIsIdempotentKeepsFilesAndHandlesMissingSource() throws Exception {
        var mapper=new ObjectMapper();var store=TestStrategyDatabase.create().repository();
        var directory=Files.createTempDirectory("legacy-library-");String id=UUID.randomUUID().toString();
        var legacy=new StrategyService.StoredSnake(id,8,"旧蛇","稳一点","READY",null,Instant.now());
        Files.writeString(directory.resolve(id+".json"),mapper.writeValueAsString(legacy));
        Files.writeString(directory.resolve(id+".py"),CODE);
        Files.writeString(directory.resolve(id+".diagnostic"),mapper.writeValueAsString(java.util.Map.of("attempt",1,"stage","validation","code","FIXTURE","detail","imported","time",Instant.now().toString())));
        assertEquals(1,LegacyStrategyImporter.run(directory,mapper,store));
        Files.writeString(directory.resolve(id+".py"),CODE+"# must not overwrite DB");
        assertEquals(0,LegacyStrategyImporter.run(directory,mapper,store));
        assertEquals(CODE,store.version(8,id).source());assertEquals(1,store.diagnostics(id).size());
        assertTrue(Files.exists(directory.resolve(id+".json")));
        String missing=UUID.randomUUID().toString();
        Files.writeString(directory.resolve(missing+".json"),mapper.writeValueAsString(new StrategyService.StoredSnake(missing,8,"缺源码","稳一点","READY",null,Instant.now())));
        assertEquals(1,LegacyStrategyImporter.run(directory,mapper,store));
        assertEquals("FAILED",store.find(8,missing).status());
    }

    @Test void sourceCorruptionFailsClosedBeforeMatchExecution() {
        var database=TestStrategyDatabase.create();var store=database.repository();var snake=store.create(1,request());
        store.complete(snake.id(),CODE,"{}","high-capability");
        database.jdbc().update("UPDATE snake_strategy_versions SET source_code=? WHERE strategy_id=?",CODE+"#tampered",snake.id());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->store.version(1,snake.id())).getStatusCode().value());
    }
}
