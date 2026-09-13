package mozhi.parseltongue.arena;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GameWebConfig implements WebMvcConfigurer {
    private final SessionVerifier sessions;

    public GameWebConfig(SessionVerifier sessions) { this.sessions = sessions; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                request.setAttribute("gameUserId", sessions.verify(request.getHeader("Authorization")));
                return true;
            }
        }).addPathPatterns("/api/game/**");
    }
}
