package com.clucky.cartorderservice.dto;

import java.util.List;

public record OrderDashboardSummaryResponse(
        long todayOrderCount,
        long activeKitchenCount,
        long todayCancelledCount,
        long openRefundRequestCount,
        List<OrderResponse> recentOrders
) {}
