package mozhi.parseltongue.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 32, message = "用户名过长")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 72, message = "密码过长")
        String password
) {
}
