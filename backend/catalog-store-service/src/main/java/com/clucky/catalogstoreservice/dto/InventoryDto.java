package com.clucky.catalogstoreservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

public class InventoryDto {

    public static class AvailabilityUpdateRequest {
        @Min(0)
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String field, Object value) { throw new IllegalArgumentException("Unsupported field"); }
        private Boolean isAvailable;
        private Boolean isSoldOut;
        @DecimalMin(value = "0.0")
        @Digits(integer = 12, fraction = 0)
        private BigDecimal storePrice;
        private Boolean clearStorePrice;

        public Boolean getIsAvailable() { return isAvailable; }
        public void setIsAvailable(Boolean isAvailable) { this.isAvailable = isAvailable; }

        public Boolean getIsSoldOut() { return isSoldOut; }
        public void setIsSoldOut(Boolean isSoldOut) { this.isSoldOut = isSoldOut; }

        public BigDecimal getStorePrice() { return storePrice; }
        public void setStorePrice(BigDecimal storePrice) { this.storePrice = storePrice; }

        public Boolean getClearStorePrice() { return clearStorePrice; }
        public void setClearStorePrice(Boolean clearStorePrice) { this.clearStorePrice = clearStorePrice; }
    }

    public static class InventoryResponse {
        private Long version;
        private Long availabilityVersion;
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public Long getAvailabilityVersion() { return availabilityVersion; }
        public void setAvailabilityVersion(Long availabilityVersion) { this.availabilityVersion = availabilityVersion; }
        private Long storeId;
        private Long productId;
        private String productName;
        private LocalizedText productNameI18n;
        private String categoryName;
        private LocalizedText categoryNameI18n;
        private String imageUrl;
        private Integer quantityAvailable;
        private Integer lowStockThreshold;
        private Boolean isSoldOut;
        private BigDecimal basePrice;
        private BigDecimal storePrice;
        private BigDecimal effectivePrice;
        private String updatedAt;
        private String updatedBy;

        public InventoryResponse() {}

        public InventoryResponse(Long storeId, Long productId, String productName, String categoryName, String imageUrl, Integer quantityAvailable, Integer lowStockThreshold, Boolean isSoldOut, String updatedAt, String updatedBy) {
            this.storeId = storeId;
            this.productId = productId;
            this.productName = productName;
            this.categoryName = categoryName;
            this.imageUrl = imageUrl;
            this.quantityAvailable = quantityAvailable;
            this.lowStockThreshold = lowStockThreshold;
            this.isSoldOut = isSoldOut;
            this.updatedAt = updatedAt;
            this.updatedBy = updatedBy;
        }

        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }

        public LocalizedText getProductNameI18n() { return productNameI18n; }
        public void setProductNameI18n(LocalizedText productNameI18n) { this.productNameI18n = productNameI18n; }

        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

        public LocalizedText getCategoryNameI18n() { return categoryNameI18n; }
        public void setCategoryNameI18n(LocalizedText categoryNameI18n) { this.categoryNameI18n = categoryNameI18n; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public Integer getQuantityAvailable() { return quantityAvailable; }
        public void setQuantityAvailable(Integer quantityAvailable) { this.quantityAvailable = quantityAvailable; }

        public Integer getLowStockThreshold() { return lowStockThreshold; }
        public void setLowStockThreshold(Integer lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }

        public Boolean getIsSoldOut() { return isSoldOut; }
        public void setIsSoldOut(Boolean isSoldOut) { this.isSoldOut = isSoldOut; }

        public BigDecimal getBasePrice() { return basePrice; }
        public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }

        public BigDecimal getStorePrice() { return storePrice; }
        public void setStorePrice(BigDecimal storePrice) { this.storePrice = storePrice; }

        public BigDecimal getEffectivePrice() { return effectivePrice; }
        public void setEffectivePrice(BigDecimal effectivePrice) { this.effectivePrice = effectivePrice; }

        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

        public String getUpdatedBy() { return updatedBy; }
        public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    }

    public static class InventoryUpdateRequest {
        @Min(0)
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String field, Object value) { throw new IllegalArgumentException("Unsupported field"); }
        @Min(0)
        private Integer quantityAvailable;
        @Min(0)
        private Integer lowStockThreshold;
        private Boolean isSoldOut;

        public Integer getQuantityAvailable() { return quantityAvailable; }
        public void setQuantityAvailable(Integer quantityAvailable) { this.quantityAvailable = quantityAvailable; }

        public Integer getLowStockThreshold() { return lowStockThreshold; }
        public void setLowStockThreshold(Integer lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }

        public Boolean getIsSoldOut() { return isSoldOut; }
        public void setIsSoldOut(Boolean isSoldOut) { this.isSoldOut = isSoldOut; }
    }
}
