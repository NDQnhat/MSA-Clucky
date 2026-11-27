package com.clucky.cartorderservice.controller;

import com.clucky.cartorderservice.dto.LocalizedText;
import com.clucky.cartorderservice.dto.ReportBackfillOrderDto;
import com.clucky.cartorderservice.entity.Order;
import com.clucky.cartorderservice.entity.OrderItem;
import com.clucky.cartorderservice.exception.ApiException;
import com.clucky.cartorderservice.exception.ErrorCode;
import com.clucky.cartorderservice.repository.OrderItemRepository;
import com.clucky.cartorderservice.repository.OrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public InternalOrderController(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    public record OrderStatusResponse(
            Long orderId,
            String orderCode,
            String status,
            Long storeId,
            Instant createdAt
    ) {}

    @GetMapping("/{orderId}/status")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    @Transactional(readOnly = true)
    public ResponseEntity<OrderStatusResponse> getOrderStatus(@PathVariable Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Đơn hàng không tồn tại"));
        return ResponseEntity.ok(new OrderStatusResponse(
                order.getId(),
                order.getOrderCode(),
                order.getStatus(),
                order.getStoreId(),
                order.getCreatedAt()
        ));
    }

    @GetMapping("/report-backfill")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ReportBackfillOrderDto>> getCompletedOrdersForBackfill(
            @RequestParam(value = "afterOrderId", defaultValue = "0") long afterOrderId,
            @RequestParam(value = "limit", defaultValue = "200") int limit
    ) {
        int clampedLimit = Math.min(Math.max(1, limit), 500);
        List<Order> completed = orderRepository.findCompletedOrdersAfter(
                Math.max(0L, afterOrderId),
                PageRequest.of(0, clampedLimit)
        );
        if (completed.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<Long> orderIds = completed.stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrder = orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        List<ReportBackfillOrderDto> payload = completed.stream()
                .map(o -> new ReportBackfillOrderDto(
                        o.getId(),
                        o.getOrderCode(),
                        o.getStoreId(),
                        o.getStatus(),
                        o.getPaymentMethod(),
                        o.getCollectionMethod(),
                        o.getOrderSource(),
                        o.getType(),
                        o.getSubtotal(),
                        o.getDiscountAmount(),
                        o.getPointsAmount(),
                        o.getTotalAmount(),
                        o.getCollectedCash(),
                        o.getDeclaredTransfer(),
                        o.getUpdatedAt() != null ? o.getUpdatedAt() : o.getCreatedAt(),
                        itemsByOrder.getOrDefault(o.getId(), List.of()).stream()
                                .map(i -> new ReportBackfillOrderDto.BackfillOrderItemDto(
                                        i.getId(),
                                        i.getItemType(),
                                        i.getProductId(),
                                        i.getComboId(),
                                        i.getItemName() != null ? i.getItemName() : LocalizedText.ofSingle(""),
                                        i.getQuantity(),
                                        i.getLineTotal()
                                ))
                                .toList()
                ))
                .toList();
        return ResponseEntity.ok(payload);
    }
}
