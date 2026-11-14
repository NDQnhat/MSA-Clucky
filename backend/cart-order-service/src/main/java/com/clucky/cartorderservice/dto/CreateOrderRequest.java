package com.clucky.cartorderservice.dto;

import com.clucky.cartorderservice.cart.CartItemDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record CreateOrderRequest(
        @NotNull(message = "storeId is required") Long storeId,
        @NotBlank(message = "type is required") String type,
        @NotBlank(message = "receiverName is required") String receiverName,
        @NotBlank(message = "receiverPhone is required") String receiverPhone,
        String deliveryAddress,
        String note,
        @NotBlank(message = "paymentMethod is required") String paymentMethod,
        String voucherCode,
        Integer pointsUsed,
        Instant scheduledAt,
        List<CartItemDto> items,
        @jakarta.validation.constraints.DecimalMin("-90") @jakarta.validation.constraints.DecimalMax("90") Double latitude,
        @jakarta.validation.constraints.DecimalMin("-180") @jakarta.validation.constraints.DecimalMax("180") Double longitude,
        @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.Digits(integer = 12, fraction = 0) java.math.BigDecimal expectedTotal,
        java.util.UUID voucherInstanceId
) {
    public CreateOrderRequest(Long storeId, String type, String receiverName, String receiverPhone, String deliveryAddress,
            String note, String paymentMethod, String voucherCode, Integer pointsUsed, Instant scheduledAt,
            List<CartItemDto> items, Double latitude, Double longitude, java.math.BigDecimal expectedTotal) {
        this(storeId, type, receiverName, receiverPhone, deliveryAddress, note, paymentMethod, voucherCode, pointsUsed,
                scheduledAt, items, latitude, longitude, expectedTotal, null);
    }
    @jakarta.validation.constraints.AssertTrue
    public boolean isCoordinatePairValid() {
        return (latitude == null && longitude == null) || (latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude));
    }
}
