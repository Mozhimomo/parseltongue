package mozhi.parseltongue.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RequestTraceGlobalFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incomingRequestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String requestId = incomingRequestId == null || incomingRequestId.isBlank()
                ? UUID.randomUUID().toString()
                : incomingRequestId;

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        String finalRequestId = requestId;
        long startedAt = System.nanoTime();
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> {
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                    log.info("gateway request id={} method={} path={} status={} durationMs={}",
                            finalRequestId,
                            request.getMethod(),
                            request.getURI().getPath(),
                            exchange.getResponse().getStatusCode(),
                            elapsedMs
                    );
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
