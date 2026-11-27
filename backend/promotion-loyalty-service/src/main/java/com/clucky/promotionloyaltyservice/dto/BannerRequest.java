package com.clucky.promotionloyaltyservice.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record BannerRequest(
        @ValidLocalizedText(required = true, max = 180, message = "Banner title is required and must be at most 180 characters")
        LocalizedText title,
        @Size(max = 1024, message = "Link URL must be at most 1024 characters")
        String linkUrl,
        @Size(max = 50, message = "Position must be at most 50 characters")
        String position,
        @Min(value = 0, message = "Display order must be non-negative")
        Integer displayOrder,
        Instant startAt,
        Instant endAt,
        Boolean isActive,
        Long expectedVersion,
        Long version,
        String imageUrl,
        String imagePublicId
) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public BannerRequest {
    }

    public BannerRequest(
            String title,
            String linkUrl,
            String position,
            Integer displayOrder,
            Instant startAt,
            Instant endAt,
            Boolean isActive,
            Long expectedVersion,
            Long version,
            String imageUrl,
            String imagePublicId) {
        this(LocalizedText.ofSingle(title), linkUrl, position, displayOrder, startAt, endAt, isActive, expectedVersion, version, imageUrl, imagePublicId);
    }

    public Long resolvedVersion() {
        return expectedVersion != null ? expectedVersion : version;
    }
}
