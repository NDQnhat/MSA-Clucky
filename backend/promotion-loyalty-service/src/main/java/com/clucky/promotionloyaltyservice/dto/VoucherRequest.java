package com.clucky.promotionloyaltyservice.dto;

import com.clucky.promotionloyaltyservice.model.VoucherScope;
import com.clucky.promotionloyaltyservice.model.VoucherApplicability;
import com.clucky.promotionloyaltyservice.model.VoucherStatus;
import com.clucky.promotionloyaltyservice.model.VoucherType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public class VoucherRequest {

    @PositiveOrZero
    private Long version;

    public Long getVersion() { return version; }

    public void setVersion(Long version) { this.version = version; }

    @Size(max = 80, message = "Mã voucher tối đa 80 ký tự")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Mã voucher chỉ chứa chữ cái, số, gạch dưới hoặc gạch ngang")
    private String code;

    private VoucherApplicability applicability = VoucherApplicability.ALL;
    private Set<@Positive Long> storeIds = Set.of();

    @ValidLocalizedText(required = false, max = 255, message = "Title must be at most 255 characters")
    private LocalizedText title;
    private LocalizedText titleI18n;

    private LocalizedText description;
    private LocalizedText descriptionI18n;

    @NotNull(message = "Loại voucher (PERCENT hoặc AMOUNT) không được để trống")
    private VoucherType type;

    @NotNull(message = "Giá trị giảm không được để trống")
    @DecimalMin(value = "1.0", message = "Giá trị giảm phải lớn hơn 0")
    @Digits(integer = 12, fraction = 0)
    private BigDecimal value;

    @DecimalMin(value = "0.0", message = "Giá trị đơn hàng tối thiểu không được âm")
    @Digits(integer = 12, fraction = 0)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @DecimalMin(value = "0.0", message = "Mức giảm tối đa không được âm")
    @Digits(integer = 12, fraction = 0)
    private BigDecimal maxDiscount;

    @Min(value = 1, message = "Giới hạn số lượt dùng tối thiểu là 1")
    private Integer totalLimit;

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    private Instant startAt;

    @NotNull(message = "Thời gian kết thúc không được để trống")
    private Instant endAt;

    private VoucherScope scope = VoucherScope.ALL;

    @Positive
    private Long storeId;

    private VoucherStatus status = VoucherStatus.ACTIVE;

    private Boolean printable = Boolean.FALSE;

    @DecimalMin(value = "0.01", message = "Tỷ lệ in tối đa phải lớn hơn 0")
    @DecimalMax(value = "1.0000", message = "Tỷ lệ in tối đa không được vượt quá 100%")
    @Digits(integer = 1, fraction = 4)
    private BigDecimal maxPrintRatio;

    @Min(value = 1, message = "Giới hạn in mỗi chi nhánh tối thiểu là 1")
    private Integer maxPrintPerStore;

    public VoucherRequest() {
    }

    public String getCode() {
        return code;
    }

    public VoucherApplicability getApplicability() { return applicability; }
    public void setApplicability(VoucherApplicability applicability) { this.applicability = applicability; }
    public Set<Long> getStoreIds() { return storeIds; }
    public void setStoreIds(Set<Long> storeIds) { this.storeIds = storeIds; }

    public void setCode(String code) {
        this.code = code;
    }

    public LocalizedText getTitle() {
        return title != null ? title : titleI18n;
    }

    public void setTitle(LocalizedText title) {
        this.title = title;
    }

    public LocalizedText getTitleI18n() {
        return titleI18n;
    }

    public void setTitleI18n(LocalizedText titleI18n) {
        this.titleI18n = titleI18n;
        if (this.title == null) this.title = titleI18n;
    }

    public LocalizedText getDescription() {
        return description != null ? description : descriptionI18n;
    }

    public void setDescription(LocalizedText description) {
        this.description = description;
    }

    public LocalizedText getDescriptionI18n() {
        return descriptionI18n;
    }

    public void setDescriptionI18n(LocalizedText descriptionI18n) {
        this.descriptionI18n = descriptionI18n;
        if (this.description == null) this.description = descriptionI18n;
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

    public Boolean getPrintable() {
        return printable;
    }

    public void setPrintable(Boolean printable) {
        this.printable = printable;
    }

    public BigDecimal getMaxPrintRatio() {
        return maxPrintRatio;
    }

    public void setMaxPrintRatio(BigDecimal maxPrintRatio) {
        this.maxPrintRatio = maxPrintRatio;
    }

    public Integer getMaxPrintPerStore() {
        return maxPrintPerStore;
    }

    public void setMaxPrintPerStore(Integer maxPrintPerStore) {
        this.maxPrintPerStore = maxPrintPerStore;
    }
}

