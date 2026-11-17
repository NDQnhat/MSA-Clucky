package com.clucky.cartorderservice.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeCounterPaymentMethodRequest(@NotBlank String paymentMethod, Long version) {}
