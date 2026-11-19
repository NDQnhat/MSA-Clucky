package com.clucky.cartorderservice.controller;

import com.clucky.cartorderservice.entity.Order;
import com.clucky.cartorderservice.repository.OrderRepository;
import com.clucky.cartorderservice.service.VoucherCheckoutRecovery;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoyaltyOrderControllerTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final LoyaltyOrderController controller = new LoyaltyOrderController(
            orders, mock(VoucherCheckoutRecovery.class));

    @Test
    void completedMockCodDeliverySettlesLoyaltyWhileCustomerCashRemainsPending() {
        Order order = order("DELIVERY", "COD", "COMPLETED", "PENDING", "PAID_SIMULATED", 55_000);
        when(orders.findById(42L)).thenReturn(Optional.of(order));

        var state = controller.get(42L);

        assertEquals("COMPLETED", state.status());
        assertEquals("PENDING", state.paymentStatus());
    }

    @Test
    void completedZeroValueOrderDoesNotRequirePaymentToSettleLoyalty() {
        Order order = order("PICKUP", "COD", "COMPLETED", "PENDING", null, 0);
        when(orders.findById(42L)).thenReturn(Optional.of(order));

        assertEquals("COMPLETED", controller.get(42L).status());
    }

    @Test
    void failedDeliveryReleasesHeldLoyaltyEvenAfterMerchantAdvance() {
        Order order = order("DELIVERY", "COD", "DELIVERY_FAILED", "PENDING", "PAID_SIMULATED", 55_000);
        when(orders.findById(42L)).thenReturn(Optional.of(order));

        assertEquals("CANCELLED", controller.get(42L).status());
    }

    @Test
    void uncollectedPickupCompletionDoesNotSettleLoyalty() {
        Order order = order("PICKUP", "COD", "COMPLETED", "PENDING", null, 25_000);
        when(orders.findById(42L)).thenReturn(Optional.of(order));

        assertEquals("CONFIRMED", controller.get(42L).status());
    }

    private static Order order(String type, String method, String status, String paymentStatus,
            String merchantPaymentStatus, long total) {
        Order order = new Order();
        order.setId(42L);
        order.setType(type);
        order.setPaymentMethod(method);
        order.setStatus(status);
        order.setPaymentStatus(paymentStatus);
        order.setMerchantPaymentStatus(merchantPaymentStatus);
        order.setTotalAmount(BigDecimal.valueOf(total));
        return order;
    }
}
