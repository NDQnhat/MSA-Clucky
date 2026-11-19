package com.clucky.cartorderservice.service;

import com.clucky.cartorderservice.dto.DomainEvent;
import com.clucky.cartorderservice.entity.Order;
import com.clucky.cartorderservice.mapper.DomainMapper;
import com.clucky.cartorderservice.repository.OrderItemRepository;
import com.clucky.cartorderservice.repository.OrderRepository;
import com.clucky.cartorderservice.repository.OrderStatusHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShippingOrderProjectionTest {
    private final Order order = new Order();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository items = mock(OrderItemRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrderEventPublisher publisher = mock(OrderEventPublisher.class);
    private final ShippingOrderProjection projection = new ShippingOrderProjection(
            orders, items, mock(OrderStatusHistoryRepository.class), jdbc, publisher);

    @BeforeEach
    void setUp() {
        order.setId(42L);
        order.setStoreId(2L);
        order.setType("DELIVERY");
        order.setStatus("READY");
        order.setPaymentMethod("COD");
        order.setPaymentStatus("PENDING");
        order.setTotalAmount(BigDecimal.valueOf(45_000));
        when(orders.lockById(42L)).thenReturn(Optional.of(order));
        when(items.findByOrderIdOrderByIdAsc(42L)).thenReturn(List.of());
        when(jdbc.update(anyString(), anyString(), eq(42L), eq(1), anyString())).thenReturn(1);
    }

    @Test
    void riderAdvanceAppearsAtPickupAndSurvivesDeliveryWithoutRealCashSettlement() {
        projection.apply(event("shipping.started", null, true));

        assertEquals("SHIPPING", order.getStatus());
        assertEquals("PAID_SIMULATED", DomainMapper.toResponse(order).merchantPaymentStatus());
        assertEquals("PENDING", order.getPaymentStatus());

        projection.apply(event("shipping.delivered", null, true));

        assertEquals("COMPLETED", order.getStatus());
        assertEquals("PAID_SIMULATED", DomainMapper.toResponse(order).merchantPaymentStatus());
        assertEquals("PENDING", order.getPaymentStatus());
        verify(publisher).publishSimulatedDeliveryCompleted(order, List.of(), false);
    }

    @Test
    void returnClearsAdvanceAndLatePickupCannotReinstateIt() {
        projection.apply(event("shipping.started", null, true));
        projection.apply(event("shipping.resolved", "RETURNED", false));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(42L), eq(1))).thenReturn(true);

        projection.apply(event("shipping.started", null, true));

        assertEquals("SHIPPING", order.getStatus());
        assertEquals("ADVANCE_RETURNED", DomainMapper.toResponse(order).merchantPaymentStatus());
        assertEquals("PENDING", order.getPaymentStatus());
    }

    private DomainEvent event(String type, String resolution, boolean advance) {
        var payload = new ObjectMapper().createObjectNode()
                .put("source", "SIMULATOR").put("simulationOnly", true)
                .put("orderId", 42L).put("storeId", 2L).put("attemptNumber", 1)
                .put("simulatedAdvanceConfirmed", advance).put("proofRecorded", type.equals("shipping.delivered"));
        if (resolution != null) payload.put("resolution", resolution).put("merchantPaymentStatus", "ADVANCE_RETURNED");
        return new DomainEvent(java.util.UUID.randomUUID().toString(), "shipping-42", type, "shipping-service", Instant.EPOCH, payload);
    }
}
