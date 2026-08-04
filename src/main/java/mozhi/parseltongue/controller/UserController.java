package mozhi.parseltongue.controller;

import mozhi.parseltongue.dto.UserDTO;
import mozhi.parseltongue.dto.response.ApiResponse;
import mozhi.parseltongue.filter.JwtAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/me")
    public ApiResponse<UserDTO> me(
            @RequestAttribute(JwtAuthenticationFilter.CURRENT_USER_ATTRIBUTE) UserDTO user
    ) {
        return ApiResponse.success(user);
    }
}
