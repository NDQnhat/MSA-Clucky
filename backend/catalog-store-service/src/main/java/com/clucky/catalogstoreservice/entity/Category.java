package com.clucky.catalogstoreservice.entity;

import com.clucky.catalogstoreservice.dto.LocalizedText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    public long getVersion() { return version; }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private LocalizedText name;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    public Category() {}

    public Category(Long id, LocalizedText name, String imageUrl, Integer sortOrder, Boolean isActive) {
        this.id = id;
        this.name = name;
        this.imageUrl = imageUrl;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
        this.isActive = isActive != null ? isActive : true;
    }

    public Category(Long id, String name, String imageUrl, Integer sortOrder, Boolean isActive) {
        this(id, LocalizedText.ofSingle(name), imageUrl, sortOrder, isActive);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalizedText getName() { return name; }
    public void setName(LocalizedText name) { this.name = name; }
    public void setName(String name) { this.name = LocalizedText.ofSingle(name); }
    public String resolveName(String lang) { return LocalizedText.resolve(name, lang); }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
