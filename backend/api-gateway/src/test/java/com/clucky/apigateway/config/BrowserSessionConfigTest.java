package com.clucky.apigateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class BrowserSessionConfigTest {
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
