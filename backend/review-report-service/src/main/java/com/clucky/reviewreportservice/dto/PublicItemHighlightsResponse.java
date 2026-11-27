package com.clucky.reviewreportservice.dto;

import java.util.List;
import java.util.Map;

public record PublicItemHighlightsResponse(
        List<Long> topProductIds,
        List<Long> topComboIds,
        Map<Long, ProductRatingSummary> ratings
) {
    public record ProductRatingSummary(double averageRating, long reviewCount) {}
}
