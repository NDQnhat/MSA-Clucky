package com.clucky.catalogstoreservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StockReconciliationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(StockReconciliationScheduler.class);

    private final JdbcTemplate jdbc;
    private final OrderStockService orderStockService;
    private final OrderServiceClient orderServiceClient;
    private final boolean enabled;
    private final int timeoutMinutes;

    public StockReconciliationScheduler(
            JdbcTemplate jdbc,
            OrderStockService orderStockService,
            OrderServiceClient orderServiceClient,
            @Value("${clucky.stock.reconciliation.enabled:true}") boolean enabled,
            @Value("${clucky.stock.reconciliation.timeout-minutes:10}") int timeoutMinutes
    ) {
        this.jdbc = jdbc;
        this.orderStockService = orderStockService;
        this.orderServiceClient = orderServiceClient;
        this.enabled = enabled;
        this.timeoutMinutes = timeoutMinutes;
    }

    @Scheduled(fixedDelayString = "${clucky.stock.reconciliation.interval-ms:60000}")
    public void reconcileStaleStockReservations() {
        if (!enabled) return;

        try {
            List<Map<String, Object>> staleReservations = jdbc.queryForList("""
                    SELECT order_id, store_id, updated_at
                    FROM catalog_service.order_stock_ledger
                    WHERE status = 'DEDUCTED'
                      AND event_id LIKE 'sync-reserve-%'
                      AND updated_at < now() - (? * INTERVAL '1 minute')
                    ORDER BY updated_at ASC
                    LIMIT 50
                    """, timeoutMinutes);

            if (staleReservations.isEmpty()) {
                return;
            }

            LOG.info("event=stock_reconciliation_start count={}", staleReservations.size());

            for (Map<String, Object> row : staleReservations) {
                long orderId = ((Number) row.get("order_id")).longValue();
                long storeId = ((Number) row.get("store_id")).longValue();

                reconcileOrder(orderId, storeId);
            }
        } catch (Exception ex) {
            LOG.warn("event=stock_reconciliation_cycle_failed err={}", ex.getMessage());
        }
    }

    public void reconcileOrder(long orderId, long storeId) {
        try {
            var orderOpt = orderServiceClient.getOrderStatus(orderId);
            if (orderOpt.isEmpty()) {
                LOG.warn("event=reconciliation_releasing_orphaned_stock orderId={} storeId={} reason=order_not_found",
                        orderId, storeId);
                orderStockService.releaseOrderStock(orderId, storeId, "reconciliation-404");
                return;
            }

            var remoteStatus = orderOpt.get();
            String status = remoteStatus.status();

            if ("CANCELLED".equalsIgnoreCase(status) || "PENDING_STOCK".equalsIgnoreCase(status)) {
                LOG.warn("event=reconciliation_releasing_abandoned_stock orderId={} storeId={} remoteStatus={}",
                        orderId, storeId, status);
                orderStockService.releaseOrderStock(orderId, storeId, "reconciliation-" + status.toLowerCase());
            } else {
                // Legitimate confirmed order
                LOG.info("event=reconciliation_order_confirmed orderId={} remoteStatus={}", orderId, status);
                jdbc.update("UPDATE catalog_service.order_stock_ledger SET event_id=? WHERE order_id=? AND status='DEDUCTED'",
                        "reconciled-confirmed-" + orderId, orderId);
            }
        } catch (Exception ex) {
            LOG.warn("event=reconciliation_order_check_failed orderId={} storeId={} err={}",
                    orderId, storeId, ex.getMessage());
        }
    }
}
