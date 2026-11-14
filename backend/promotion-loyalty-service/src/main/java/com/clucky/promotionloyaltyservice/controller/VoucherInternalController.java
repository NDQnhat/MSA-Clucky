package com.clucky.promotionloyaltyservice.controller;

import com.clucky.promotionloyaltyservice.service.VoucherReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.UUID;

@RestController
@org.springframework.validation.annotation.Validated
@RequestMapping("/internal/loyalty")
public class VoucherInternalController {
    private final VoucherReservationService vouchers;
    public VoucherInternalController(VoucherReservationService vouchers) { this.vouchers = vouchers; }

    public record ReservationRequest(@NotNull @Positive Long orderId, @Positive Long customerId,
            @NotNull @Positive Long storeId, @Size(max=100) String code, UUID voucherInstanceId,
            @NotNull @DecimalMin("0") @Digits(integer=12, fraction=0) BigDecimal orderAmount,
            @NotNull @DecimalMin("0") @Digits(integer=12, fraction=0) BigDecimal discountAmount) {}
    public record ReservationResponse(String code, UUID voucherInstanceId, BigDecimal discountAmount) {}

    @PostMapping("/voucher-reservations")
    public ReservationResponse reserve(@Valid @RequestBody ReservationRequest request) {
        UUID id = vouchers.reserve(request.orderId(), request.customerId(), request.storeId(), request.code(),
                request.voucherInstanceId(), request.orderAmount(), request.discountAmount());
        return new ReservationResponse(id == null ? request.code().toUpperCase(java.util.Locale.ROOT) : null,
                id, request.discountAmount());
    }

    @PostMapping("/voucher-reservations/{orderId}/release")
    public void release(@PathVariable @Positive Long orderId) {
        vouchers.release(orderId);
    }

    public record RefundRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,150}") String refundId) {}

    @PostMapping("/voucher-reservations/{orderId}/refund")
    public void recordRefund(@PathVariable @Positive Long orderId, @Valid @RequestBody RefundRequest request) {
        vouchers.recordRefund(orderId, request.refundId());
    }
}
