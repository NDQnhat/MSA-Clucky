package com.clucky.promotionloyaltyservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class PrintBatchDtos {
    private PrintBatchDtos() {}

    public record CreatePrintBatchRequest(
            @Positive(message = "storeId phải là số dương") Long storeId,
            @NotNull(message = "Số lượng phiếu cần in không được để trống")
            @Min(value = 1, message = "Số lượng phiếu in tối thiểu là 1")
            @Max(value = 200, message = "Mỗi lô chỉ được in tối đa 200 phiếu") Integer quantity,
            @Size(max = 500, message = "Ghi chú tối đa 500 ký tự") String note
    ) {}

    public record RevokePrintBatchRequest(
            @Size(max = 500, message = "Lý do thu hồi tối đa 500 ký tự") String reason
    ) {}

    public record PrintQuotaSummaryResponse(
            Long campaignId,
            Long storeId,
            boolean printable,
            Integer totalLimit,
            int usedCount,
            int reservedCount,
            int printedCount,
            int printedUnredeemed,
            int maxPrintableLimit,
            int globalPrintableRemaining,
            int poolRemaining,
            Integer storePrintLimit,
            int storePrintedCount,
            int maxPrintableNow,
            boolean multiStoreCampaign
    ) {}

    public record PrintBatchResponse(
            Long id,
            String batchCode,
            Long campaignId,
            String campaignTitle,
            String campaignType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            BigDecimal maxDiscount,
            Instant campaignStartAt,
            Instant campaignEndAt,
            Long storeId,
            String storeName,
            int quantity,
            String allowedChannels,
            String status,
            String note,
            int issuedCount,
            int reservedCount,
            int redeemedCount,
            int expiredCount,
            int revokedCount,
            Long createdBy,
            String createdByRole,
            Instant createdAt,
            Long revokedBy,
            Instant revokedAt,
            String revokeReason
    ) {}
}
