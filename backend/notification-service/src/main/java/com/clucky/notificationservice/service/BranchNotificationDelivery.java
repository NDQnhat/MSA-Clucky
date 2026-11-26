package com.clucky.notificationservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Durable delivery of branch notifications when Identity is temporarily unavailable. */
@Service
public class BranchNotificationDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(BranchNotificationDelivery.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BranchRecipientClient recipients;

    public BranchNotificationDelivery(JdbcTemplate jdbc, TransactionTemplate transactions,
                                      BranchRecipientClient recipients) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.recipients = recipients;
    }

    @Scheduled(fixedDelayString = "${clucky.notification.branch-inbox-poll-ms:3000}")
    public void deliverPending() {
        for (int i = 0; i < 5; i++) {
            Boolean claimed = transactions.execute(status -> {
                var rows = jdbc.queryForList("""
                        SELECT id, operation_key, store_id, role, title, content, type, ref_id, attempt_count
                        FROM notification_service.branch_notification_intents
                        WHERE delivered_at IS NULL AND next_attempt_at <= now()
                        ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """);
                if (rows.isEmpty()) return false;
                Map<String, Object> row = rows.getFirst();
                long id = ((Number) row.get("id")).longValue();
                long storeId = ((Number) row.get("store_id")).longValue();
                String role = (String) row.get("role");
                Long userId;
                try {
                    userId = recipients.currentActiveUserId(storeId, role);
                } catch (RuntimeException unavailable) {
                    defer(id, ((Number) row.get("attempt_count")).intValue());
                    LOG.warn("event=branch_notification_delivery_deferred storeId={} role={}", storeId, role);
                    return true;
                }
                if (userId == null) {
                    defer(id, ((Number) row.get("attempt_count")).intValue());
                    return true;
                }
                // Database failures must propagate and roll back the transaction:
                // PostgreSQL cannot execute a backoff update after a failed SQL.
                String operationKey = UUID.nameUUIDFromBytes(("BRANCH_IN_APP:" + row.get("operation_key") + ":" + userId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                jdbc.update("""
                        INSERT INTO notification_service.notifications(user_id,store_id,title,content,type,ref_id,operation_key)
                        VALUES (?,?,?::jsonb,?::jsonb,?,?,?) ON CONFLICT (operation_key) WHERE operation_key IS NOT NULL DO NOTHING
                        """, userId, storeId, String.valueOf(row.get("title")), String.valueOf(row.get("content")),
                        row.get("type"), row.get("ref_id"), operationKey);
                jdbc.update("UPDATE notification_service.branch_notification_intents SET delivered_at=now() WHERE id=?", id);
                return true;
            });
            if (!Boolean.TRUE.equals(claimed)) return;
        }
    }

    private void defer(long id, int attempts) {
        int seconds = Math.min(600, 5 * (1 << Math.min(7, attempts)));
        jdbc.update("""
                UPDATE notification_service.branch_notification_intents
                SET attempt_count=attempt_count+1, next_attempt_at=now() + (? * interval '1 second')
                WHERE id=? AND delivered_at IS NULL
                """, seconds, id);
    }
}
