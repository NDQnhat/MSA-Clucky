package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.dto.DomainEvent;
import com.clucky.catalogstoreservice.entity.ComboItem;
import com.clucky.catalogstoreservice.entity.Product;
import com.clucky.catalogstoreservice.exception.ApiException;
import com.clucky.catalogstoreservice.exception.ErrorCode;
import com.clucky.catalogstoreservice.repository.ComboItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderStockServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final ComboItemRepository combos = mock(ComboItemRepository.class);
    private final OrderStockService service = new OrderStockService(jdbc, inventory, combos, mapper);

    private DomainEvent event(String type) {
        var payload = mapper.createObjectNode().put("orderId", 11).put("storeId", 2);
        payload.putObject("stockQuantities").put("3", 4);
        return new DomainEvent("event-11", "stock-check", type, "cart-order-service", Instant.parse("2026-11-13T10:00:00Z"), payload);
    }

    private void ledger(String status, String quantities) {
        when(jdbc.queryForObject(startsWith("SELECT started_at"), eq(Timestamp.class))).thenReturn(Timestamp.from(Instant.parse("2026-11-13T09:00:00Z")));
        when(jdbc.queryForMap(startsWith("SELECT store_id,status"), eq(11L))).thenReturn(Map.of("store_id", 2L, "status", status, "quantities", quantities));
    }

    @Test void snapshotDeductsOnceAndCancellationRestoresTheOriginalQuantity() {
        ledger("PENDING", "{}");
        service.apply(event("order.created"));
        verify(inventory).changeForOrder(2L, 3L, -4);
        verifyNoInteractions(combos);
        ledger("DEDUCTED", "{\"3\":4}");
        service.apply(event("order.created"));
        service.apply(event("order.cancelled"));
        verify(inventory).changeForOrder(2L, 3L, 4);
        ledger("CANCELLED", "{\"3\":4}");
        service.apply(event("order.cancelled"));
        service.apply(event("order.created"));
        verifyNoMoreInteractions(inventory);
    }

    @Test void earlyCancellationAndHistoricalEventsNeverAddStock() {
        ledger("PENDING", "{}");
        service.apply(event("order.cancelled"));
        verifyNoInteractions(inventory);
        when(jdbc.queryForObject(startsWith("SELECT started_at"), eq(Timestamp.class))).thenReturn(Timestamp.from(Instant.parse("2026-11-14T00:00:00Z")));
        service.apply(event("order.created"));
        verifyNoInteractions(inventory);
    }

    @Test void combinesStandaloneAndComboQuantitiesAndRejectsInvalidDemand() throws Exception {
        Product product = new Product(); product.setId(3L);
        when(combos.findByComboId(9L)).thenReturn(List.of(new ComboItem(null, null, product, 2, null)));
        var items = mapper.readTree("[{\"itemType\":\"PRODUCT\",\"productId\":3,\"quantity\":1},{\"itemType\":\"COMBO\",\"comboId\":9,\"quantity\":2}]");
        assertEquals(Map.of(3L, 5), OrderStockService.quantities(items, combos));
        assertThrows(IllegalArgumentException.class, () -> OrderStockService.quantities(mapper.readTree("[{\"itemType\":\"PRODUCT\",\"productId\":3,\"quantity\":0}]"), combos));
    }

    @Test void failedStockUpdateDoesNotMarkTheOrderDeducted() {
        ledger("PENDING", "{}");
        doThrow(new IllegalStateException("insufficient inventory")).when(inventory).changeForOrder(2L, 3L, -4);
        assertThrows(IllegalStateException.class, () -> service.apply(event("order.created")));
        verify(jdbc, never()).update(startsWith("UPDATE catalog_service.order_stock_ledger"), any(Object[].class));
    }

    @Test void reserveOrderStockDeductsAndRecordsInLedger() {
        when(jdbc.queryForMap(startsWith("SELECT store_id,status,quantities::text"), eq(99L)))
                .thenReturn(Map.of("store_id", 2L, "status", "PENDING", "quantities", "{}"));
        service.reserveOrderStock(99L, 2L, Map.of(3L, 2), Map.of(3L, 5L), "corr-1");
        verify(jdbc).update(startsWith("INSERT INTO catalog_service.order_stock_ledger"), eq(99L), eq(2L), eq("sync-reserve-99"), eq("corr-1"));
        verify(inventory).reserveItemForOrder(2L, 3L, 2, 5L);
        verify(jdbc).update(startsWith("UPDATE catalog_service.order_stock_ledger"), eq("{\"3\":2}"), eq("sync-reserve-99"), eq("corr-1"), eq(99L));
    }

    @Test void duplicateReservationDoesNotDeductTwice() {
        when(jdbc.queryForMap(startsWith("SELECT store_id,status,quantities::text"), eq(99L)))
                .thenReturn(Map.of("store_id", 2L, "status", "PENDING", "quantities", "{}"))
                .thenReturn(Map.of("store_id", 2L, "status", "DEDUCTED", "quantities", "{\"3\":2}"));
        service.reserveOrderStock(99L, 2L, Map.of(3L, 2), Map.of(), "corr-1");
        service.reserveOrderStock(99L, 2L, Map.of(3L, 2), Map.of(), "corr-1");
        verify(inventory, times(1)).reserveItemForOrder(2L, 3L, 2, null);
        verify(jdbc, times(1)).update(startsWith("UPDATE catalog_service.order_stock_ledger"), any(), any(), any(), any());
    }

    @Test void rejectsConflictingOrCancelledReservationWithoutChangingStock() {
        when(jdbc.queryForMap(startsWith("SELECT store_id,status,quantities::text"), eq(99L)))
                .thenReturn(Map.of("store_id", 2L, "status", "DEDUCTED", "quantities", "{\"3\":2}"))
                .thenReturn(Map.of("store_id", 9L, "status", "DEDUCTED", "quantities", "{\"3\":2}"))
                .thenReturn(Map.of("store_id", 2L, "status", "CANCELLED", "quantities", "{\"3\":2}"));

        for (var parameters : List.of(Map.of(3L, 3), Map.of(3L, 2), Map.of(3L, 2))) {
            assertEquals(ErrorCode.RESOURCE_CONFLICT,
                    assertThrows(ApiException.class, () -> service.reserveOrderStock(99L, 2L, parameters, Map.of(), "retry")).getCode());
        }
        verifyNoInteractions(inventory);
    }

    @Test void rejectsNonPositiveAndMissingQuantitiesWithoutTouchingLedger() {
        for (Map<Long, Integer> invalid : List.of(Map.of(3L, -1), Map.of(3L, 0), Map.of(-1L, 2), Map.of(0L, 2))) {
            assertEquals(ErrorCode.VALIDATION_ERROR,
                    assertThrows(ApiException.class, () -> service.reserveOrderStock(99L, 2L, invalid, Map.of(), null)).getCode());
        }
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> service.reserveOrderStock(0L, 2L, Map.of(3L, 2), Map.of(), null)).getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> service.reserveOrderStock(99L, 0L, Map.of(3L, 2), Map.of(), null)).getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> service.reserveOrderStock(99L, 2L, Map.of(), Map.of(), null)).getCode());
        Map<Long, Integer> nullQuantity = new java.util.HashMap<>();
        nullQuantity.put(3L, null);
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> service.reserveOrderStock(99L, 2L, nullQuantity, Map.of(), null)).getCode());
        Map<Long, Integer> nullProduct = new java.util.HashMap<>();
        nullProduct.put(null, 2);
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> service.reserveOrderStock(99L, 2L, nullProduct, Map.of(), null)).getCode());
        verifyNoInteractions(jdbc, inventory);
    }

    @Test void releaseOrderStockRestoresWhenDeducted() {
        when(jdbc.queryForList(startsWith("SELECT store_id,status,quantities::text"), eq(99L)))
                .thenReturn(List.of(Map.of("store_id", 2L, "status", "DEDUCTED", "quantities", "{\"3\":2}")))
                .thenReturn(List.of(Map.of("store_id", 2L, "status", "CANCELLED", "quantities", "{\"3\":2}")));
        service.releaseOrderStock(99L, 2L, "corr-cancel");
        service.releaseOrderStock(99L, 2L, "corr-cancel");
        verify(inventory).changeForOrder(2L, 3L, 2);
        verify(jdbc).update(startsWith("UPDATE catalog_service.order_stock_ledger SET status='CANCELLED'"), eq(99L));
    }

    @Test void releaseRefusesAnotherStoreAndPreservesOriginalReservation() {
        when(jdbc.queryForList(startsWith("SELECT store_id,status,quantities::text"), eq(99L)))
                .thenReturn(List.of(Map.of("store_id", 2L, "status", "DEDUCTED", "quantities", "{\"3\":2}")));
        assertEquals(ErrorCode.RESOURCE_CONFLICT,
                assertThrows(ApiException.class, () -> service.releaseOrderStock(99L, 9L, "wrong-store")).getCode());
        verifyNoInteractions(inventory);
        verify(jdbc, never()).update(startsWith("UPDATE catalog_service.order_stock_ledger"), any(Object[].class));
    }
}
