package com.clucky.cartorderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Completes the failed-order half of the stock reservation saga.
 * Catalog only releases stock after seeing CANCELLED (or a missing order).
 */
@Component
public class PendingStockRecoveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(PendingStockRecoveryScheduler.class);

    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final boolean enabled;
    private final int timeoutMinutes;

    public PendingStockRecoveryScheduler(
            JdbcTemplate jdbc,
            OrderService orders,
            @Value("${clucky.stock.pending-recovery.enabled:true}") boolean enabled,
            @Value("${clucky.stock.pending-recovery.timeout-minutes:15}") int timeoutMinutes
    ) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.enabled = enabled;
        this.timeoutMinutes = Math.max(10, timeoutMinutes);
    }

    @Scheduled(fixedDelayString = "${clucky.stock.pending-recovery.interval-ms:60000}")
    public void recover() {
        if (!enabled) return;
        try {
            List<Long> staleOrders = jdbc.queryForList("""
                    SELECT id FROM order_service.orders
                    WHERE status='PENDING_STOCK'
                      AND created_at < now() - (? * INTERVAL '1 minute')
                    ORDER BY created_at, id
                    LIMIT 50
                    """, Long.class, timeoutMinutes);

            for (Long orderId : staleOrders) {
                try {
                    // OrderService takes the row lock and verifies the age and
                    // state again. A concurrent finalizer can win that lock.
                    if (orders.cancelStalePendingStock(orderId, Duration.ofMinutes(timeoutMinutes))) {
                        LOG.warn("event=stale_order_cancelled orderId={}", orderId);
                    }
                } catch (RuntimeException failure) {
                    LOG.warn("event=stale_order_recovery_retry orderId={} err={}", orderId, failure.getMessage());
                }
            }

            // Compatibility with incomplete claims left by earlier versions.
            // New claims and drafts are committed in one transaction.
            jdbc.update("""
                    DELETE FROM order_service.web_order_operations
                    WHERE order_id IS NULL AND created_at < now() - (? * INTERVAL '1 minute')
                    """, timeoutMinutes);
            jdbc.update("""
                    DELETE FROM order_service.counter_order_operations
                    WHERE order_id IS NULL AND created_at < now() - (? * INTERVAL '1 minute')
                    """, timeoutMinutes);
        } catch (RuntimeException failure) {
            LOG.warn("event=stale_order_recovery_cycle_failed err={}", failure.getMessage());
        }
    }
}
