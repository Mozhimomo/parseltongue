package mozhi.parseltongue.dto;

import mozhi.parseltongue.entity.User;
import mozhi.parseltongue.entity.UserRole;

public record UserDTO(
        Long id,
        String username,
        UserRole role
) {
    public static UserDTO from(User user) {
        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getRole()
        );
    }
}
