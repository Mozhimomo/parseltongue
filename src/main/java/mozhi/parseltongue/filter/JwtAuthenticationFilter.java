package mozhi.parseltongue.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import mozhi.parseltongue.dto.UserDTO;
import mozhi.parseltongue.dto.response.ApiResponse;
import mozhi.parseltongue.exception.ApiException;
import mozhi.parseltongue.service.AuthService;
import mozhi.parseltongue.service.JwtService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER_ATTRIBUTE = "currentUser";
    public static final String CURRENT_SESSION_ID_ATTRIBUTE = "currentSessionId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh"
    );

    private final JwtService jwtService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            AuthService authService,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            writeError(response, ApiException.unauthorized("Authorization 请求头格式不正确"));
            return;
        }

        try {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            JwtService.JwtClaims claims = jwtService.parseAccessToken(token);
            UserDTO user = authService.validateSession(claims.userId(), claims.sessionId());
            request.setAttribute(CURRENT_USER_ATTRIBUTE, user);
            request.setAttribute(CURRENT_SESSION_ID_ATTRIBUTE, claims.sessionId());
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            writeError(response, exception);
        } catch (RuntimeException exception) {
            log.error("JWT authentication failed unexpectedly", exception);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiResponse.error(50000, "服务器内部错误"));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/api/")
                || PUBLIC_PATHS.contains(path);
    }

    private void writeError(HttpServletResponse response, ApiException exception) throws IOException {
        response.setStatus(exception.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(exception.getCode(), exception.getMessage())
        );
    }
}
