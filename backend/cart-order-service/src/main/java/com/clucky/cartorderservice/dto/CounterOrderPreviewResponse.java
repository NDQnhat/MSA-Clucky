package com.clucky.cartorderservice.dto;

import java.math.BigDecimal;
import java.util.List;

public record CounterOrderPreviewResponse(
        String draftHash, BigDecimal subtotal, BigDecimal voucherDiscount, BigDecimal tierDiscount,
        int pointsUsed, BigDecimal pointsAmount, long pointsToEarn, long availablePoints,
        int maxRedeemablePoints, BigDecimal totalAmount, boolean redeemAllowed, boolean voucherValid,
        String voucherMessage) {}
