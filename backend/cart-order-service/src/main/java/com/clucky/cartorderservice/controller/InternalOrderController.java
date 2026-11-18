package com.clucky.cartorderservice.controller;

import com.clucky.cartorderservice.entity.Order;
import com.clucky.cartorderservice.exception.ApiException;
import com.clucky.cartorderservice.exception.ErrorCode;
import com.clucky.cartorderservice.repository.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderRepository orderRepository;

    public InternalOrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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
}
