package com.clucky.reviewreportservice.entity;

import com.clucky.reviewreportservice.dto.LocalizedText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
    name = "daily_item_stats",
    schema = "review_report_service",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_daily_item_stats", columnNames = {"store_id", "stat_date", "item_type", "item_ref_id"})
    }
)
public class DailyItemStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType;

    @Column(name = "item_ref_id", nullable = false)
    private Long itemRefId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_name_i18n", nullable = false, columnDefinition = "jsonb")
    private LocalizedText itemNameI18n;

    @Column(name = "quantity_sold", nullable = false)
    private Integer quantitySold = 0;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount = 0;

    @Column(name = "gross_revenue", nullable = false, precision = 12, scale = 0)
    private BigDecimal grossRevenue = BigDecimal.ZERO;

    @Column(name = "allocated_net_revenue", nullable = false, precision = 12, scale = 0)
    private BigDecimal allocatedNetRevenue = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getStoreId() { return storeId; }
    public LocalDate getStatDate() { return statDate; }
    public String getItemType() { return itemType; }
    public Long getItemRefId() { return itemRefId; }
    public LocalizedText getItemNameI18n() { return itemNameI18n; }
    public Integer getQuantitySold() { return quantitySold; }
    public Integer getOrderCount() { return orderCount; }
    public BigDecimal getGrossRevenue() { return grossRevenue; }
    public BigDecimal getAllocatedNetRevenue() { return allocatedNetRevenue; }
    public Instant getUpdatedAt() { return updatedAt; }
}
