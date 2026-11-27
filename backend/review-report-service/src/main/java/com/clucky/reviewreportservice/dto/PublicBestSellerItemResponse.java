package com.clucky.reviewreportservice.dto;

public record PublicBestSellerItemResponse(
        String itemType,
        Long itemRefId,
        String itemName,
        LocalizedText itemNameI18n,
        long quantitySold
) {}
