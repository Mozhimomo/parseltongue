package mozhi.parseltongue.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class UserSession {

    private String id;
    private Long userId;
    private String refreshTokenHash;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant lastUsedAt;
}
