package mozhi.parseltongue.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "用户名或邮箱不能为空")
        @Size(max = 254, message = "用户名或邮箱过长")
        String identifier,

        @NotBlank(message = "密码不能为空")
        @Size(max = 72, message = "密码过长")
        String password
) {
}
