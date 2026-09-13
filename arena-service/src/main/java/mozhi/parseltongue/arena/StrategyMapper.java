package mozhi.parseltongue.arena;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Parameterized SQL and result mappings for the strategy library. */
@Mapper
public interface StrategyMapper {
    int lockAdmission();
    long countGenerating();
    long countGeneratingByOwner(@Param("owner") long owner);
    long countRecentByOwner(@Param("owner") long owner, @Param("since") Instant since);
    long countById(@Param("id") String id);
    void insert(@Param("snake") StrategyService.StoredSnake snake, @Param("updatedAt") Instant updatedAt);
    List<StrategyService.StoredSnake> list(@Param("owner") long owner);
    StrategyService.StoredSnake find(@Param("owner") long owner, @Param("id") String id);
    StrategyRepository.Version version(@Param("owner") long owner, @Param("id") String id);
    String lockStatus(@Param("id") String id);
    void insertVersion(@Param("id") String id, @Param("source") String source, @Param("sha256") String sha256,
                       @Param("validation") String validation, @Param("model") String model, @Param("createdAt") Instant createdAt);
    void markReady(@Param("id") String id, @Param("updatedAt") Instant updatedAt);
    void saveArtifact(@Param("id") String id, @Param("kind") String kind, @Param("json") String json,
                      @Param("updatedAt") Instant updatedAt);
    String artifact(@Param("id") String id, @Param("kind") String kind);
    void fail(@Param("id") String id, @Param("error") String error, @Param("updatedAt") Instant updatedAt);
    int recoverInterrupted(@Param("error") String error, @Param("updatedAt") Instant updatedAt);
    void diagnostic(@Param("id") String id, @Param("attempt") int attempt, @Param("stage") String stage,
                    @Param("code") String code, @Param("detail") String detail, @Param("createdAt") Instant createdAt);
    List<Map<String, Object>> diagnostics(@Param("id") String id);
}
