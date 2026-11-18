package com.clucky.cartorderservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record CounterPaymentClaimRequest(
        @NotNull @Positive Long storeId,
        @NotBlank String orderCode,
        @NotBlank String paymentMethod,
        @NotNull @DecimalMin("0.01") BigDecimal totalAmount
) {}
