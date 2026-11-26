package com.clucky.promotionloyaltyservice.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "vouchers", schema = "promotion_service")
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 80)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_kind", nullable = false, length = 16)
    private VoucherCampaignKind campaignKind = VoucherCampaignKind.LEGACY;

    @Enumerated(EnumType.STRING)
    @Column(name = "applicability", nullable = false, length = 16)
    private VoucherApplicability applicability = VoucherApplicability.ALL;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "voucher_campaign_stores", schema = "promotion_service", joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "store_id", nullable = false)
    private Set<Long> storeIds = new LinkedHashSet<>();

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoucherType type;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal value;

    @Column(name = "min_order_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Column(name = "max_discount", precision = 12, scale = 0)
    private BigDecimal maxDiscount;

    @Column(name = "total_limit")
    private Integer totalLimit;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @Column(name = "printable", nullable = false)
    private boolean printable = false;

    @Column(name = "max_print_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal maxPrintRatio = new BigDecimal("0.5000");

    @Column(name = "max_print_per_store")
    private Integer maxPrintPerStore;

    @Column(name = "printed_count", nullable = false)
    private int printedCount;

    @Column(name = "printed_unredeemed", nullable = false)
    private int printedUnredeemed;

    @Version
    private long version;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoucherScope scope = VoucherScope.ALL;

    @Column(name = "store_id")
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoucherStatus status = VoucherStatus.ACTIVE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Voucher() {
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (usedCount == null) {
            usedCount = 0;
        }
        if (minOrderAmount == null) {
            minOrderAmount = BigDecimal.ZERO;
        }
        if (scope == null) {
            scope = VoucherScope.ALL;
        }
        if (status == null) {
            status = VoucherStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Compute current effective status taking temporal boundaries and limits into account.
     */
    public VoucherStatus computeEffectiveStatus(Instant now) {
        if (status == VoucherStatus.DISABLED || status == VoucherStatus.PENDING_APPROVAL || status == VoucherStatus.REJECTED) {
            return status;
        }
        if (now.isBefore(startAt)) {
            return VoucherStatus.UPCOMING;
        }
        if (now.isAfter(endAt)) {
            return VoucherStatus.EXPIRED;
        }
        if (totalLimit != null && totalLimit > 0 && usedCount != null
                && (long) usedCount + reservedCount + printedUnredeemed >= totalLimit) {
            return VoucherStatus.EXHAUSTED;
        }
        return VoucherStatus.ACTIVE;
    }

    public int computeMaxPrintableLimit() {
        if (!printable || totalLimit == null || totalLimit <= 0 || maxPrintRatio == null) {
            return 0;
        }
        return BigDecimal.valueOf(totalLimit)
                .multiply(maxPrintRatio)
                .setScale(0, RoundingMode.FLOOR)
                .intValue();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public VoucherCampaignKind getCampaignKind() { return campaignKind; }
    public void setCampaignKind(VoucherCampaignKind kind) { this.campaignKind = kind; }
    public VoucherApplicability getApplicability() { return applicability; }
    public void setApplicability(VoucherApplicability applicability) { this.applicability = applicability; }
    public Set<Long> getStoreIds() { return storeIds; }
    public void setStoreIds(Set<Long> storeIds) {
        Set<Long> selected = storeIds == null ? Set.of() : new LinkedHashSet<>(storeIds);
        this.storeIds.clear();
        this.storeIds.addAll(selected);
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public VoucherType getType() {
        return type;
    }

    public void setType(VoucherType type) {
        this.type = type;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public BigDecimal getMinOrderAmount() {
        return minOrderAmount;
    }

    public void setMinOrderAmount(BigDecimal minOrderAmount) {
        this.minOrderAmount = minOrderAmount;
    }

    public BigDecimal getMaxDiscount() {
        return maxDiscount;
    }

    public void setMaxDiscount(BigDecimal maxDiscount) {
        this.maxDiscount = maxDiscount;
    }

    public Integer getTotalLimit() {
        return totalLimit;
    }

    public void setTotalLimit(Integer totalLimit) {
        this.totalLimit = totalLimit;
    }

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public int getReservedCount() { return reservedCount; }

    public void setReservedCount(int reservedCount) { this.reservedCount = reservedCount; }

    public boolean isPrintable() { return printable; }

    public void setPrintable(boolean printable) { this.printable = printable; }

    public BigDecimal getMaxPrintRatio() { return maxPrintRatio; }

    public void setMaxPrintRatio(BigDecimal maxPrintRatio) { this.maxPrintRatio = maxPrintRatio; }

    public Integer getMaxPrintPerStore() { return maxPrintPerStore; }

    public void setMaxPrintPerStore(Integer maxPrintPerStore) { this.maxPrintPerStore = maxPrintPerStore; }

    public int getPrintedCount() { return printedCount; }

    public void setPrintedCount(int printedCount) { this.printedCount = printedCount; }

    public int getPrintedUnredeemed() { return printedUnredeemed; }

    public void setPrintedUnredeemed(int printedUnredeemed) { this.printedUnredeemed = printedUnredeemed; }

    public long getVersion() { return version; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public Long getRejectedBy() { return rejectedBy; }
    public void setRejectedBy(Long rejectedBy) { this.rejectedBy = rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Instant rejectedAt) { this.rejectedAt = rejectedAt; }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public VoucherScope getScope() {
        return scope;
    }

    public void setScope(VoucherScope scope) {
        this.scope = scope;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }

    public VoucherStatus getStatus() {
        return status;
    }

    public void setStatus(VoucherStatus status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(Long approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

