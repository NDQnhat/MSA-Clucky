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
        // A successful mock COD delivery settles the merchant side through the
        // simulated rider advance. Customer cash remains outside Clucky, so the
        // payment status stays PENDING while loyalty can settle the completed order.
        boolean noPaymentRequired = order.getTotalAmount() != null && order.getTotalAmount().signum() == 0;
        boolean settlementEligible = noPaymentRequired
                || java.util.Set.of("PAID", "COLLECTED").contains(order.getPaymentStatus())
                || ("DELIVERY".equals(order.getType()) && "COD".equals(order.getPaymentMethod())
                    && "PAID_SIMULATED".equals(order.getMerchantPaymentStatus()));
        boolean virtualCodCompletion = "DELIVERY".equals(order.getType())
                && "COD".equals(order.getPaymentMethod())
                && "COMPLETED".equals(order.getStatus())
                && !settlementEligible;
        boolean uncollectedCompletion = "COMPLETED".equals(order.getStatus())
                && !settlementEligible;
        String loyaltyState = "DELIVERY_FAILED".equals(order.getStatus()) || virtualCodCompletion
                ? "CANCELLED" : order.getStatus();
        if (uncollectedCompletion && !virtualCodCompletion) loyaltyState = "CONFIRMED";
        if (refundId == null) return new OrderState(id, order.getCustomerId(), loyaltyState, null, null, null,
                order.getPaymentStatus(), order.getTotalAmount(), order.getOrderSource());
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
