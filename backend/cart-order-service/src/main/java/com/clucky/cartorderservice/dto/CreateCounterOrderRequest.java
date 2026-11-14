package com.clucky.cartorderservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateCounterOrderRequest(
        Long storeId,
        String diningOption,
        String paymentMethod,
        @Size(max = 1000) String note,
        @NotEmpty(message = "Items list cannot be empty") @Valid List<CounterOrderItemRequest> items,
        @Size(max = 128) String loyaltySubject,
        @Size(max = 128) String loyaltyScanProof,
        @jakarta.validation.constraints.PositiveOrZero Integer pointsUsed,
        @Size(max = 80) String voucherCode,
        java.util.UUID voucherInstanceId
) {
    public CreateCounterOrderRequest(Long storeId, String diningOption, String paymentMethod, String note, List<CounterOrderItemRequest> items) {
        this(storeId, diningOption, paymentMethod, note, items, null, null, 0, null, null);
    }
    public CreateCounterOrderRequest {
        diningOption = (diningOption != null && !diningOption.isBlank()) ? diningOption.trim().toUpperCase() : "TAKE_AWAY";
        paymentMethod = (paymentMethod != null && !paymentMethod.isBlank()) ? paymentMethod.trim().toUpperCase() : "CASH";
        items = items != null ? items : List.of();
        pointsUsed = pointsUsed == null ? 0 : pointsUsed;
    }
}
