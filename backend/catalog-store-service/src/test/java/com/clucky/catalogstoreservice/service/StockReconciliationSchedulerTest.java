package com.clucky.catalogstoreservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockReconciliationSchedulerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrderStockService orderStockService = mock(OrderStockService.class);
    private final OrderServiceClient orderServiceClient = mock(OrderServiceClient.class);
    private final StockReconciliationScheduler scheduler = new StockReconciliationScheduler(
            jdbc, orderStockService, orderServiceClient, true, 10);

    @Test
    void reconcilesOrphanedStockWhenOrderNotFound() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("order_id", 100L, "store_id", 2L)
        ));
        when(orderServiceClient.getOrderStatus(100L)).thenReturn(Optional.empty());

        scheduler.reconcileStaleStockReservations();

        verify(orderStockService).releaseOrderStock(100L, 2L, "reconciliation-404");
    }

    @Test
    void reconcilesAbandonedStockWhenOrderIsCancelled() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("order_id", 101L, "store_id", 2L)
        ));
        when(orderServiceClient.getOrderStatus(101L)).thenReturn(Optional.of(
                new OrderServiceClient.RemoteOrderStatus(101L, "CLU-101", "CANCELLED", 2L, Instant.now())
        ));

        scheduler.reconcileStaleStockReservations();

        verify(orderStockService).releaseOrderStock(101L, 2L, "reconciliation-cancelled");
    }

    @Test
    void waitsForCartCancellationWhenOrderIsStalePendingStock() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("order_id", 102L, "store_id", 2L)
        ));
        when(orderServiceClient.getOrderStatus(102L)).thenReturn(Optional.of(
                new OrderServiceClient.RemoteOrderStatus(102L, "CLU-102", "PENDING_STOCK", 2L, Instant.now())
        ));

        scheduler.reconcileStaleStockReservations();

        verify(orderStockService, never()).releaseOrderStock(anyLong(), anyLong(), anyString());
        verify(jdbc).update(startsWith("UPDATE catalog_service.order_stock_ledger SET updated_at=now()"), eq(10), eq(102L));
        verify(jdbc, never()).update(startsWith("UPDATE catalog_service.order_stock_ledger SET event_id="), any(), any());
    }

    @Test
    void updatesLedgerWithoutReleasingWhenOrderIsConfirmed() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("order_id", 103L, "store_id", 2L)
        ));
        when(orderServiceClient.getOrderStatus(103L)).thenReturn(Optional.of(
                new OrderServiceClient.RemoteOrderStatus(103L, "CLU-103", "CONFIRMED", 2L, Instant.now())
        ));

        scheduler.reconcileStaleStockReservations();

        verify(orderStockService, never()).releaseOrderStock(anyLong(), anyLong(), anyString());
        verify(jdbc).update(startsWith("UPDATE catalog_service.order_stock_ledger"), eq("reconciled-confirmed-103"), eq(103L));
    }
}
