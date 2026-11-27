package com.clucky.reviewreportservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ItemRevenueReportResponse(
        Long storeId,
        LocalDate from,
        LocalDate to,
        String itemTypeFilter,
        String sortBy,
        BigDecimal totalGrossRevenue,
        BigDecimal totalAllocatedNetRevenue,
        long totalQuantitySold,
        int totalDistinctItems,
        List<ItemRevenueEntry> items
) {
    public record ItemRevenueEntry(
            String itemType,
            Long itemRefId,
            String itemName,
            LocalizedText itemNameI18n,
            long quantitySold,
            long orderCount,
            BigDecimal grossRevenue,
            BigDecimal allocatedNetRevenue,
            double revenueSharePct,
            long storeCount
    ) {}
}
