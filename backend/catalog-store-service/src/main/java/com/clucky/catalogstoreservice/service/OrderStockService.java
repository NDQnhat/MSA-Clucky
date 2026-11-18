package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.dto.DomainEvent;
import com.clucky.catalogstoreservice.exception.ApiException;
import com.clucky.catalogstoreservice.exception.ErrorCode;
import com.clucky.catalogstoreservice.repository.ComboItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

@Service
public class OrderStockService {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final ComboItemRepository combos;
    private final ObjectMapper mapper;

    public OrderStockService(JdbcTemplate jdbc, InventoryService inventory, ComboItemRepository combos, ObjectMapper mapper) {
        this.jdbc = jdbc; this.inventory = inventory; this.combos = combos; this.mapper = mapper;
    }

    @Transactional
    public void apply(DomainEvent event) {
        if (!"cart-order-service".equals(event.sourceService()) || !java.util.Set.of("order.created", "order.cancelled").contains(event.eventType()))
            throw new IllegalArgumentException("Invalid inventory event source or type");
        long orderId = positiveId(event.payload(), "orderId");
        long storeId = positiveId(event.payload(), "storeId");
        Timestamp started = jdbc.queryForObject("SELECT started_at FROM catalog_service.inventory_projection_start WHERE id=TRUE", Timestamp.class);
        if (event.occurredAt().isBefore(started.toInstant())) return;
        jdbc.update("INSERT INTO catalog_service.order_stock_ledger(order_id,store_id,status,event_id,correlation_id) VALUES (?,?,'PENDING',?,?) ON CONFLICT DO NOTHING",
                orderId, storeId, event.eventId(), event.correlationId());
        Map<String, Object> ledger = jdbc.queryForMap("SELECT store_id,status,quantities::text FROM catalog_service.order_stock_ledger WHERE order_id=? FOR UPDATE", orderId);
        if (((Number) ledger.get("store_id")).longValue() != storeId) throw new IllegalArgumentException("Inventory store mismatch");
        String status = ledger.get("status").toString();
        if ("CANCELLED".equals(status)) return;
        boolean cancelled = "order.cancelled".equals(event.eventType());
        Map<Long, Integer> quantities = new TreeMap<>();
        if (cancelled && "DEDUCTED".equals(status)) {
            try {
                var fields = mapper.readTree(ledger.get("quantities").toString()).fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    quantities.put(Long.parseLong(entry.getKey()), entry.getValue().intValue());
                }
            } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid stock ledger", failure); }
        } else if (!cancelled) {
            if ("DEDUCTED".equals(status)) return;
            if (event.payload().has("stockQuantities")) {
                JsonNode snapshot = event.payload().path("stockQuantities");
                if (!snapshot.isObject() || snapshot.isEmpty()) throw new IllegalArgumentException("Stock snapshot required");
                var fields = snapshot.fieldNames();
                while (fields.hasNext()) {
                    String product = fields.next();
                    long productId = Long.parseLong(product);
                    if (productId <= 0) throw new IllegalArgumentException("Invalid stock product");
                    quantities.put(productId, Math.toIntExact(positiveId(snapshot, product)));
                }
            } else quantities = quantities(event.payload().path("items"), combos);
        }
        for (var entry : quantities.entrySet()) inventory.changeForOrder(storeId, entry.getKey(), cancelled ? entry.getValue() : -entry.getValue());
        jdbc.update("UPDATE catalog_service.order_stock_ledger SET status=?,quantities=?::jsonb,event_id=?,correlation_id=?,updated_at=now() WHERE order_id=?",
                cancelled ? "CANCELLED" : "DEDUCTED", mapper.valueToTree(quantities).toString(), event.eventId(), event.correlationId(), orderId);
    }

    @Transactional
    public void reserveOrderStock(long orderId, long storeId, Map<Long, Integer> quantities, Map<Long, Long> expectedVersions, String correlationId) {
        if (orderId <= 0 || storeId <= 0 || quantities == null || quantities.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid order reservation parameters");
        }
        for (var entry : quantities.entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0 || entry.getValue() == null || entry.getValue() <= 0) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Stock quantities must contain positive product IDs and quantities");
            }
        }
        Map<Long, Integer> sortedQuantities = new TreeMap<>(quantities);
        String snapshot = mapper.valueToTree(sortedQuantities).toString();

        // Both Kafka apply() and synchronous reservations lock this row first.
        // Inserting PENDING is safe to roll back if any inventory item cannot be reserved.
        jdbc.update("INSERT INTO catalog_service.order_stock_ledger(order_id,store_id,status,event_id,correlation_id) " +
                        "VALUES (?,?,'PENDING',?,?) ON CONFLICT (order_id) DO NOTHING",
                orderId, storeId, "sync-reserve-" + orderId, correlationId != null ? correlationId : "sync");
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT store_id,status,quantities::text FROM catalog_service.order_stock_ledger WHERE order_id=? FOR UPDATE", orderId);

        if (((Number) ledger.get("store_id")).longValue() != storeId) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Inventory store mismatch");
        }
        String status = ledger.get("status").toString();
        if ("CANCELLED".equals(status)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Order stock was already cancelled");
        }
        if ("DEDUCTED".equals(status)) {
            try {
                if (!mapper.readTree(ledger.get("quantities").toString()).equals(mapper.readTree(snapshot))) {
                    throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Order stock quantities changed");
                }
            } catch (IOException invalidLedger) {
                throw new IllegalStateException("Invalid stock ledger", invalidLedger);
            }
            return; // Retry for the same order: already reserved; do not deduct again.
        }
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Unexpected stock ledger status: " + status);
        }

        for (var entry : sortedQuantities.entrySet()) {
            long productId = entry.getKey();
            int qty = entry.getValue();
            Long expectedVersion = expectedVersions != null ? expectedVersions.get(productId) : null;
            inventory.reserveItemForOrder(storeId, productId, qty, expectedVersion);
        }
        jdbc.update("UPDATE catalog_service.order_stock_ledger " +
                        "SET status='DEDUCTED', quantities=?::jsonb, event_id=?, correlation_id=?, updated_at=now() WHERE order_id=?",
                snapshot, "sync-reserve-" + orderId, correlationId != null ? correlationId : "sync", orderId);
    }

    @Transactional
    public void releaseOrderStock(long orderId, long storeId, String correlationId) {
        if (orderId <= 0 || storeId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid order release parameters");
        }
        var rows = jdbc.queryForList("SELECT store_id,status,quantities::text FROM catalog_service.order_stock_ledger WHERE order_id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) return;
        if (((Number) rows.get(0).get("store_id")).longValue() != storeId) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Inventory store mismatch");
        }
        String status = rows.get(0).get("status").toString();
        if (!"DEDUCTED".equals(status)) return;

        try {
            var fields = mapper.readTree(rows.get(0).get("quantities").toString()).fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                long productId = Long.parseLong(entry.getKey());
                int qty = entry.getValue().intValue();
                inventory.changeForOrder(storeId, productId, qty);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Không thể hoàn trả tồn kho", e);
        }
        jdbc.update("UPDATE catalog_service.order_stock_ledger SET status='CANCELLED', updated_at=now() WHERE order_id=?", orderId);
    }

    static Map<Long, Integer> quantities(JsonNode items, ComboItemRepository combos) {
        if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException("Order items required");
        Map<Long, Integer> quantities = new TreeMap<>();
        for (JsonNode item : items) {
            int quantity = Math.toIntExact(positiveId(item, "quantity"));
            if ("PRODUCT".equals(item.path("itemType").asText())) {
                quantities.merge(positiveId(item, "productId"), quantity, Math::addExact);
            } else if ("COMBO".equals(item.path("itemType").asText())) {
                var components = combos.findByComboId(positiveId(item, "comboId"));
                if (components.isEmpty()) throw new IllegalArgumentException("Combo components missing");
                for (var component : components) {
                    if (component.getQuantity() == null || component.getQuantity() <= 0) throw new IllegalArgumentException("Invalid combo quantity");
                    quantities.merge(component.getProduct().getId(), Math.multiplyExact(quantity, component.getQuantity()), Math::addExact);
                }
            } else throw new IllegalArgumentException("Invalid inventory item type");
        }
        return quantities;
    }

    private static long positiveId(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw new IllegalArgumentException("Invalid " + field);
        return value.longValue();
    }
}
