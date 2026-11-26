package com.clucky.catalogstoreservice.entity;

import com.clucky.catalogstoreservice.dto.LocalizedText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;

@Entity
@Table(name = "product_options")
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "group_name", nullable = false, columnDefinition = "jsonb")
    private LocalizedText groupName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private LocalizedText name;

    @Column(name = "extra_price", nullable = false, precision = 12, scale = 0)
    private BigDecimal extraPrice = BigDecimal.ZERO;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;

    public ProductOption() {}

    public ProductOption(Long id, Product product, LocalizedText groupName, LocalizedText name, BigDecimal extraPrice) {
        this(id, product, groupName, name, extraPrice, true);
    }

    public ProductOption(Long id, Product product, LocalizedText groupName, LocalizedText name, BigDecimal extraPrice, Boolean isRequired) {
        this.id = id;
        this.product = product;
        this.groupName = groupName;
        this.name = name;
        this.extraPrice = extraPrice != null ? extraPrice : BigDecimal.ZERO;
        this.isRequired = isRequired != null ? isRequired : true;
    }

    public ProductOption(Long id, Product product, String groupName, String name, BigDecimal extraPrice) {
        this(id, product, LocalizedText.ofSingle(groupName), LocalizedText.ofSingle(name), extraPrice, true);
    }

    public ProductOption(Long id, Product product, String groupName, String name, BigDecimal extraPrice, Boolean isRequired) {
        this(id, product, LocalizedText.ofSingle(groupName), LocalizedText.ofSingle(name), extraPrice, isRequired);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public LocalizedText getGroupName() { return groupName; }
    public void setGroupName(LocalizedText groupName) { this.groupName = groupName; }
    public void setGroupName(String groupName) { this.groupName = LocalizedText.ofSingle(groupName); }
    public String resolveGroupName(String lang) { return LocalizedText.resolve(groupName, lang); }

    public LocalizedText getName() { return name; }
    public void setName(LocalizedText name) { this.name = name; }
    public void setName(String name) { this.name = LocalizedText.ofSingle(name); }
    public String resolveName(String lang) { return LocalizedText.resolve(name, lang); }

    public BigDecimal getExtraPrice() { return extraPrice; }
    public void setExtraPrice(BigDecimal extraPrice) { this.extraPrice = extraPrice; }

    public Boolean getIsRequired() { return isRequired; }
    public void setIsRequired(Boolean isRequired) { this.isRequired = isRequired != null ? isRequired : true; }
}
