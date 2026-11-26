package com.clucky.catalogstoreservice.entity;

import com.clucky.catalogstoreservice.dto.LocalizedText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;

@Entity
@Table(name = "product_toppings")
public class ProductTopping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "topping_name", nullable = false, columnDefinition = "jsonb")
    private LocalizedText toppingName;

    @Column(name = "extra_price", nullable = false, precision = 12, scale = 0)
    private BigDecimal extraPrice = BigDecimal.ZERO;

    public ProductTopping() {}

    public ProductTopping(Long id, Product product, LocalizedText toppingName, BigDecimal extraPrice) {
        this.id = id;
        this.product = product;
        this.toppingName = toppingName;
        this.extraPrice = extraPrice != null ? extraPrice : BigDecimal.ZERO;
    }

    public ProductTopping(Long id, Product product, String toppingName, BigDecimal extraPrice) {
        this(id, product, LocalizedText.ofSingle(toppingName), extraPrice);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public LocalizedText getToppingName() { return toppingName; }
    public void setToppingName(LocalizedText toppingName) { this.toppingName = toppingName; }
    public void setToppingName(String toppingName) { this.toppingName = LocalizedText.ofSingle(toppingName); }
    public String resolveToppingName(String lang) { return LocalizedText.resolve(toppingName, lang); }

    public BigDecimal getExtraPrice() { return extraPrice; }
    public void setExtraPrice(BigDecimal extraPrice) { this.extraPrice = extraPrice; }
}
