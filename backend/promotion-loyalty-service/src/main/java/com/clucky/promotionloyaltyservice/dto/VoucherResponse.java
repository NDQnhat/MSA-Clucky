package com.clucky.promotionloyaltyservice.dto;

import com.clucky.promotionloyaltyservice.config.LocaleResolverHelper;
import com.clucky.promotionloyaltyservice.model.Voucher;
import com.clucky.promotionloyaltyservice.model.VoucherApplicability;
import com.clucky.promotionloyaltyservice.model.VoucherCampaignKind;
import com.clucky.promotionloyaltyservice.model.VoucherScope;
import com.clucky.promotionloyaltyservice.model.VoucherStatus;
import com.clucky.promotionloyaltyservice.model.VoucherType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public class VoucherResponse {

    private Long id;
    private String code;
    private VoucherCampaignKind campaignKind;
    private VoucherApplicability applicability;
    private Set<Long> storeIds;
    private Integer usage;
    private Integer maxUsage;
    private String title;
    private LocalizedText titleI18n;
    private String description;
    private LocalizedText descriptionI18n;
    private VoucherType type;
    private BigDecimal value;
    private BigDecimal minOrderAmount;
    private BigDecimal maxDiscount;
    private Integer totalLimit;
    private Integer usedCount;
    private int reservedCount;
    private boolean printable;
    private BigDecimal maxPrintRatio;
    private Integer maxPrintPerStore;
    private int printedCount;
    private int printedUnredeemed;
    private int maxPrintableLimit;
    private int remainingPrintableQuota;
    private long version;
    private Instant startAt;
    private Instant endAt;
    private VoucherScope scope;
    private Long storeId;
    private String storeName;
    private VoucherStatus status;
    private VoucherStatus effectiveStatus;
    private Long createdBy;
    private Long approvedBy;
    private String rejectionReason;
    private Long rejectedBy;
    private Instant rejectedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public VoucherResponse() {
    }

    public int getReservedCount() { return reservedCount; }

    public boolean isPrintable() { return printable; }
    public BigDecimal getMaxPrintRatio() { return maxPrintRatio; }
    public Integer getMaxPrintPerStore() { return maxPrintPerStore; }
    public int getPrintedCount() { return printedCount; }
    public int getPrintedUnredeemed() { return printedUnredeemed; }
    public int getMaxPrintableLimit() { return maxPrintableLimit; }
    public int getRemainingPrintableQuota() { return remainingPrintableQuota; }

    public long getVersion() { return version; }

    public String getRejectionReason() { return rejectionReason; }
    public Long getRejectedBy() { return rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }

    public static VoucherResponse fromEntity(Voucher v) {
        return fromEntity(v, Instant.now(), LocaleResolverHelper.currentRequestLang());
    }

    public static VoucherResponse fromEntity(Voucher v, Instant now) {
        return fromEntity(v, now, LocaleResolverHelper.currentRequestLang());
    }

    public static VoucherResponse fromEntity(Voucher v, Instant now, String lang) {
        if (v == null) {
            return null;
        }
        VoucherResponse dto = new VoucherResponse();
        dto.setId(v.getId());
        dto.setCode(v.getCode());
        dto.campaignKind = v.getCampaignKind();
        dto.applicability = v.getApplicability();
        dto.storeIds = Set.copyOf(v.getStoreIds());
        dto.usage = v.getUsedCount();
        dto.maxUsage = v.getTotalLimit() == null || v.getTotalLimit() <= 0 ? null : v.getTotalLimit();
        dto.setTitle(v.getTitle() != null ? v.resolveTitle(lang) : null);
        dto.setTitleI18n(v.getTitle());
        dto.setDescription(v.getDescription() != null ? v.resolveDescription(lang) : null);
        dto.setDescriptionI18n(v.getDescription());
        dto.setType(v.getType());
        dto.setValue(v.getValue());
        dto.setMinOrderAmount(v.getMinOrderAmount());
        dto.setMaxDiscount(v.getMaxDiscount());
        dto.setTotalLimit(v.getTotalLimit());
        dto.setUsedCount(v.getUsedCount());
        dto.reservedCount = v.getReservedCount();
        dto.printable = v.isPrintable();
        dto.maxPrintRatio = v.getMaxPrintRatio();
        dto.maxPrintPerStore = v.getMaxPrintPerStore();
        dto.printedCount = v.getPrintedCount();
        dto.printedUnredeemed = v.getPrintedUnredeemed();
        int maxPrintable = v.computeMaxPrintableLimit();
        dto.maxPrintableLimit = maxPrintable;
        int poolRemaining = v.getTotalLimit() != null && v.getTotalLimit() > 0
                ? Math.max(0, v.getTotalLimit() - (v.getUsedCount() != null ? v.getUsedCount() : 0) - v.getReservedCount() - v.getPrintedUnredeemed())
                : 0;
        dto.remainingPrintableQuota = v.isPrintable()
                ? Math.max(0, Math.min(maxPrintable - v.getPrintedCount(), poolRemaining))
                : 0;
        dto.version = v.getVersion();
        dto.setStartAt(v.getStartAt());
        dto.setEndAt(v.getEndAt());
        dto.setScope(v.getScope());
        dto.setStoreId(v.getStoreId());
        dto.setStatus(v.getStatus());
        dto.setEffectiveStatus(v.computeEffectiveStatus(now));
        dto.setCreatedBy(v.getCreatedBy());
        dto.setApprovedBy(v.getApprovedBy());
        dto.rejectionReason = v.getRejectionReason();
        dto.rejectedBy = v.getRejectedBy();
        dto.rejectedAt = v.getRejectedAt();
        dto.setCreatedAt(v.getCreatedAt());
        dto.setUpdatedAt(v.getUpdatedAt());
        return dto;
    }

    /** Public cards and customer wallet never expose campaign editorial/audit actor identities. */
    public static VoucherResponse fromPublicEntity(Voucher v, Instant now) {
        return fromPublicEntity(v, now, LocaleResolverHelper.currentRequestLang());
    }

    public static VoucherResponse fromPublicEntity(Voucher v, Instant now, String lang) {
        VoucherResponse dto = fromEntity(v, now, lang);
        if (dto != null) {
            dto.createdBy = null;
            dto.approvedBy = null;
            dto.rejectedBy = null;
            dto.rejectedAt = null;
            dto.rejectionReason = null;
            dto.code = v.getCampaignKind() == com.clucky.promotionloyaltyservice.model.VoucherCampaignKind.PERSONAL
                    ? null : dto.code;
            dto.printable = false;
            dto.maxPrintRatio = null;
            dto.maxPrintPerStore = null;
            dto.printedCount = 0;
            dto.printedUnredeemed = 0;
            dto.maxPrintableLimit = 0;
            dto.remainingPrintableQuota = 0;
        }
        return dto;
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
    public VoucherApplicability getApplicability() { return applicability; }
    public Set<Long> getStoreIds() { return storeIds; }
    public Integer getUsage() { return usage; }
    public Integer getMaxUsage() { return maxUsage; }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalizedText getTitleI18n() {
        return titleI18n;
    }

    public void setTitleI18n(LocalizedText titleI18n) {
        this.titleI18n = titleI18n;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalizedText getDescriptionI18n() {
        return descriptionI18n;
    }

    public void setDescriptionI18n(LocalizedText descriptionI18n) {
        this.descriptionI18n = descriptionI18n;
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

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public VoucherStatus getStatus() {
        return status;
    }

    public void setStatus(VoucherStatus status) {
        this.status = status;
    }

    public VoucherStatus getEffectiveStatus() {
        return effectiveStatus;
    }

    public void setEffectiveStatus(VoucherStatus effectiveStatus) {
        this.effectiveStatus = effectiveStatus;
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
