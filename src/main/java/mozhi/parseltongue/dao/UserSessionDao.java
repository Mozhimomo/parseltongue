package mozhi.parseltongue.dao;

import mozhi.parseltongue.entity.User;
import mozhi.parseltongue.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface UserSessionDao {

    int insert(UserSession session);

    UserSession findByRefreshTokenHash(@Param("refreshTokenHash") String refreshTokenHash);

    User findAuthenticatedUser(
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("now") Instant now
    );

    int rotateRefreshToken(
            @Param("id") String id,
            @Param("oldHash") String oldHash,
            @Param("newHash") String newHash,
            @Param("usedAt") Instant usedAt
    );

    int revoke(
            @Param("id") String id,
            @Param("userId") Long userId,
            @Param("revokedAt") Instant revokedAt
    );

    int revokeAllByUserId(
            @Param("userId") Long userId,
            @Param("revokedAt") Instant revokedAt
    );
}
