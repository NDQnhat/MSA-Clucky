package com.clucky.catalogstoreservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public class CategoryDto {
    public record OrderRequest(@NotNull List<@NotNull @Valid OrderItem> items) {}

    public record OrderItem(@NotNull @Positive Long id,
                            @NotNull @PositiveOrZero Long expectedVersion) {}

    public static class Response {
        private long version;
        public long getVersion() { return version; }
        public void setVersion(long version) { this.version = version; }
        private Long id;
        private String name;
        private LocalizedText nameI18n;
        private String imageUrl;
        private Integer sortOrder;
        private Boolean isActive;

        public Response() {}

        public Response(Long id, String name, String imageUrl, Integer sortOrder, Boolean isActive) {
            this(id, name, LocalizedText.ofSingle(name), imageUrl, sortOrder, isActive);
        }

        public Response(Long id, String name, LocalizedText nameI18n, String imageUrl, Integer sortOrder, Boolean isActive) {
            this.id = id;
            this.name = name;
            this.nameI18n = nameI18n;
            this.imageUrl = imageUrl;
            this.sortOrder = sortOrder;
            this.isActive = isActive;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) { this.nameI18n = nameI18n; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    }

    public static class Request {
        @PositiveOrZero
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

        @ValidLocalizedText(required = true, max = 120, message = "Category name is required and must be at most 120 characters")
        private LocalizedText name;
        private LocalizedText nameI18n;
        private String imageUrl;
        @PositiveOrZero
        private Integer sortOrder = 0;
        private Boolean isActive = true;

        public LocalizedText getName() { return name != null ? name : nameI18n; }
        public void setName(LocalizedText name) { this.name = name; }
        @JsonIgnore
        public void setName(String name) { this.name = LocalizedText.ofSingle(name); }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) {
            this.nameI18n = nameI18n;
            if (this.name == null) this.name = nameI18n;
        }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    }
}
