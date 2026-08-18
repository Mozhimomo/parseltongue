package mozhi.parseltongue.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mozhi.parseltongue.annotation.RequireRole;
import mozhi.parseltongue.dto.UserDTO;
import mozhi.parseltongue.exception.ApiException;
import mozhi.parseltongue.filter.JwtAuthenticationFilter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        UserDTO user = (UserDTO) request.getAttribute(
                JwtAuthenticationFilter.CURRENT_USER_ATTRIBUTE
        );
        if (user == null) {
            throw ApiException.unauthorized("请先登录");
        }

        RequireRole requirement = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(),
                RequireRole.class
        );
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(),
                    RequireRole.class
            );
        }
        if (requirement != null && Arrays.stream(requirement.value())
                .noneMatch(role -> role == user.role())) {
            throw ApiException.forbidden("没有访问该接口的权限");
        }
        return true;
    }
}
