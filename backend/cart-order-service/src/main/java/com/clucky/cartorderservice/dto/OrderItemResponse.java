package com.clucky.cartorderservice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        Long orderId,
        String itemType,
        Long productId,
        Long comboId,
        String itemName,
        LocalizedText itemNameI18n,
        BigDecimal unitPrice,
        Integer quantity,
        JsonNode optionsSnapshot,
        JsonNode toppingsSnapshot,
        String note,
        BigDecimal lineTotal
) {
    public OrderItemResponse(
            Long id,
            Long orderId,
            String itemType,
            Long productId,
            Long comboId,
            String itemName,
            BigDecimal unitPrice,
            Integer quantity,
            JsonNode optionsSnapshot,
            JsonNode toppingsSnapshot,
            String note,
            BigDecimal lineTotal
    ) {
        this(id, orderId, itemType, productId, comboId, itemName, LocalizedText.ofSingle(itemName),
                unitPrice, quantity, optionsSnapshot, toppingsSnapshot, note, lineTotal);
    }
}
