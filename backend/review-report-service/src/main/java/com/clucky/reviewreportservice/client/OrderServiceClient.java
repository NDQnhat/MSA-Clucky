package com.clucky.reviewreportservice.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface OrderServiceClient {

    record OrderVerificationDto(
        Long id,
        String orderCode,
        Long customerId,
        String status,
        List<Long> productIds,
        Long storeId
    ) {
        public OrderVerificationDto(
            Long id,
            String orderCode,
            Long customerId,
            String status,
            List<Long> productIds
        ) {
            this(id, orderCode, customerId, status, productIds, null);
        }
    }

    record OrderStoreDto(
        Long orderId,
        Long storeId
    ) {}

    record BackfillOrderDto(
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
            com.clucky.reviewreportservice.dto.LocalizedText itemName,
            Integer quantity,
            BigDecimal lineTotal
        ) {}
    }

    OrderVerificationDto getOrderForReview(Long orderId, Long customerId);

    Long getOrderStoreId(Long orderId);

    List<BackfillOrderDto> getCompletedOrdersForBackfill(long afterOrderId, int limit);
}
