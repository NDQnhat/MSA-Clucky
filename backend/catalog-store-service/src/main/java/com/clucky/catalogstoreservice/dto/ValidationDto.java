package com.clucky.catalogstoreservice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ValidationDto {
    public record OptionSnapshot(Long id, String groupName, String name, BigDecimal extraPrice,
                                 LocalizedText groupNameI18n, LocalizedText nameI18n) {
        public OptionSnapshot(Long id, String groupName, String name, BigDecimal extraPrice) {
            this(id, groupName, name, extraPrice, LocalizedText.ofSingle(groupName), LocalizedText.ofSingle(name));
        }
    }

    public record ToppingSnapshot(Long id, String name, BigDecimal extraPrice, LocalizedText nameI18n) {
        public ToppingSnapshot(Long id, String name, BigDecimal extraPrice) {
            this(id, name, extraPrice, LocalizedText.ofSingle(name));
        }
    }

    public record ComboOptionSnapshot(Long comboItemId, Long productId, String productName, Long id,
                                      String groupName, String name, BigDecimal extraPrice, BigDecimal unitExtraPrice,
                                      Integer componentQuantity, boolean fixed,
                                      LocalizedText productNameI18n, LocalizedText groupNameI18n, LocalizedText nameI18n) {
        public ComboOptionSnapshot(Long comboItemId, Long productId, String productName, Long id,
                                   String groupName, String name, BigDecimal extraPrice, BigDecimal unitExtraPrice,
                                   Integer componentQuantity, boolean fixed) {
            this(comboItemId, productId, productName, id, groupName, name, extraPrice, unitExtraPrice, componentQuantity, fixed,
                    LocalizedText.ofSingle(productName), LocalizedText.ofSingle(groupName), LocalizedText.ofSingle(name));
        }
    }

    public record ComboToppingSnapshot(Long comboItemId, Long productId, String productName, Long id,
                                       String name, BigDecimal extraPrice, BigDecimal unitExtraPrice,
                                       Integer componentQuantity,
                                       LocalizedText productNameI18n, LocalizedText nameI18n) {
        public ComboToppingSnapshot(Long comboItemId, Long productId, String productName, Long id,
                                    String name, BigDecimal extraPrice, BigDecimal unitExtraPrice,
                                    Integer componentQuantity) {
            this(comboItemId, productId, productName, id, name, extraPrice, unitExtraPrice, componentQuantity,
                    LocalizedText.ofSingle(productName), LocalizedText.ofSingle(name));
        }
    }

    public record ComboSelectionRequest(Long comboItemId, List<Long> optionIds, List<Long> toppingIds) {}

    public record ReserveStockRequest(
            @NotNull @Positive Long orderId,
            @NotNull @Positive Long storeId,
            @NotEmpty Map<@NotNull @Positive Long, @NotNull @Positive Integer> stockQuantities,
            Map<Long, Long> expectedVersions
    ) {}

    public record ReleaseStockRequest(
            @NotNull @Positive Long orderId,
            @NotNull @Positive Long storeId
    ) {}

    public static class ValidateItemRequest {
        private Long productId;
        private Long comboId;
        private Integer quantity = 1;
        private List<Long> optionIds = new ArrayList<>();
        private List<Long> toppingIds = new ArrayList<>();
        private List<ComboSelectionRequest> comboSelections = new ArrayList<>();
        private Long expectedVersion;

        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public Long getComboId() { return comboId; }
        public void setComboId(Long comboId) { this.comboId = comboId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public List<Long> getOptionIds() { return optionIds; }
        public void setOptionIds(List<Long> optionIds) { this.optionIds = optionIds; }

        public List<Long> getToppingIds() { return toppingIds; }
        public void setToppingIds(List<Long> toppingIds) { this.toppingIds = toppingIds; }

        public List<ComboSelectionRequest> getComboSelections() { return comboSelections; }
        public void setComboSelections(List<ComboSelectionRequest> comboSelections) {
            this.comboSelections = comboSelections != null ? comboSelections : new ArrayList<>();
        }
    }

    public static class Request {
        private Long storeId;
        private List<ValidateItemRequest> items = new ArrayList<>();

        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }

        public List<ValidateItemRequest> getItems() { return items; }
        public void setItems(List<ValidateItemRequest> items) { this.items = items; }
    }

    public static class ValidatedItem {
        private final List<OptionSnapshot> optionsSnapshot = new ArrayList<>();
        private final List<ToppingSnapshot> toppingsSnapshot = new ArrayList<>();
        private final List<ComboOptionSnapshot> comboOptionsSnapshot = new ArrayList<>();
        private final List<ComboToppingSnapshot> comboToppingsSnapshot = new ArrayList<>();

        public List<OptionSnapshot> getOptionsSnapshot() { return optionsSnapshot; }
        public List<ToppingSnapshot> getToppingsSnapshot() { return toppingsSnapshot; }
        public List<ComboOptionSnapshot> getComboOptionsSnapshot() { return comboOptionsSnapshot; }
        public List<ComboToppingSnapshot> getComboToppingsSnapshot() { return comboToppingsSnapshot; }
        private Long productId;
        private Long comboId;
        private String name;
        private LocalizedText nameI18n;
        private BigDecimal unitPrice;
        private BigDecimal extraPrice = BigDecimal.ZERO;
        private Integer quantity;
        private BigDecimal lineTotal;
        private boolean available = true;
        private String reason;
        private String errorCode;
        private boolean insufficientStock;
        private Long inventoryVersion;

        public Long getInventoryVersion() { return inventoryVersion; }
        public void setInventoryVersion(Long inventoryVersion) { this.inventoryVersion = inventoryVersion; }

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public Long getComboId() { return comboId; }
        public void setComboId(Long comboId) { this.comboId = comboId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) { this.nameI18n = nameI18n; }

        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

        public BigDecimal getExtraPrice() { return extraPrice; }
        public void setExtraPrice(BigDecimal extraPrice) { this.extraPrice = extraPrice; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public BigDecimal getLineTotal() { return lineTotal; }
        public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

        public boolean isInsufficientStock() { return insufficientStock; }
        public void setInsufficientStock(boolean insufficientStock) { this.insufficientStock = insufficientStock; }
    }

    public static class Response {
        private boolean valid = true;
        private boolean storeOpen = true;
        private String message;
        private BigDecimal subtotal = BigDecimal.ZERO;
        private List<ValidatedItem> items = new ArrayList<>();
        private Map<Long, Long> stockQuantities = Map.of();

        public Map<Long, Long> getStockQuantities() { return stockQuantities; }
        public void setStockQuantities(Map<Long, Long> quantities) { stockQuantities = Map.copyOf(quantities); }

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }

        public boolean isStoreOpen() { return storeOpen; }
        public void setStoreOpen(boolean storeOpen) { this.storeOpen = storeOpen; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public BigDecimal getSubtotal() { return subtotal; }
        public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

        public List<ValidatedItem> getItems() { return items; }
        public void setItems(List<ValidatedItem> items) { this.items = items; }
    }
}
