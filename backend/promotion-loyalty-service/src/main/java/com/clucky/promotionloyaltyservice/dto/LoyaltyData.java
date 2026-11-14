package com.clucky.promotionloyaltyservice.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class LoyaltyData {
    private LoyaltyData() {}
    public record Policy(long version, long spendPerPoint, long pointValue, long silverThreshold,
            long goldThreshold, int silverDiscount, int goldDiscount,
            long maxEarnPerOrder, long maxEarnPerDay, long maxPointBalance) {
        public Policy(long version, long spendPerPoint, long pointValue, long silverThreshold,
                long goldThreshold, int silverDiscount, int goldDiscount) {
            this(version, spendPerPoint, pointValue, silverThreshold, goldThreshold,
                    silverDiscount, goldDiscount, 0, 0, 0);
        }
    }
    public record PolicyInput(@Positive long version, @Min(1) @Max(1000000000) long spendPerPoint,
            @Min(1) @Max(1000000000) long pointValue, @Min(1) @Max(1000000000) long silverThreshold,
            @Min(1) @Max(1000000000) long goldThreshold, @Min(0) @Max(100) int silverDiscount,
            @Min(0) @Max(100) int goldDiscount, @Min(0) long maxEarnPerOrder,
            @Min(0) long maxEarnPerDay, @Min(0) long maxPointBalance) {
        public PolicyInput(long version, long spendPerPoint, long pointValue, long silverThreshold,
                long goldThreshold, int silverDiscount, int goldDiscount) {
            this(version, spendPerPoint, pointValue, silverThreshold, goldThreshold,
                    silverDiscount, goldDiscount, 0, 0, 0);
        }
    }
    public record Balance(long customerId, long totalPoints, long reservedPoints, long availablePoints,
            long lifetimePoints, String tier) {}
    public record Entry(long id, Long orderId, long change, long balanceAfter, String type, String reason, Instant createdAt) {}
    public record Overview(Balance points, Policy policy, List<Entry> transactions, int page, int size, long totalElements) {}
    public record PreviewInput(@NotNull @Positive Long customerId, @NotNull @Min(0) Integer pointsToRedeem,
            @NotNull @DecimalMin("0") @DecimalMax("999999999999") BigDecimal orderAmount) {}
    public record Quote(long policyVersion, long availablePoints, int pointsUsed, BigDecimal pointsAmount,
            long pointsToEarn, String tier, BigDecimal tierDiscount, Long walletId) {
        public Quote(long policyVersion, long availablePoints, int pointsUsed, BigDecimal pointsAmount,
                long pointsToEarn, String tier, BigDecimal tierDiscount) {
            this(policyVersion, availablePoints, pointsUsed, pointsAmount, pointsToEarn, tier, tierDiscount, null);
        }
    }
    public record ReserveInput(@NotNull @Positive Long orderId, @NotNull @Positive Long customerId,
            @NotNull @Min(0) Integer pointsToRedeem, @NotNull @DecimalMin("0") @DecimalMax("999999999999") BigDecimal orderAmount,
            @NotNull @Positive Long policyVersion, @NotNull @DecimalMin("0") BigDecimal pointsAmount,
            @NotNull @DecimalMin("0") BigDecimal tierDiscount) {}
}
