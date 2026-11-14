package com.clucky.cartorderservice.controller;

import com.clucky.cartorderservice.repository.OrderRepository;
import com.clucky.cartorderservice.repository.RefundRequestRepository;
import com.clucky.cartorderservice.exception.ApiException;
import com.clucky.cartorderservice.exception.ErrorCode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/loyalty/orders")
public class LoyaltyOrderController {
    private final OrderRepository orders;
    private final com.clucky.cartorderservice.service.VoucherCheckoutRecovery recovery;
    private final RefundRequestRepository refunds;

    @org.springframework.beans.factory.annotation.Autowired
    public LoyaltyOrderController(OrderRepository orders, com.clucky.cartorderservice.service.VoucherCheckoutRecovery recovery,
            RefundRequestRepository refunds) {
        this.orders = orders; this.recovery = recovery; this.refunds = refunds;
    }

    public LoyaltyOrderController(OrderRepository orders, com.clucky.cartorderservice.service.VoucherCheckoutRecovery recovery) {
        this.orders = orders; this.recovery = recovery; this.refunds = null;
    }

    /** Optional refund fields are authoritative Order DB state, not Kafka assertions. */
    public record OrderState(Long orderId, Long customerId, String status, Long refundId,
            String refundStatus, java.math.BigDecimal refundAmount, String paymentStatus,
            java.math.BigDecimal totalAmount, String orderSource) {
        public OrderState(Long orderId, Long customerId, String status) {
            this(orderId, customerId, status, null, null, null, null, null, null);
        }
    }

    // Preserves existing direct callers while the GET endpoint accepts a refund lookup.
    public OrderState get(Long id) { return get(id, null); }

    @GetMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public OrderState get(@PathVariable Long id, @RequestParam(required = false) Long refundId) {
        if (id == null || id <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        if (refundId != null && refundId <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        recovery.lock(id);
        var order = orders.findById(id).orElse(null);
        if (order == null) {
            if (refundId != null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
            return new OrderState(id, null, "MISSING");
        }
        // Loyalty interprets CANCELLED as "release reserved points/vouchers". A failed
        // delivery or a COD-only simulated completion does not settle real money.
        // This projection deliberately keeps those simulated orders out of rewards,
        // including the periodic reconciliation path that queries this endpoint.
        boolean virtualCodCompletion = "DELIVERY".equals(order.getType())
                && "COD".equals(order.getPaymentMethod())
                && "COMPLETED".equals(order.getStatus())
                && !java.util.Set.of("PAID", "COLLECTED").contains(order.getPaymentStatus());
        boolean uncollectedCompletion = "COMPLETED".equals(order.getStatus())
                && !java.util.Set.of("PAID", "COLLECTED").contains(order.getPaymentStatus());
        String loyaltyState = "DELIVERY_FAILED".equals(order.getStatus()) || virtualCodCompletion
                ? "CANCELLED" : order.getStatus();
        if (uncollectedCompletion && !virtualCodCompletion) loyaltyState = "CONFIRMED";
        if (refundId == null) return new OrderState(id, order.getCustomerId(), loyaltyState);
        if (refunds == null) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        var refund = refunds.findById(refundId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!id.equals(refund.getOrderId()) || !java.util.Objects.equals(order.getCustomerId(), refund.getCustomerId()))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return new OrderState(id, order.getCustomerId(), loyaltyState, refund.getId(),
                refund.getStatus(), refund.getAmount(), order.getPaymentStatus(),
                order.getTotalAmount(), order.getOrderSource());
    }
}
