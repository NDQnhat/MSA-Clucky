package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.exception.BadRequestException;
import com.clucky.promotionloyaltyservice.exception.ConflictException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Full-refund compensation of settled loyalty points, independent of voucher usage. */
@Service
public class LoyaltyRefundService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;
    private final LoyaltyService loyalty;
    private final VoucherReservationService vouchers;

    public LoyaltyRefundService(JdbcTemplate jdbc, LoyaltyService loyalty, VoucherReservationService vouchers) {
        this.jdbc = jdbc;
        this.loyalty = loyalty;
        this.vouchers = vouchers;
    }

    /** The caller MUST first verify the refund against the authenticated Order snapshot. */
    @Transactional
    public void process(long refundId, long orderId, BigDecimal amount, String orderStatus,
            Long customerId, String orderSource, String eventId, String correlationId) {
        if (refundId <= 0 || orderId <= 0 || amount == null || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 0 || amount.precision() > 12
                || !java.util.Set.of("COMPLETED", "CANCELLED", "CONFIRMED").contains(orderStatus)
                || !java.util.Set.of("WEB", "COUNTER").contains(orderSource)
                || eventId == null || eventId.isBlank() || eventId.length() > 160
                || correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,128}"))
            throw new BadRequestException("Invalid loyalty refund");

        // First insert serializes Kafka replays, including concurrent delivery. A unique
        // order_id forbids applying multiple full refunds to the same sale.
        int inserted = jdbc.update("""
                INSERT INTO promotion_service.loyalty_refunds(refund_id,order_id,refund_amount,state)
                VALUES (?,?,?,'PENDING') ON CONFLICT (refund_id) DO NOTHING
                """, refundId, orderId, amount);
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT * FROM promotion_service.loyalty_refunds WHERE refund_id=? FOR UPDATE", refundId);
        if (number(record, "order_id") != orderId
                || amount.compareTo((BigDecimal) record.get("refund_amount")) != 0)
            throw new ConflictException("Refund replay identity mismatch");
        if (inserted == 0) {
            if ("PENDING".equals(record.get("state"))) throw new ConflictException("Refund already processing");
            return;
        }

        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT * FROM promotion_service.loyalty_orders WHERE order_id=?", orderId);
        if (orders.isEmpty()) {
            // A paid/cancelled order without a loyalty reservation never credited points.
            noPoints(refundId);
            vouchers.recordRefund(orderId, String.valueOf(refundId));
            return;
        }

        Map<String, Object> order = orders.getFirst();
        // Counter orders are anonymous in Order Service even when their loyalty
        // wallet belongs to a verified account. Never equate those two identities.
        boolean counterOrder = order.get("authentication_method") != null;
        if ((counterOrder && !"COUNTER".equals(orderSource))
                || (!counterOrder && (!"WEB".equals(orderSource)
                    || !java.util.Objects.equals(order.get("customer_id"), customerId))))
            throw new ConflictException("Refund order ownership or source mismatch");

        // Counter settle locks order -> wallet; Web settle locks wallet -> order.
        // Match each writer's order to avoid deadlocks during event replay.
        if (counterOrder) order = lockedOrder(orderId);
        Long originalWalletId = order.get("wallet_id") == null ? null : number(order, "wallet_id");
        Map<String, Object> wallet = originalWalletId == null ? null : currentWallet(originalWalletId);
        if (!counterOrder) order = lockedOrder(orderId);

        if (!"COMPLETED".equals(order.get("state"))) {
            // The usual refund was for a cancelled, unfulfilled order. A genuinely
            // completed but not-yet-settled Order must be retried, not marked NOOP.
            if ("COMPLETED".equals(orderStatus) && !"CANCELLED".equals(order.get("state")))
                throw new ConflictException("Completed order loyalty settlement pending");
            noPoints(refundId);
            vouchers.recordRefund(orderId, String.valueOf(refundId));
            return;
        }
        if (wallet == null) throw new ConflictException("Settled loyalty wallet missing");

        long restored = number(order, "points");
        long earned = number(order, "earned_points");
        long total = number(wallet, "total_points");
        long reserved = number(wallet, "reserved_points");
        long lifetime = number(wallet, "lifetime_points");
        if (restored < 0 || earned < 0 || total < reserved || lifetime < earned)
            throw new ConflictException("Inconsistent loyalty refund ledger");

        // RefundRequestService currently only issues full refunds of order.totalAmount.
        // Thus all earned merchandise points reverse, excluding shipping and the
        // amount originally paid with points. Never debit points already held elsewhere.
        long afterRestore = Math.addExact(total, restored);
        long removable = Math.subtractExact(afterRestore, reserved);
        long debited = Math.min(earned, removable);
        long deferred = Math.subtractExact(earned, debited);
        long after = Math.subtractExact(afterRestore, debited);
        long afterLifetime = Math.subtractExact(lifetime, earned);
        long walletId = number(wallet, "id");
        long sourceWalletId = order.get("original_wallet_id") == null
                ? originalWalletId : number(order, "original_wallet_id");

        if (restored > 0) adjustment(wallet, sourceWalletId, orderId, refundId, restored,
                afterRestore, "RESTORE", "REFUND_REDEEM_RESTORE", number(order, "policy_version"), eventId, correlationId);
        if (debited > 0) adjustment(wallet, sourceWalletId, orderId, refundId, -debited,
                after, "EARN", "REFUND_EARN_REVERSE", number(order, "policy_version"), eventId, correlationId);
        if (deferred > 0) adjustment(wallet, sourceWalletId, orderId, refundId, 0,
                after, "DEFER", "REFUND_EARN_DEFERRED", number(order, "policy_version"), eventId, correlationId);

        int updated = jdbc.update("""
                UPDATE promotion_service.member_points
                SET total_points=?,lifetime_points=?,tier=?,
                    pending_clawback_points=pending_clawback_points+?,updated_at=now()
                WHERE id=?
                """, after, afterLifetime, LoyaltyMath.tier(afterLifetime, loyalty.policy()), deferred, walletId);
        if (updated != 1) throw new ConflictException("Refund wallet update failed");

        LocalDate earnedDate = ((Timestamp) order.get("created_at")).toInstant()
                .atZone(VIETNAM).toLocalDate();
        // Historical V11 data may not have a daily usage row. Keep history unchanged.
        jdbc.update("""
                UPDATE promotion_service.loyalty_daily_earn_usage
                SET earned=GREATEST(0,earned-?)
                WHERE wallet_id=? AND local_date=?
                """, earned, walletId, earnedDate);
        jdbc.update("""
                UPDATE promotion_service.loyalty_refunds
                SET wallet_id=?,original_wallet_id=?,state='COMPENSATED',
                    redeemed_restored=?,earned_reversed=?,earned_debited=?,clawback_deferred=?,processed_at=now()
                WHERE refund_id=?
                """, walletId, sourceWalletId, restored, earned, debited, deferred, refundId);

        // Record no-restore for already-redeemed personal vouchers, atomically with
        // point compensation. Never decrement campaign usage or reissue the voucher.
        vouchers.recordRefund(orderId, String.valueOf(refundId));
    }

    private void noPoints(long refundId) {
        jdbc.update("UPDATE promotion_service.loyalty_refunds SET state='NOOP',processed_at=now() WHERE refund_id=?", refundId);
    }

    private Map<String, Object> lockedOrder(long orderId) {
        return jdbc.queryForMap("SELECT * FROM promotion_service.loyalty_orders WHERE order_id=? FOR UPDATE", orderId);
    }

    private Map<String, Object> currentWallet(long walletId) {
        // Phone claims take both wallet row locks in ascending id order. Read the
        // merge reference first so refunds acquire the same pair in the same order.
        Map<String, Object> observed = jdbc.queryForMap(
                "SELECT merged_into_wallet_id FROM promotion_service.member_points WHERE id=?", walletId);
        Object merged = observed.get("merged_into_wallet_id");
        if (merged == null) {
            Map<String, Object> wallet = jdbc.queryForMap(
                    "SELECT * FROM promotion_service.member_points WHERE id=? FOR UPDATE", walletId);
            // Claim committed between the preliminary read and this lock. Retry
            // rather than taking a target lock out of order.
            if (wallet.get("merged_into_wallet_id") != null)
                throw new ConflictException("Loyalty wallet claimed concurrently; retry refund");
            return wallet;
        }
        long targetId = ((Number) merged).longValue();
        List<Map<String, Object>> locked = jdbc.queryForList("""
                SELECT * FROM promotion_service.member_points WHERE id IN (?,?) ORDER BY id FOR UPDATE
                """, walletId, targetId);
        Map<String, Object> original = locked.stream()
                .filter(row -> number(row, "id") == walletId).findFirst()
                .orElseThrow(() -> new ConflictException("Refund source wallet missing"));
        if (original.get("merged_into_wallet_id") == null
                || ((Number) original.get("merged_into_wallet_id")).longValue() != targetId)
            throw new ConflictException("Loyalty wallet claim changed; retry refund");
        // A guest wallet can have been claimed after sale settlement. Preserve
        // the original wallet in audit while posting to its verified owner.
        Map<String, Object> target = locked.stream()
                .filter(row -> number(row, "id") == targetId).findFirst()
                .orElseThrow(() -> new ConflictException("Refund destination wallet missing"));
        if (target.get("merged_into_wallet_id") != null)
            throw new ConflictException("Unexpected nested loyalty wallet merge");
        return target;
    }

    private void adjustment(Map<String, Object> wallet, long originWalletId, long orderId,
            long refundId, long change, long after, String step, String reason, long policyVersion,
            String eventId, String correlationId) {
        jdbc.update("""
                INSERT INTO promotion_service.point_transactions
                  (customer_id,wallet_id,origin_wallet_id,order_id,change,balance_after,
                   type,operation_key,reason,policy_version,event_id,correlation_id,source_type,source_ref)
                VALUES (?,?,?,?,?,?,'ADJUST',?,?,?,?,?,'REFUND',?)
                """, wallet.get("customer_id"), number(wallet, "id"), originWalletId, orderId,
                change, after, "refund:" + refundId + ":" + step, reason, policyVersion,
                eventId, correlationId, String.valueOf(refundId));
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }
}
