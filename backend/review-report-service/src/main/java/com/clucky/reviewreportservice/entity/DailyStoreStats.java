package com.clucky.reviewreportservice.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
    name = "daily_store_stats",
    schema = "review_report_service",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_daily_store_stats", columnNames = {"store_id", "stat_date"})
    }
)
public class DailyStoreStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount = 0;

    @Column(name = "revenue", nullable = false, precision = 12, scale = 0)
    private BigDecimal revenue = BigDecimal.ZERO;

    @Column(name = "cod_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal codAmount = BigDecimal.ZERO;

    @Column(name = "transfer_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal transferAmount = BigDecimal.ZERO;

    @Column(name = "reconciled_transfer_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal reconciledTransferAmount = BigDecimal.ZERO;

    @Column(name = "pending_reconciliation_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal pendingReconciliationAmount = BigDecimal.ZERO;

    @Column(name = "excess_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal excessAmount = BigDecimal.ZERO;

    @Column(name = "expired_receipt_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal expiredReceiptAmount = BigDecimal.ZERO;

    @Column(name = "cancelled_order_count", nullable = false)
    private Integer cancelledOrderCount = 0;

    @Column(name = "cancelled_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal cancelledAmount = BigDecimal.ZERO;

    @Column(name = "total_discount_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalDiscountAmount = BigDecimal.ZERO;

    @Column(name = "total_points_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalPointsAmount = BigDecimal.ZERO;

    @Column(name = "delivery_order_count", nullable = false)
    private Integer deliveryOrderCount = 0;

    @Column(name = "pickup_order_count", nullable = false)
    private Integer pickupOrderCount = 0;

    @Column(name = "web_order_count", nullable = false)
    private Integer webOrderCount = 0;

    @Column(name = "counter_order_count", nullable = false)
    private Integer counterOrderCount = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public DailyStoreStats() {
    }

    public DailyStoreStats(Long storeId, LocalDate statDate) {
        this.storeId = storeId;
        this.statDate = statDate;
        this.orderCount = 0;
        this.revenue = BigDecimal.ZERO;
        this.codAmount = BigDecimal.ZERO;
        this.transferAmount = BigDecimal.ZERO;
        this.reconciledTransferAmount = BigDecimal.ZERO;
        this.pendingReconciliationAmount = BigDecimal.ZERO;
        this.excessAmount = BigDecimal.ZERO;
        this.expiredReceiptAmount = BigDecimal.ZERO;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Integer getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Integer orderCount) {
        this.orderCount = orderCount;
    }

    public BigDecimal getRevenue() {
        return revenue;
    }

    public void setRevenue(BigDecimal revenue) {
        this.revenue = revenue;
    }

    public BigDecimal getCodAmount() {
        return codAmount;
    }

    public void setCodAmount(BigDecimal codAmount) {
        this.codAmount = codAmount;
    }

    public BigDecimal getTransferAmount() {
        return transferAmount;
    }

    public void setTransferAmount(BigDecimal transferAmount) {
        this.transferAmount = transferAmount;
    }

    public BigDecimal getReconciledTransferAmount() {
        return reconciledTransferAmount;
    }

    public void setReconciledTransferAmount(BigDecimal reconciledTransferAmount) {
        this.reconciledTransferAmount = reconciledTransferAmount;
    }

    public BigDecimal getPendingReconciliationAmount() {
        return pendingReconciliationAmount;
    }

    public void setPendingReconciliationAmount(BigDecimal pendingReconciliationAmount) {
        this.pendingReconciliationAmount = pendingReconciliationAmount;
    }

    public BigDecimal getExcessAmount() {
        return excessAmount;
    }

    public void setExcessAmount(BigDecimal excessAmount) {
        this.excessAmount = excessAmount;
    }

    public BigDecimal getExpiredReceiptAmount() {
        return expiredReceiptAmount;
    }

    public void setExpiredReceiptAmount(BigDecimal expiredReceiptAmount) {
        this.expiredReceiptAmount = expiredReceiptAmount;
    }

    public Integer getCancelledOrderCount() {
        return cancelledOrderCount;
    }

    public void setCancelledOrderCount(Integer cancelledOrderCount) {
        this.cancelledOrderCount = cancelledOrderCount;
    }

    public BigDecimal getCancelledAmount() {
        return cancelledAmount;
    }

    public void setCancelledAmount(BigDecimal cancelledAmount) {
        this.cancelledAmount = cancelledAmount;
    }

    public BigDecimal getTotalDiscountAmount() {
        return totalDiscountAmount;
    }

    public void setTotalDiscountAmount(BigDecimal totalDiscountAmount) {
        this.totalDiscountAmount = totalDiscountAmount;
    }

    public BigDecimal getTotalPointsAmount() {
        return totalPointsAmount;
    }

    public void setTotalPointsAmount(BigDecimal totalPointsAmount) {
        this.totalPointsAmount = totalPointsAmount;
    }

    public Integer getDeliveryOrderCount() {
        return deliveryOrderCount;
    }

    public void setDeliveryOrderCount(Integer deliveryOrderCount) {
        this.deliveryOrderCount = deliveryOrderCount;
    }

    public Integer getPickupOrderCount() {
        return pickupOrderCount;
    }

    public void setPickupOrderCount(Integer pickupOrderCount) {
        this.pickupOrderCount = pickupOrderCount;
    }

    public Integer getWebOrderCount() {
        return webOrderCount;
    }

    public void setWebOrderCount(Integer webOrderCount) {
        this.webOrderCount = webOrderCount;
    }

    public Integer getCounterOrderCount() {
        return counterOrderCount;
    }

    public void setCounterOrderCount(Integer counterOrderCount) {
        this.counterOrderCount = counterOrderCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
