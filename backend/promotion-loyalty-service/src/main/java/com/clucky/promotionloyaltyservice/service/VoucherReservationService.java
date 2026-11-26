package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.dto.ValidateVoucherRequest;
import com.clucky.promotionloyaltyservice.exception.ApiException;
import com.clucky.promotionloyaltyservice.exception.ConflictException;
import com.clucky.promotionloyaltyservice.exception.ErrorCode;
import com.clucky.promotionloyaltyservice.repository.VoucherRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherReservationService {
    private final VoucherRepository repository;
    private final VoucherService vouchers;
    private final JdbcTemplate jdbc;
    private final PersonalVoucherService personal;
    private final PrintedVoucherService printed;

    @Autowired
    public VoucherReservationService(VoucherRepository repository, VoucherService vouchers, JdbcTemplate jdbc,
            PersonalVoucherService personal, PrintedVoucherService printed) {
        this.repository = repository;
        this.vouchers = vouchers;
        this.jdbc = jdbc;
        this.personal = personal;
        this.printed = printed;
    }

    public VoucherReservationService(VoucherRepository repository, VoucherService vouchers, JdbcTemplate jdbc,
            PersonalVoucherService personal) {
        this(repository, vouchers, jdbc, personal, null);
    }

    // Keeps legacy direct-instantiation callers compatible.
    public VoucherReservationService(VoucherRepository repository, VoucherService vouchers, JdbcTemplate jdbc) {
        this(repository, vouchers, jdbc, null, null);
    }

    @Transactional
    public UUID reserve(long orderId, Long customerId, long storeId, String code, UUID voucherInstanceId,
            BigDecimal amount, BigDecimal discountAmount) {
        lockOrder(orderId);
        if (PrintedVoucherService.isPrintedCode(code)) {
            if (voucherInstanceId != null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
            if (printed == null) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            printed.reserve(orderId, customerId, storeId, code, amount, discountAmount);
            return null;
        }
        boolean personalCode = code != null && (code.toUpperCase(Locale.ROOT).startsWith("CK1")
                || code.toUpperCase(Locale.ROOT).startsWith("CLUCKY:V1:"));
        if (voucherInstanceId != null || personalCode) {
            if (personal == null) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            return personal.reserve(orderId, customerId, storeId, code, voucherInstanceId, amount, discountAmount);
        }
        reserveLegacy(orderId, customerId, storeId, code, amount, discountAmount);
        return null;
    }

    @Transactional
    public void reserve(long orderId, long customerId, long storeId, String code, BigDecimal orderAmount, BigDecimal discountAmount) {
        reserveLegacy(orderId, customerId, storeId, code, orderAmount, discountAmount);
    }

    private void reserveLegacy(long orderId, Long customerId, long storeId, String code,
            BigDecimal orderAmount, BigDecimal discountAmount) {
        if (orderId <= 0 || customerId != null && customerId <= 0 || storeId <= 0
                || code == null || !code.matches("[A-Za-z0-9_-]{1,80}")
                || orderAmount == null || discountAmount == null || orderAmount.signum() < 0 || discountAmount.signum() < 0
                || discountAmount.compareTo(orderAmount) > 0 || orderAmount.stripTrailingZeros().scale() > 0
                || discountAmount.stripTrailingZeros().scale() > 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        lockOrder(orderId);
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        var rows = jdbc.queryForList("SELECT * FROM promotion_service.voucher_reservations WHERE order_id=? FOR UPDATE", orderId);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if ("RELEASED".equals(row.get("state")) || !Objects.equals(row.get("customer_id"), customerId)
                    || number(row, "store_id") != storeId || !normalized.equals(row.get("code"))
                    || orderAmount.compareTo((BigDecimal) row.get("order_amount")) != 0
                    || discountAmount.compareTo((BigDecimal) row.get("discount_amount")) != 0)
                throw new ConflictException("Voucher reservation mismatch");
            return;
        }
        var voucher = repository.findByCodeWithLock(normalized)
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        var quote = vouchers.validateVoucher(new ValidateVoucherRequest(normalized, orderAmount, storeId));
        if (!quote.isValid()) throw new ApiException(ErrorCode.valueOf(quote.getErrorCode()));
        if (quote.getDiscountAmount().compareTo(discountAmount) != 0)
            throw new ConflictException("Voucher changed; preview again");
        jdbc.update("INSERT INTO promotion_service.voucher_reservations(order_id,voucher_id,customer_id,store_id,code,order_amount,discount_amount,state) VALUES (?,?,?,?,?,?,?,'RESERVED')",
                orderId, voucher.getId(), customerId, storeId, normalized, orderAmount, discountAmount);
        voucher.setReservedCount(Math.addExact(voucher.getReservedCount(), 1));
        repository.saveAndFlush(voucher);
    }

    @Transactional
    public void release(long orderId) {
        if (orderId <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        lockOrder(orderId);
        jdbc.update("INSERT INTO promotion_service.voucher_reservations(order_id,state) VALUES (?,'RELEASED') ON CONFLICT (order_id) DO NOTHING", orderId);
        var row = jdbc.queryForList("SELECT * FROM promotion_service.voucher_reservations WHERE order_id=? FOR UPDATE", orderId).getFirst();
        if ("COMPLETED".equals(row.get("state"))) throw new ConflictException("Consumed voucher cannot be released");
        finish(row, "RELEASED", null, null);
    }

    @Transactional
    public void settle(long orderId, Long customerId, String status, String eventId, String correlationId) {
        if (orderId <= 0 || customerId != null && customerId <= 0 || status == null || eventId != null && (eventId.isBlank() || eventId.length() > 160
                || correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,128}"))) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        if (!Set.of("PENDING_PAYMENT", "CONFIRMED", "PREPARING", "READY", "SHIPPING", "COMPLETED", "CANCELLED", "MISSING").contains(status))
            throw new IllegalArgumentException("Invalid voucher settlement status");
        lockOrder(orderId);
        if (Set.of("CANCELLED", "MISSING", "COMPLETED").contains(status))
            jdbc.update("INSERT INTO promotion_service.voucher_reservations(order_id,customer_id,state) VALUES (?,?,'RELEASED') ON CONFLICT DO NOTHING", orderId, customerId);
        var rows = jdbc.queryForList("SELECT * FROM promotion_service.voucher_reservations WHERE order_id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        if (!Objects.equals(row.get("customer_id"), customerId))
            throw new ConflictException("Order owner mismatch");
        if (eventId != null) {
            int inserted = jdbc.update("INSERT INTO promotion_service.voucher_order_events(event_id,order_id,customer_id,status,correlation_id) VALUES (?,?,?,?,?) ON CONFLICT (event_id) DO NOTHING",
                    eventId, orderId, customerId, status, correlationId);
            if (inserted == 0) {
                var previous = jdbc.queryForList("SELECT order_id,customer_id,status,correlation_id FROM promotion_service.voucher_order_events WHERE event_id=?", eventId).getFirst();
                if (number(previous, "order_id") != orderId || !Objects.equals(previous.get("customer_id"), customerId)
                        || !status.equals(previous.get("status")) || !correlationId.equals(previous.get("correlation_id")))
                    throw new ConflictException("Event identity mismatch");
                return;
            }
        }
        if ("COMPLETED".equals(status)) finish(row, "COMPLETED", eventId, correlationId);
        else if ("CANCELLED".equals(status) || "MISSING".equals(status)) finish(row, "RELEASED", eventId, correlationId);
        else jdbc.update("UPDATE promotion_service.voucher_reservations SET updated_at=now() WHERE order_id=? AND state='RESERVED'", orderId);
    }

    private void finish(Map<String, Object> row, String state, String eventId, String correlationId) {
        if (row.get("printed_voucher_id") != null) {
            if (printed == null) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            printed.finish(row, state, eventId);
            if (correlationId != null)
                jdbc.update("UPDATE promotion_service.voucher_reservations SET correlation_id=? WHERE order_id=?",
                        correlationId, number(row, "order_id"));
            return;
        }
        if (row.get("voucher_instance_id") != null) {
            if (personal == null) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            personal.finish(row, state, eventId);
            if (correlationId != null)
                jdbc.update("UPDATE promotion_service.voucher_reservations SET correlation_id=? WHERE order_id=?",
                        correlationId,number(row, "order_id"));
            return;
        }
        if (!"RESERVED".equals(row.get("state"))) return;
        var voucher = repository.findByIdWithLock(number(row, "voucher_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        voucher.setReservedCount(Math.subtractExact(voucher.getReservedCount(), 1));
        if ("COMPLETED".equals(state)) {
            voucher.setUsedCount(Math.addExact(voucher.getUsedCount(), 1));
            jdbc.update("INSERT INTO promotion_service.voucher_usages(voucher_id,customer_id,order_id) VALUES (?,?,?)",
                    voucher.getId(), row.get("customer_id"), number(row, "order_id"));
        }
        jdbc.update("UPDATE promotion_service.voucher_reservations SET state=?,event_id=?,correlation_id=?,updated_at=now() WHERE order_id=?",
                state, eventId, correlationId, number(row, "order_id"));
        repository.saveAndFlush(voucher);
    }

    private void lockOrder(long orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (row, index) -> 0, orderId);
    }

    @Transactional
    public void recordRefund(long orderId, String refundId) {
        lockOrder(orderId);
        if (personal != null) {
            personal.recordRefund(orderId, refundId);
        }
        if (printed != null) {
            printed.recordRefund(orderId, refundId);
        }
    }

    public record PendingVoucher(Long orderId, Long customerId) {}

    /** Includes guest counter orders, which have no loyalty wallet reservation. */
    @Transactional(readOnly = true)
    public List<PendingVoucher> pending() {
        return jdbc.query("SELECT order_id,customer_id FROM promotion_service.voucher_reservations"
                + " WHERE state='RESERVED' AND updated_at < now() - interval '5 minutes'"
                + " ORDER BY updated_at LIMIT 100",
                (rs, index) -> new PendingVoucher(rs.getLong("order_id"),
                        rs.getObject("customer_id") == null ? null : rs.getLong("customer_id")));
    }

    private long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
}
