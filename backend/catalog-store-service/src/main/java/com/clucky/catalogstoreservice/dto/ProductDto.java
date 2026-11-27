package com.clucky.catalogstoreservice.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class ProductDto {

    private static LocalizedText parseLocalizedJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return LocalizedText.ofSingle(node.asText());
        if (node.isObject()) {
            String vi = node.has("vi") && !node.get("vi").isNull() ? node.get("vi").asText() : null;
            String en = node.has("en") && !node.get("en").isNull() ? node.get("en").asText() : null;
            return new LocalizedText(vi != null ? vi.trim() : "", en != null ? en.trim() : "");
        }
        return null;
    }

    public static class OptionChoiceDto {
        private String id;
        private String name;
        private LocalizedText nameI18n;
        @NotNull(message = "Price adjustment is required")
        @DecimalMin(value = "0.0", message = "Price adjustment must be non-negative")
        @Digits(integer = 12, fraction = 0)
        private BigDecimal priceAdjustment = BigDecimal.ZERO;

        public OptionChoiceDto() {}

        public OptionChoiceDto(String id, String name, BigDecimal priceAdjustment) {
            this(id, name, LocalizedText.ofSingle(name), priceAdjustment);
        }

        public OptionChoiceDto(String id, String name, LocalizedText nameI18n, BigDecimal priceAdjustment) {
            this.id = id;
            this.name = name;
            this.nameI18n = nameI18n;
            this.priceAdjustment = priceAdjustment != null ? priceAdjustment : BigDecimal.ZERO;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        @JsonGetter("name")
        public String getName() { return name; }

        @JsonSetter("name")
        public void setNameFromJson(JsonNode node) {
            LocalizedText parsed = parseLocalizedJson(node);
            if (parsed != null) {
                if (this.nameI18n == null) this.nameI18n = parsed;
                this.name = parsed.resolve("vi");
            } else {
                this.name = null;
            }
        }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) {
            this.nameI18n = nameI18n;
            if (this.name == null && nameI18n != null) {
                this.name = nameI18n.resolve("vi");
            }
        }

        @JsonIgnore
        public LocalizedText effectiveName() {
            return nameI18n != null && nameI18n.hasText() ? nameI18n : LocalizedText.ofSingle(name);
        }

        @JsonIgnore
        @AssertTrue(message = "Option choice name is required and must be at most 100 characters")
        public boolean isNameValid() {
            LocalizedText effective = effectiveName();
            if (effective == null || !effective.hasVi()) return false;
            return (effective.vi() == null || effective.vi().length() <= 100)
                    && (effective.en() == null || effective.en().length() <= 100);
        }

        public BigDecimal getPriceAdjustment() { return priceAdjustment; }
        public void setPriceAdjustment(BigDecimal priceAdjustment) { this.priceAdjustment = priceAdjustment; }
    }

    public static class OptionGroupDto {
        private String id;
        private String name;
        private LocalizedText nameI18n;
        private boolean required = true;
        @Valid
        @NotEmpty
        private List<@NotNull OptionChoiceDto> choices = new ArrayList<>();

        public OptionGroupDto() {}

        public OptionGroupDto(String id, String name, boolean required, List<OptionChoiceDto> choices) {
            this(id, name, LocalizedText.ofSingle(name), required, choices);
        }

        public OptionGroupDto(String id, String name, LocalizedText nameI18n, boolean required, List<OptionChoiceDto> choices) {
            this.id = id;
            this.name = name;
            this.nameI18n = nameI18n;
            this.required = required;
            this.choices = choices != null ? choices : new ArrayList<>();
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        @JsonGetter("name")
        public String getName() { return name; }

        @JsonSetter("name")
        public void setNameFromJson(JsonNode node) {
            LocalizedText parsed = parseLocalizedJson(node);
            if (parsed != null) {
                if (this.nameI18n == null) this.nameI18n = parsed;
                this.name = parsed.resolve("vi");
            } else {
                this.name = null;
            }
        }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) {
            this.nameI18n = nameI18n;
            if (this.name == null && nameI18n != null) {
                this.name = nameI18n.resolve("vi");
            }
        }

        @JsonIgnore
        public LocalizedText effectiveName() {
            return nameI18n != null && nameI18n.hasText() ? nameI18n : LocalizedText.ofSingle(name);
        }

        @JsonIgnore
        @AssertTrue(message = "Option group name is required and must be at most 80 characters")
        public boolean isNameValid() {
            LocalizedText effective = effectiveName();
            if (effective == null || !effective.hasVi()) return false;
            return (effective.vi() == null || effective.vi().length() <= 80)
                    && (effective.en() == null || effective.en().length() <= 80);
        }

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }

        public List<OptionChoiceDto> getChoices() { return choices; }
        public void setChoices(List<OptionChoiceDto> choices) { this.choices = choices; }
    }

    public static class ToppingDto {
        private String id;
        private String name;
        private LocalizedText nameI18n;
        @NotNull(message = "Topping price is required")
        @DecimalMin(value = "0.0", message = "Topping price must be non-negative")
        @Digits(integer = 12, fraction = 0)
        private BigDecimal price = BigDecimal.ZERO;

        public ToppingDto() {}

        public ToppingDto(String id, String name, BigDecimal price) {
            this(id, name, LocalizedText.ofSingle(name), price);
        }

        public ToppingDto(String id, String name, LocalizedText nameI18n, BigDecimal price) {
            this.id = id;
            this.name = name;
            this.nameI18n = nameI18n;
            this.price = price != null ? price : BigDecimal.ZERO;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        @JsonGetter("name")
        public String getName() { return name; }

        @JsonSetter("name")
        public void setNameFromJson(JsonNode node) {
            LocalizedText parsed = parseLocalizedJson(node);
            if (parsed != null) {
                if (this.nameI18n == null) this.nameI18n = parsed;
                this.name = parsed.resolve("vi");
            } else {
                this.name = null;
            }
        }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) {
            this.nameI18n = nameI18n;
            if (this.name == null && nameI18n != null) {
                this.name = nameI18n.resolve("vi");
            }
        }

        @JsonIgnore
        public LocalizedText effectiveName() {
            return nameI18n != null && nameI18n.hasText() ? nameI18n : LocalizedText.ofSingle(name);
        }

        @JsonIgnore
        @AssertTrue(message = "Topping name is required and must be at most 120 characters")
        public boolean isNameValid() {
            LocalizedText effective = effectiveName();
            if (effective == null || !effective.hasVi()) return false;
            return (effective.vi() == null || effective.vi().length() <= 120)
                    && (effective.en() == null || effective.en().length() <= 120);
        }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
    }

    public static class Response {
        private long version;
        public long getVersion() { return version; }
        public void setVersion(long version) { this.version = version; }
        private Long inventoryVersion;
        public Long getInventoryVersion() { return inventoryVersion; }
        public void setInventoryVersion(Long inventoryVersion) { this.inventoryVersion = inventoryVersion; }
        private Long id;
        private String slug;
        private String name;
        private LocalizedText nameI18n;
        private Long categoryId;
        private String categoryName;
        private LocalizedText categoryNameI18n;
        private String category;
        private String description;
        private LocalizedText descriptionI18n;
        private BigDecimal basePrice;
        private BigDecimal price;
        private String imageUrl;
        private String image;
        private String imagePublicId;
        private String alt;
        private Integer kcal;
        private Integer spicyLevel;
        private Boolean isActive;
        private Boolean isAvailable;
        private Boolean isSoldOut;
        private Boolean isBestSeller;
        private Double rating;
        private Integer reviewCount;
        private List<OptionGroupDto> options = new ArrayList<>();
        private List<ToppingDto> toppings = new ArrayList<>();

        public Response() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) { this.nameI18n = nameI18n; }

        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) {
            this.categoryName = categoryName;
            this.category = categoryName;
        }

        public LocalizedText getCategoryNameI18n() { return categoryNameI18n; }
        public void setCategoryNameI18n(LocalizedText categoryNameI18n) { this.categoryNameI18n = categoryNameI18n; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public LocalizedText getDescriptionI18n() { return descriptionI18n; }
        public void setDescriptionI18n(LocalizedText descriptionI18n) { this.descriptionI18n = descriptionI18n; }

        public BigDecimal getBasePrice() { return basePrice; }
        public void setBasePrice(BigDecimal basePrice) {
            this.basePrice = basePrice;
            this.price = basePrice;
        }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
            this.image = imageUrl;
        }

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }

        public String getImagePublicId() { return imagePublicId; }
        public void setImagePublicId(String imagePublicId) { this.imagePublicId = imagePublicId; }

        public String getAlt() { return alt; }
        public void setAlt(String alt) { this.alt = alt; }

        public Integer getKcal() { return kcal; }
        public void setKcal(Integer kcal) { this.kcal = kcal; }

        public Integer getSpicyLevel() { return spicyLevel; }
        public void setSpicyLevel(Integer spicyLevel) { this.spicyLevel = spicyLevel; }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }

        public Boolean getIsAvailable() { return isAvailable; }
        public void setIsAvailable(Boolean isAvailable) { this.isAvailable = isAvailable; }

        public Boolean getIsSoldOut() { return isSoldOut; }
        public void setIsSoldOut(Boolean isSoldOut) { this.isSoldOut = isSoldOut; }

        public Boolean getIsBestSeller() { return isBestSeller; }
        public void setIsBestSeller(Boolean isBestSeller) { this.isBestSeller = isBestSeller; }

        public Double getRating() { return rating; }
        public void setRating(Double rating) { this.rating = rating; }

        public Integer getReviewCount() { return reviewCount; }
        public void setReviewCount(Integer reviewCount) { this.reviewCount = reviewCount; }

        public List<OptionGroupDto> getOptions() { return options; }
        public void setOptions(List<OptionGroupDto> options) { this.options = options; }

        public List<ToppingDto> getToppings() { return toppings; }
        public void setToppings(List<ToppingDto> toppings) { this.toppings = toppings; }
    }

    public static class Request {
        @PositiveOrZero
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

        @ValidLocalizedText(required = true, max = 180, message = "Product name is required and must be at most 180 characters")
        private LocalizedText name;
        private LocalizedText nameI18n;

        @NotNull(message = "Category ID is required")
        @Positive
        private Long categoryId;

        @ValidLocalizedText(required = true, message = "Product description is required")
        private LocalizedText description;
        private LocalizedText descriptionI18n;

        @NotNull(message = "Base price is required")
        @DecimalMin(value = "0.0")
        @Digits(integer = 12, fraction = 0)
        private BigDecimal basePrice;

        private String imageUrl;
        private String imagePublicId;
        @Min(0)
        private Integer kcal;
        @Min(0)
        @Max(3)
        private Integer spicyLevel;
        private Boolean isActive;

        @Valid
        private List<@NotNull OptionGroupDto> options;
        @Valid
        private List<@NotNull ToppingDto> toppings;

        public LocalizedText getName() { return name != null ? name : nameI18n; }
        public void setName(LocalizedText name) { this.name = name; }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) {
            this.nameI18n = nameI18n;
            if (this.name == null) this.name = nameI18n;
        }

        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

        public LocalizedText getDescription() { return description != null ? description : descriptionI18n; }
        public void setDescription(LocalizedText description) { this.description = description; }

        public LocalizedText getDescriptionI18n() { return descriptionI18n; }
        public void setDescriptionI18n(LocalizedText descriptionI18n) {
            this.descriptionI18n = descriptionI18n;
            if (this.description == null) this.description = descriptionI18n;
        }

        public BigDecimal getBasePrice() { return basePrice; }
        public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public String getImagePublicId() { return imagePublicId; }
        public void setImagePublicId(String imagePublicId) { this.imagePublicId = imagePublicId; }

        public Integer getKcal() { return kcal; }
        public void setKcal(Integer kcal) { this.kcal = kcal; }

        public Integer getSpicyLevel() { return spicyLevel; }
        public void setSpicyLevel(Integer spicyLevel) { this.spicyLevel = spicyLevel; }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }

        public List<OptionGroupDto> getOptions() { return options; }
        public void setOptions(List<OptionGroupDto> options) { this.options = options; }

        public List<ToppingDto> getToppings() { return toppings; }
        public void setToppings(List<ToppingDto> toppings) { this.toppings = toppings; }
    }

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    public static String toSlug(String input) {
        if (input == null) return "";
        String replaced = input.replace('đ', 'd').replace('Đ', 'D');
        String nowhitespace = WHITESPACE.matcher(replaced).replaceAll("-");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        return slug.toLowerCase(Locale.ENGLISH).replaceAll("-+", "-").replaceAll("^-|-$", "");
    }
}
