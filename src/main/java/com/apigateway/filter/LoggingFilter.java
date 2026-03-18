package com.apigateway.filter;

import com.apigateway.model.ApiLog;
import com.apigateway.repository.ApiLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class LoggingFilter implements GlobalFilter, Ordered {

    private final ApiLogRepository apiLogRepository;

    // Runs FIRST among all filters
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String requestId = UUID.randomUUID().toString();
        long startTime   = System.currentTimeMillis();

        // Attach requestId to headers for downstream services
        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-Request-ID", requestId)
                .header("X-Gateway-Time", String.valueOf(startTime))
                .build();

        log.info("→ REQUEST  [{} {}] id={} ip={}",
                request.getMethod(),
                request.getPath(),
                requestId,
                getClientIp(request));

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signalType -> {
                    ServerHttpResponse response = exchange.getResponse();
                    long duration = System.currentTimeMillis() - startTime;
                    int statusCode = response.getStatusCode() != null
                            ? response.getStatusCode().value() : 0;

                    log.info("← RESPONSE [{} {}] id={} status={} duration={}ms",
                            request.getMethod(),
                            request.getPath(),
                            requestId,
                            statusCode,
                            duration);

                    // Save log to PostgreSQL asynchronously
                    saveLog(requestId, request, statusCode, duration)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                });
    }

    private Mono<Void> saveLog(String requestId,
                               ServerHttpRequest request,
                               int statusCode,
                               long duration) {
        return Mono.fromRunnable(() -> {
            try {
                ApiLog apiLog = ApiLog.builder()
                        .requestId(requestId)
                        .method(request.getMethod().name())
                        .path(request.getPath().toString())
                        .statusCode(statusCode)
                        .ipAddress(getClientIp(request))
                        .durationMs(duration)
                        .createdAt(LocalDateTime.now())
                        .build();

                apiLogRepository.save(apiLog);
            } catch (Exception e) {
                log.error("Failed to save API log: {}", e.getMessage());
            }
        });
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