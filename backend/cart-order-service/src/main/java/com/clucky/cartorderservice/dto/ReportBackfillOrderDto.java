package com.clucky.cartorderservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReportBackfillOrderDto(
        Long orderId,
        String orderCode,
        Long storeId,
        String status,
        String paymentMethod,
        String collectionMethod,
        String orderSource,
        String type,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal pointsAmount,
        BigDecimal totalAmount,
        BigDecimal collectedCash,
        BigDecimal declaredTransfer,
        Instant completedAt,
        List<BackfillOrderItemDto> items
) {
    public record BackfillOrderItemDto(
            Long id,
            String itemType,
            Long productId,
            Long comboId,
            LocalizedText itemName,
            Integer quantity,
            BigDecimal lineTotal
    ) {}
}
