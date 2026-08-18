package mozhi.parseltongue.dto.response;

import mozhi.parseltongue.dto.UserDTO;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserDTO user
) {
}
