package com.apigateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimiterService {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Value("${rate-limit.default-limit}")
    private int defaultLimit;

    @Value("${rate-limit.default-duration}")
    private int defaultDuration;

    @Value("${rate-limit.burst-capacity}")
    private int burstCapacity;

    // ── Lua Script: Sliding Window Rate Limiter ──────────────────────
    // Atomic operation in Redis — thread-safe across all instances
    private static final String SLIDING_WINDOW_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local request_id = ARGV[4]
            
            -- Remove expired entries outside the window
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)
            
            -- Count current requests in window
            local count = redis.call('ZCARD', key)
            
            -- Check if limit exceeded
            if count >= limit then
                return {0, count, limit}
            end
            
            -- Add current request
            redis.call('ZADD', key, now, request_id)
            
            -- Set expiry on the key
            redis.call('EXPIRE', key, window)
            
            return {1, count + 1, limit}
            """;

    // ── Public Methods ───────────────────────────────────────────────

    /**
     * Check rate limit for a given key (IP or userId)
     * Returns Mono<RateLimitResult>
     */
    public Mono<RateLimitResult> isAllowed(String identifier, int limit, int durationSeconds) {
        String redisKey = "rate_limit:" + identifier;
        long now = Instant.now().toEpochMilli();
        String requestId = now + ":" + Math.random();

        RedisScript<List<Long>> script = RedisScript.of(
                SLIDING_WINDOW_SCRIPT,
                (Class<List<Long>>) (Class<?>) List.class
        );

        List<String> keys = List.of(redisKey);
        List<String> args = Arrays.asList(
                String.valueOf(now),
                String.valueOf(durationSeconds),
                String.valueOf(limit),
                requestId
        );

        return reactiveRedisTemplate
                .execute(script, keys, args)
                .next()
                .map(result -> {
                    // result = [allowed(0/1), currentCount, limit]
                    boolean allowed = result.get(0) == 1L;
                    long current = result.get(1);
                    long max = result.get(2);
                    long remaining = Math.max(0, max - current);

                    log.debug("Rate limit check - key: {}, allowed: {}, current: {}/{} ",
                            identifier, allowed, current, max);

                    return new RateLimitResult(allowed, remaining, max, durationSeconds);
                })
                .onErrorResume(ex -> {
                    // Fail open — allow request if Redis is down
                    log.error("Redis rate limit error, failing open: {}", ex.getMessage());
                    return Mono.just(new RateLimitResult(true, defaultLimit, defaultLimit, durationSeconds));
                });
    }

    /**
     * Convenience method using defaults
     */
    public Mono<RateLimitResult> isAllowed(String identifier) {
        return isAllowed(identifier, defaultLimit, defaultDuration);
    }

    /**
     * Check by IP address
     */
    public Mono<RateLimitResult> isAllowedByIp(String ipAddress, int limit, int duration) {
        return isAllowed("ip:" + ipAddress, limit, duration);
    }

    /**
     * Check by userId (authenticated users get higher limits)
     */
    public Mono<RateLimitResult> isAllowedByUser(String userId, int limit, int duration) {
        return isAllowed("user:" + userId, limit, duration);
    }

    /**
     * Get current count without incrementing
     */
    public Mono<Long> getCurrentCount(String identifier) {
        String redisKey = "rate_limit:" + identifier;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - (defaultDuration * 1000L);

        return reactiveRedisTemplate.opsForZSet()
                .removeRangeByScore(
                        redisKey,
                        org.springframework.data.domain.Range.of(
                                org.springframework.data.domain.Range.Bound.inclusive(0.0),
                                org.springframework.data.domain.Range.Bound.inclusive((double) windowStart)
                        )
                )
                .then(reactiveRedisTemplate.opsForZSet().size(redisKey))
                .defaultIfEmpty(0L);
    }

    /**
     * Reset rate limit for an identifier (admin use)
     */
    public Mono<Boolean> resetLimit(String identifier) {
        String redisKey = "rate_limit:" + identifier;
        return reactiveRedisTemplate.delete(redisKey)
                .map(count -> count > 0)
                .doOnSuccess(deleted ->
                        log.info("Rate limit reset for: {}, deleted: {}", identifier, deleted));
    }

    // ── Result Record ────────────────────────────────────────────────

    public record RateLimitResult(
            boolean allowed,
            long remainingRequests,
            long limitMax,
            int windowSeconds
    ) {
        public long retryAfterSeconds() {
            return allowed ? 0 : windowSeconds;
        }
    }
}