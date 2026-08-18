package mozhi.parseltongue.gateway.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final GatewayErrorWriter errorWriter;

    public GatewayErrorWebExceptionHandler(GatewayErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exception instanceof ResponseStatusException statusException) {
            int status = statusException.getStatusCode().value();
            String message = statusException.getReason() == null
                    ? statusException.getStatusCode().toString()
                    : statusException.getReason();
            return errorWriter.write(exchange, status, status * 100, message);
        }
        if (hasCause(exception, TimeoutException.class)) {
            return errorWriter.write(exchange, 504, 50400, "下游服务响应超时");
        }
        if (hasCause(exception, ConnectException.class)
                || hasCause(exception, UnknownHostException.class)) {
            return errorWriter.write(exchange, 503, 50300, "下游服务暂时不可用");
        }

        log.error("Unhandled gateway exception", exception);
        return errorWriter.write(exchange, 500, 50000, "网关内部错误");
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
