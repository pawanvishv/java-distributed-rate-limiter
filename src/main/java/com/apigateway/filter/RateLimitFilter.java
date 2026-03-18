package com.apigateway.filter;

import com.apigateway.service.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimiterService rateLimiterService;

    // Runs SECOND (after logging, before auth)
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Use userId if authenticated, otherwise fallback to IP
        String userId    = request.getHeaders().getFirst("X-User-ID");
        String ipAddress = getClientIp(request);
        String identifier = (userId != null) ? "user:" + userId : "ip:" + ipAddress;

        // Authenticated users get 500 req/min, anonymous get 60 req/min
        int limit    = (userId != null) ? 500 : 60;
        int duration = 60;

        return rateLimiterService.isAllowed(identifier, limit, duration)
                .flatMap(result -> {
                    ServerHttpResponse response = exchange.getResponse();

                    // Always set rate limit headers
                    response.getHeaders().add("X-RateLimit-Limit",
                            String.valueOf(result.limitMax()));
                    response.getHeaders().add("X-RateLimit-Remaining",
                            String.valueOf(result.remainingRequests()));
                    response.getHeaders().add("X-RateLimit-Window",
                            result.windowSeconds() + "s");

                    if (!result.allowed()) {
                        log.warn("Rate limit exceeded for: {}", identifier);
                        response.getHeaders().add("Retry-After",
                                String.valueOf(result.retryAfterSeconds()));
                        return writeErrorResponse(exchange,
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Rate limit exceeded. Try again in "
                                        + result.retryAfterSeconds() + " seconds.");
                    }

                    return chain.filter(exchange);
                });
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange,
                                          HttpStatus status,
                                          String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                    "success": false,
                    "status": %d,
                    "message": "%s"
                }
                """.formatted(status.value(), message);

        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }

    private String getClientIp(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }
}