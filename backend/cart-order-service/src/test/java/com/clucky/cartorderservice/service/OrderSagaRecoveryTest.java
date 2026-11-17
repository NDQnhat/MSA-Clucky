package com.clucky.cartorderservice.service;

import com.clucky.cartorderservice.cart.CartDto;
import com.clucky.cartorderservice.cart.CartItemDto;
import com.clucky.cartorderservice.cart.CartService;
import com.clucky.cartorderservice.config.AccessGuard;
import com.clucky.cartorderservice.dto.CreateOrderRequest;
import com.clucky.cartorderservice.dto.OrderResponse;
import com.clucky.cartorderservice.entity.Order;
import com.clucky.cartorderservice.entity.OrderItem;
import com.clucky.cartorderservice.exception.ApiException;
import com.clucky.cartorderservice.exception.ErrorCode;
import com.clucky.cartorderservice.repository.OrderItemRepository;
import com.clucky.cartorderservice.repository.OrderRepository;
import com.clucky.cartorderservice.repository.OrderStatusHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderSagaRecoveryTest {
    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository histories = mock(OrderStatusHistoryRepository.class);
    private final CartService carts = mock(CartService.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final PromotionClient promotion = mock(PromotionClient.class);
    private final ShippingClient shipping = mock(ShippingClient.class);
    private final LoyaltyClient loyalty = mock(LoyaltyClient.class);
    private final VoucherCheckoutRecovery voucherRecovery = mock(VoucherCheckoutRecovery.class);
    private final CustomerProfileClient profiles = mock(CustomerProfileClient.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final MemoryOperationsJdbc jdbc = new MemoryOperationsJdbc();
    private final Map<Long, Order> persistedOrders = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(10);
    private OrderService service;

    private static final CreateOrderRequest REQUEST = new CreateOrderRequest(
            1L, "PICKUP", "Customer", "0900000000", null, null, "COD",
            null, 0, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        CartItemDto cartItem = new CartItemDto(1L, null, "Chicken", 1,
                BigDecimal.valueOf(100), List.of(), List.of(), null, 1L);
        when(carts.getCart(anyString())).thenReturn(CartDto.of(List.of(cartItem)));
        var validatedItem = new CatalogClient.ValidatedCatalogItem(
                1L, null, "Chicken", BigDecimal.valueOf(100), BigDecimal.ZERO,
                1, BigDecimal.valueOf(100), true, null, List.of(), List.of());
        when(catalog.validateItems(anyLong(), anyList())).thenReturn(
                new CatalogClient.CatalogValidationResponse(true, true, null,
                        BigDecimal.valueOf(100), List.of(validatedItem), Map.of(1L, 1L)));
        when(shipping.calculateShippingFee(anyString(), anyLong(), any(), any())).thenReturn(
                new ShippingClient.ShippingFeeResult(true, BigDecimal.ZERO, "PICKUP", null, null, null));
        when(promotion.validateVoucher(any(), any(), anyLong(), any())).thenReturn(
                new PromotionClient.VoucherResult(false, null, BigDecimal.ZERO, null, null, null));
        var quote = new LoyaltyClient.Quote(1, 0, 0, BigDecimal.ZERO, 0, "MEMBER", BigDecimal.ZERO);
        when(loyalty.preview(any(), any())).thenReturn(quote);
        when(loyalty.reserveWithReceipt(anyLong(), anyLong(), any(), any())).thenReturn(quote);
        when(repository.save(any(Order.class))).thenAnswer(call -> {
            Order order = call.getArgument(0);
            if (order.getId() == null) order.setId(nextId.getAndIncrement());
            persistedOrders.put(order.getId(), order);
            return order;
        });
        when(repository.findById(anyLong())).thenAnswer(call ->
                Optional.ofNullable(persistedOrders.get(call.getArgument(0))));
        when(repository.lockById(anyLong())).thenAnswer(call ->
                Optional.ofNullable(persistedOrders.get(call.getArgument(0))));
        when(orderItems.save(any(OrderItem.class))).thenAnswer(call -> {
            OrderItem item = call.getArgument(0);
            item.setId(100L);
            return item;
        });
        service = new OrderService(repository, orderItems, histories, carts, new AccessGuard(),
                new ObjectMapper(), events, catalog, promotion, shipping, loyalty, null,
                voucherRecovery, profiles, jdbc);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private OrderResponse checkout(String key) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256")
                .subject("1").claim("role", "CUSTOMER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try {
            return service.createOrder(REQUEST, key);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void failedPreDraftValidationDoesNotClaimKeyAndCanBeRetried() {
        var valid = new CatalogClient.CatalogValidationResponse(true, true, null,
                BigDecimal.valueOf(100),
                List.of(new CatalogClient.ValidatedCatalogItem(1L, null, "Chicken",
                        BigDecimal.valueOf(100), BigDecimal.ZERO, 1, BigDecimal.valueOf(100),
                        true, null, List.of(), List.of())),
                Map.of(1L, 1L));
        doThrow(new ApiException(ErrorCode.RESOURCE_CONFLICT)).doReturn(valid)
                .when(catalog).validateItems(anyLong(), anyList());

        assertEquals(ErrorCode.RESOURCE_CONFLICT,
                assertThrows(ApiException.class, () -> checkout("same-key")).getCode());
        assertTrue(jdbc.operations.isEmpty(), "Failed validation must leave no idempotency claim");
        verify(repository, never()).save(any(Order.class));

        OrderResponse result = checkout("same-key");
        assertEquals("CONFIRMED", result.status());
        assertEquals(1, jdbc.operations.size());
    }

    @Test
    void overlappingSameKeyRejectsPendingDraftThenReplaysCompletedOrder() throws Exception {
        CountDownLatch stockEntered = new CountDownLatch(1);
        CountDownLatch allowStock = new CountDownLatch(1);
        doAnswer(call -> {
            stockEntered.countDown();
            assertTrue(allowStock.await(5, TimeUnit.SECONDS));
            return null;
        }).when(catalog).reserveStock(anyLong(), anyLong(), anyMap(), anyMap());

        CompletableFuture<OrderResponse> first = CompletableFuture.supplyAsync(() -> checkout("shared-key"));
        try {
            assertTrue(stockEntered.await(5, TimeUnit.SECONDS), "First request must have committed its draft");
            ApiException conflict = assertThrows(ApiException.class, () -> checkout("shared-key"));
            assertEquals(ErrorCode.RESOURCE_CONFLICT, conflict.getCode());
        } finally {
            allowStock.countDown();
        }

        OrderResponse created = first.get(5, TimeUnit.SECONDS);
        assertEquals("CONFIRMED", created.status());
        OrderResponse replay = checkout("shared-key");
        assertEquals(created.id(), replay.id());
        assertEquals("CONFIRMED", replay.status());
        verify(catalog, times(1)).reserveStock(anyLong(), anyLong(), anyMap(), anyMap());
    }

    @Test
    void stockFailureCancelsDraftReleasesKeyAndAllowsRetry() {
        doThrow(new ApiException(ErrorCode.RESOURCE_CONFLICT))
                .doNothing().when(catalog).reserveStock(anyLong(), anyLong(), anyMap(), anyMap());

        assertEquals(ErrorCode.RESOURCE_CONFLICT,
                assertThrows(ApiException.class, () -> checkout("retry-key")).getCode());
        assertEquals("CANCELLED", persistedOrders.get(10L).getStatus());
        assertTrue(jdbc.operations.isEmpty(), "Failed draft must not keep its idempotency key");
        verify(events).publishOrderCancelled(any(Order.class), anyList(), anyString());

        OrderResponse retried = checkout("retry-key");
        assertEquals(11L, retried.id());
        assertEquals("CONFIRMED", retried.status());
    }

    @Test
    void staleDraftCancellationLocksRowAndEmitsOutboxOnce() {
        Order abandoned = new Order();
        abandoned.setId(90L);
        abandoned.setStatus("PENDING_STOCK");
        abandoned.setStoreId(1L);
        abandoned.setCreatedAt(Instant.now().minus(Duration.ofMinutes(20)));
        persistedOrders.put(90L, abandoned);
        jdbc.operations.put("W:1:abandoned", new MemoryOperationsJdbc.Attempt("hash", 90L));

        assertTrue(service.cancelStalePendingStock(90L, Duration.ofMinutes(15)));
        assertEquals("CANCELLED", abandoned.getStatus());
        assertFalse(service.cancelStalePendingStock(90L, Duration.ofMinutes(15)));
        assertTrue(jdbc.operations.isEmpty());
        verify(repository, times(2)).lockById(90L);
        verify(events, times(1)).publishOrderCancelled(eq(abandoned), eq(List.of()), anyString());
        verify(histories, times(1)).save(argThat(history ->
                "PENDING_STOCK".equals(history.getFromStatus())
                        && "CANCELLED".equals(history.getToStatus())));
    }

    /**
     * Mimics the two operation tables' unique claims and committed order links.
     * The real implementation uses PostgreSQL ON CONFLICT inside the draft transaction.
     */
    static final class MemoryOperationsJdbc extends JdbcTemplate {
        private final Map<String, Attempt> operations = new ConcurrentHashMap<>();

        static final class Attempt {
            final String hash;
            volatile Long orderId;

            Attempt(String hash, Long orderId) {
                this.hash = hash;
                this.orderId = orderId;
            }
        }

        @Override
        public int update(String sql, Object... values) {
            if (sql.contains("INSERT INTO order_service.web_order_operations")) {
                String key = "W:" + values[0] + ":" + values[1];
                return operations.putIfAbsent(key, new Attempt(values[2].toString(), null)) == null ? 1 : 0;
            }
            if (sql.startsWith("UPDATE order_service.web_order_operations SET order_id=")) {
                Attempt attempt = operations.get("W:" + values[1] + ":" + values[2]);
                if (attempt == null) return 0;
                attempt.orderId = ((Number) values[0]).longValue();
                return 1;
            }
            if (sql.startsWith("DELETE FROM order_service.web_order_operations")
                    || sql.startsWith("DELETE FROM order_service.counter_order_operations")) {
                Long id = ((Number) values[0]).longValue();
                long before = operations.size();
                operations.entrySet().removeIf(entry -> id.equals(entry.getValue().orderId));
                return (int) (before - operations.size());
            }
            return 1;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... values) {
            if (!sql.contains("order_service.web_order_operations")) return List.of();
            Attempt attempt = operations.get("W:" + values[0] + ":" + values[1]);
            if (attempt == null) return List.of();
            Map<String, Object> row = new HashMap<>();
            row.put("payload_hash", attempt.hash);
            row.put("order_id", attempt.orderId);
            return List.of(row);
        }
    }
}
