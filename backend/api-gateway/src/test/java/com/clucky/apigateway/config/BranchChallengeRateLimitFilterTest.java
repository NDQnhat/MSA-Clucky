package com.clucky.apigateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchChallengeRateLimitFilterTest {
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final BranchChallengeRateLimitFilter filter =
            new BranchChallengeRateLimitFilter(redis, new ObjectMapper().findAndRegisterModules());

    @Test
    void blocksPublicFinishFloodAtGatewayRegardlessOfForwardedHeader() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(61L));
        var request = MockServerHttpRequest.post("/api/auth/branch/finish")
                .remoteAddress(new InetSocketAddress("203.0.113.6", 1234))
                .header("X-Forwarded-For", "192.0.2.9").build();
        var exchange = MockServerWebExchange.from(request);
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertFalse(forwarded.get());
        verify(redis).execute(any(RedisScript.class), eq(java.util.List.of("rate-limit:branch-http:finish:203.0.113.6")), anyList());
    }

    @Test
    void allowsModerateFinishTrafficAndUnrelatedRoutes() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(60L));
        AtomicBoolean forwarded = new AtomicBoolean();
        var finish = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/branch/finish").build());
        filter.filter(finish, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        assertTrue(forwarded.get());
        var login = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build());
        filter.filter(login, ignored -> Mono.empty()).block();
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    void deniesPublicOtpWhenRateLimitStoreIsUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("redis unavailable")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/branch/challenge").build());
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not forward"))).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    }
}
