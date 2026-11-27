package com.clucky.reviewreportservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ItemStoreComparisonResponse(
        String itemType,
        Long itemRefId,
        String itemName,
        LocalizedText itemNameI18n,
        LocalDate from,
        LocalDate to,
        long totalQuantitySold,
        BigDecimal totalGrossRevenue,
        BigDecimal totalAllocatedNetRevenue,
        List<StoreItemMetric> stores
) {
    public record StoreItemMetric(
            Long storeId,
            long quantitySold,
            long orderCount,
            BigDecimal grossRevenue,
            BigDecimal allocatedNetRevenue,
            double sharePct
    ) {}
}
