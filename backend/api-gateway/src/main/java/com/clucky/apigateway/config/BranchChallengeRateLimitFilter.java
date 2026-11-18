package com.clucky.apigateway.config;

import com.clucky.apigateway.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Caller-level protection for the two public branch OTP endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BranchChallengeRateLimitFilter implements WebFilter {
    private static final RedisScript<Long> COUNTER = RedisScript.of(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end; return count",
            Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;

    public BranchChallengeRateLimitFilter(ReactiveStringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) return chain.filter(exchange);
        String path = exchange.getRequest().getPath().value();
        boolean challenge = "/api/auth/branch/challenge".equals(path);
        boolean finish = "/api/auth/branch/finish".equals(path);
        if (!challenge && !finish) return chain.filter(exchange);

        // Use the actual connected peer; a caller-controlled forwarded-for header
        // must not let an attacker rotate limiter identities at will.
        var address = exchange.getRequest().getRemoteAddress();
        String caller = address == null || address.getAddress() == null
                ? "unknown" : address.getAddress().getHostAddress();
        String key = "rate-limit:branch-http:" + (challenge ? "issue:" : "finish:") + caller;
        long max = challenge ? 30 : 60;
        return redis.execute(COUNTER, List.of(key), List.of(String.valueOf(Duration.ofMinutes(1).toMillis())))
                .next()
                .onErrorReturn(Long.MAX_VALUE)
                .defaultIfEmpty(Long.MAX_VALUE)
                .flatMap(attempts -> attempts == Long.MAX_VALUE
                        ? reject(exchange, HttpStatus.SERVICE_UNAVAILABLE)
                        : attempts > max ? reject(exchange, HttpStatus.TOO_MANY_REQUESTS) : chain.filter(exchange));
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getAttributes().put(CorrelationIdFilter.ERROR_CODE,
                status == HttpStatus.TOO_MANY_REQUESTS ? "TOO_MANY_REQUESTS" : "DEPENDENCY_UNAVAILABLE");
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] payload = mapper.writeValueAsBytes(ApiError.of(status, exchange.getRequest().getPath().value()));
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        } catch (Exception failure) {
            return Mono.error(failure);
        }
    }
}
