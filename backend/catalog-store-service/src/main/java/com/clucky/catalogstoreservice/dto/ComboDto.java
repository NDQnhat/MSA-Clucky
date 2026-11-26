package com.clucky.catalogstoreservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class ComboDto {

    public static class ItemDto {
        @Positive
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        @NotNull
        @Positive
        private Long productId;
        private String productName;
        private LocalizedText productNameI18n;
        @NotNull
        @Min(1)
        private Integer quantity = 1;
        @Positive
        private Long optionId;

        public ItemDto() {}

        public ItemDto(Long productId, String productName, Integer quantity, Long optionId) {
            this(productId, productName, LocalizedText.ofSingle(productName), quantity, optionId);
        }

        public ItemDto(Long productId, String productName, LocalizedText productNameI18n, Integer quantity, Long optionId) {
            this.productId = productId;
            this.productName = productName;
            this.productNameI18n = productNameI18n;
            this.quantity = quantity != null ? quantity : 1;
            this.optionId = optionId;
        }

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }

        public LocalizedText getProductNameI18n() { return productNameI18n; }
        public void setProductNameI18n(LocalizedText productNameI18n) { this.productNameI18n = productNameI18n; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public Long getOptionId() { return optionId; }
        public void setOptionId(Long optionId) { this.optionId = optionId; }
    }

    public static class Response {
        private long version;
        public long getVersion() { return version; }
        public void setVersion(long version) { this.version = version; }
        private Long id;
        private String name;
        private LocalizedText nameI18n;
        private String slug;
        private String description;
        private LocalizedText descriptionI18n;
        private BigDecimal comboPrice;
        private String imageUrl;
        private String imagePublicId;
        private String startAt;
        private String endAt;
        private Boolean isActive;
        private Boolean isAvailable;
        private Boolean isSoldOut;
        private List<ItemDto> items = new ArrayList<>();

        public Response() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) { this.nameI18n = nameI18n; }

        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public LocalizedText getDescriptionI18n() { return descriptionI18n; }
        public void setDescriptionI18n(LocalizedText descriptionI18n) { this.descriptionI18n = descriptionI18n; }

        public BigDecimal getComboPrice() { return comboPrice; }
        public void setComboPrice(BigDecimal comboPrice) { this.comboPrice = comboPrice; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public String getImagePublicId() { return imagePublicId; }
        public void setImagePublicId(String imagePublicId) { this.imagePublicId = imagePublicId; }

        public String getStartAt() { return startAt; }
        public void setStartAt(String startAt) { this.startAt = startAt; }

        public String getEndAt() { return endAt; }
        public void setEndAt(String endAt) { this.endAt = endAt; }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }

        public Boolean getIsAvailable() { return isAvailable; }
        public void setIsAvailable(Boolean isAvailable) { this.isAvailable = isAvailable; }

        public Boolean getIsSoldOut() { return isSoldOut; }
        public void setIsSoldOut(Boolean isSoldOut) { this.isSoldOut = isSoldOut; }

        public List<ItemDto> getItems() { return items; }
        public void setItems(List<ItemDto> items) { this.items = items; }
    }

    public static class Request {
        @PositiveOrZero
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

        @ValidLocalizedText(required = true, max = 180, message = "Combo name is required and must be at most 180 characters")
        private LocalizedText name;
        private LocalizedText nameI18n;

        @ValidLocalizedText(required = true, message = "Combo description is required")
        private LocalizedText description;
        private LocalizedText descriptionI18n;

        @NotNull(message = "Combo price is required")
        @DecimalMin(value = "0.0", message = "Combo price must be non-negative")
        @Digits(integer = 12, fraction = 0)
        private BigDecimal comboPrice;

        private String imageUrl;
        private String imagePublicId;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private boolean startAtSpecified;
        private boolean endAtSpecified;
        @JsonIgnore
        public boolean isStartAtSpecified() { return startAtSpecified; }
        @JsonIgnore
        public boolean isEndAtSpecified() { return endAtSpecified; }
        private Boolean isActive;
        @Valid
        private List<@NotNull ItemDto> items;

        public LocalizedText getName() { return name != null ? name : nameI18n; }
        public void setName(LocalizedText name) { this.name = name; }
        @JsonIgnore
        public void setName(String name) { this.name = LocalizedText.ofSingle(name); }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) {
            this.nameI18n = nameI18n;
            if (this.name == null) this.name = nameI18n;
        }

        public LocalizedText getDescription() { return description != null ? description : descriptionI18n; }
        public void setDescription(LocalizedText description) { this.description = description; }
        @JsonIgnore
        public void setDescription(String description) { this.description = LocalizedText.ofSingle(description); }

        public LocalizedText getDescriptionI18n() { return descriptionI18n; }
        public void setDescriptionI18n(LocalizedText descriptionI18n) {
            this.descriptionI18n = descriptionI18n;
            if (this.description == null) this.description = descriptionI18n;
        }

        public BigDecimal getComboPrice() { return comboPrice; }
        public void setComboPrice(BigDecimal comboPrice) { this.comboPrice = comboPrice; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public String getImagePublicId() { return imagePublicId; }
        public void setImagePublicId(String imagePublicId) { this.imagePublicId = imagePublicId; }

        public OffsetDateTime getStartAt() { return startAt; }
        public void setStartAt(OffsetDateTime startAt) { this.startAt = startAt; this.startAtSpecified = true; }

        public OffsetDateTime getEndAt() { return endAt; }
        public void setEndAt(OffsetDateTime endAt) { this.endAt = endAt; this.endAtSpecified = true; }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }

        public List<ItemDto> getItems() { return items; }
        public void setItems(List<ItemDto> items) { this.items = items; }
    }
}
