package com.clucky.apigateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class BrowserSessionConfigTest {
    @Test
    void forwardsAdminAccessCookieAsBearerForProtectedSessionLimitMutation() {
        BrowserSessionConfig config = new BrowserSessionConfig(
                List.of("http://localhost:3000"), List.of("http://localhost:3001"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.patch("/api/auth/sessions/limit")
                .cookie(new HttpCookie("clucky_admin_access", "admin-access-token"))
                .header("Origin", "http://localhost:3001")
                .header("X-Clucky-Client", "admin")
                .header("Authorization", "Bearer browser-supplied-token"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        config.browserSessionFilter(new ObjectMapper()).filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();

        assertEquals("Bearer admin-access-token",
                forwarded.get().getRequest().getHeaders().getFirst("Authorization"));
    }

    @Test
    void keepsPublicAuthPostEndpointsCookieOnlyEvenWhenAnAccessCookieExists() {
        BrowserSessionConfig config = new BrowserSessionConfig(
                List.of("http://localhost:3000"), List.of("http://localhost:3001"));
        List<String> publicAuthPaths = List.of(
                "/api/auth/register", "/api/auth/resend-otp", "/api/auth/verify-otp",
                "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                "/api/auth/forgot-password", "/api/auth/reset-password",
                "/api/auth/google", "/api/auth/google/complete",
                "/api/auth/branch/challenge", "/api/auth/branch/finish");

        for (String path : publicAuthPaths) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                    .cookie(new HttpCookie("clucky_admin_access", "stale-access-token"))
                    .header("Origin", "http://localhost:3001")
                    .header("X-Clucky-Client", "admin")
                    .header("Authorization", "Bearer browser-supplied-token"));
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

            config.browserSessionFilter(new ObjectMapper()).filter(exchange, next -> {
                forwarded.set(next);
                return Mono.empty();
            }).block();

            assertNull(forwarded.get().getRequest().getHeaders().getFirst("Authorization"), path);
            assertEquals(
                    "clucky_admin_access=stale-access-token",
                    forwarded.get().getRequest().getHeaders().getFirst("Cookie"), path);
        }
    }

    @Test
    void removesBrowserSuppliedAuthorizationWhenProtectedSessionMutationHasNoAccessCookie() {
        BrowserSessionConfig config = new BrowserSessionConfig(
                List.of("http://localhost:3000"), List.of("http://localhost:3001"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.patch("/api/auth/sessions/limit")
                .header("Origin", "http://localhost:3001")
                .header("X-Clucky-Client", "admin")
                .header("Authorization", "Bearer browser-supplied-token"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        config.browserSessionFilter(new ObjectMapper()).filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();

        assertNull(forwarded.get().getRequest().getHeaders().getFirst("Authorization"));
    }

    @Test
    void overwritesBrowserSuppliedClientIpWithConnectedPeerAddress() {
        BrowserSessionConfig config = new BrowserSessionConfig(
                List.of("http://localhost:3000"), List.of("http://localhost:3001"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress("203.0.113.8", 12345))
                .header("Origin", "http://localhost:3001")
                .header("X-Clucky-Client", "admin")
                .header("X-Real-IP", "192.0.2.11")
                .header("X-Clucky-Client-IP", "198.51.100.4"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        config.browserSessionFilter(new ObjectMapper()).filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();

        assertEquals("192.0.2.11", forwarded.get().getRequest().getHeaders().getFirst("X-Clucky-Client-IP"));
    }
}
