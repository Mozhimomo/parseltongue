package mozhi.parseltongue.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class User {

    private Long id;
    private String username;
    private String email;
    private String passwordHash;
    private UserRole role;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
