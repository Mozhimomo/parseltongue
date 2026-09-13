package mozhi.parseltongue.llm;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalRequestFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 128 * 1024;
    private final byte[] expectedAuthorization;

    public InternalRequestFilter(LlmProperties properties) {
        expectedAuthorization = ("Bearer " + properties.gatewayToken()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute("llmRequestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        if ("GET".equals(request.getMethod()) && "/actuator/health".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !MessageDigest.isEqual(expectedAuthorization, authorization.getBytes(StandardCharsets.UTF_8))) {
            reject(response, 401, "无效的内部网关凭据");
            return;
        }
        if ("GET".equals(request.getMethod()) && "/internal/llm/models".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (!"POST".equals(request.getMethod()) || !"/internal/llm/generations".equals(request.getRequestURI())) {
            reject(response, 403, "不允许访问该内部接口");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response, 413, "请求体超出限制");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, 413, "请求体超出限制");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":" + status * 100 + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
