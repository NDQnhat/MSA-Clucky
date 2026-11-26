package com.clucky.catalogstoreservice.entity;

import com.clucky.catalogstoreservice.dto.LocalizedText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "stores")
public class Store {

    @Version
    private long version;
    @Column(name = "delivery_radius_km", precision = 7, scale = 2)
    private java.math.BigDecimal deliveryRadiusKm;
    public long getVersion() { return version; }
    public java.math.BigDecimal getDeliveryRadiusKm() { return deliveryRadiusKm; }
    public void setDeliveryRadiusKm(java.math.BigDecimal radius) { this.deliveryRadiusKm = radius; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private LocalizedText name;

    @Column(nullable = false, length = 255)
    private String address;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private LocalizedText district;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private LocalizedText city;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    @Column(nullable = false, length = 20)
    private StoreStatus status = StoreStatus.OPEN;

    @Column(name = "account_readiness", nullable = false)
    private String accountReadiness = "UNPROVISIONED";
    public String getAccountReadiness() { return accountReadiness; }
    public void setAccountReadiness(String readiness) { this.accountReadiness = readiness; }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delivery_area", columnDefinition = "jsonb")
    private LocalizedText deliveryArea;

    @Column(name = "low_stock_threshold", nullable = false)
    private Integer lowStockThreshold = 20;

    public Integer getLowStockThreshold() { return lowStockThreshold != null ? lowStockThreshold : 20; }
    public void setLowStockThreshold(Integer lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private java.math.BigDecimal latitude;
    private java.math.BigDecimal longitude;
    public Double getLatitude() { return latitude == null ? null : latitude.doubleValue(); }
    public void setLatitude(Double latitude) { this.latitude = latitude == null ? null : java.math.BigDecimal.valueOf(latitude); }
    public Double getLongitude() { return longitude == null ? null : longitude.doubleValue(); }
    public void setLongitude(Double longitude) { this.longitude = longitude == null ? null : java.math.BigDecimal.valueOf(longitude); }

    public Store() {}

    public Store(Long id, LocalizedText name, String address, String phone, LocalTime openTime, LocalTime closeTime, StoreStatus status, LocalizedText deliveryArea) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.status = status != null ? status : StoreStatus.OPEN;
        this.deliveryArea = deliveryArea;
        this.createdAt = OffsetDateTime.now();
    }

    public Store(Long id, String name, String address, String phone, LocalTime openTime, LocalTime closeTime, StoreStatus status, String deliveryArea) {
        this(id, LocalizedText.ofSingle(name), address, phone, openTime, closeTime, status, LocalizedText.ofSingle(deliveryArea));
    }

    public Store(Long id, String name, String address, String phone, LocalTime openTime, LocalTime closeTime, String status, String deliveryArea) {
        this(id, LocalizedText.ofSingle(name), address, phone, openTime, closeTime, StoreStatus.fromString(status), LocalizedText.ofSingle(deliveryArea));
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalizedText getName() { return name; }
    public void setName(LocalizedText name) { this.name = name; }
    public void setName(String name) { this.name = LocalizedText.ofSingle(name); }
    public String resolveName(String lang) { return LocalizedText.resolve(name, lang); }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public LocalizedText getDistrict() { return district; }
    public void setDistrict(LocalizedText district) { this.district = district; }
    public void setDistrict(String district) { this.district = LocalizedText.ofSingle(district); }

    public LocalizedText getCity() { return city; }
    public void setCity(LocalizedText city) { this.city = city; }
    public void setCity(String city) { this.city = LocalizedText.ofSingle(city); }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalTime getOpenTime() { return openTime; }
    public void setOpenTime(LocalTime openTime) { this.openTime = openTime; }

    public LocalTime getCloseTime() { return closeTime; }
    public void setCloseTime(LocalTime closeTime) { this.closeTime = closeTime; }

    public StoreStatus getStatus() { return status; }
    public void setStatus(StoreStatus status) { this.status = status != null ? status : StoreStatus.OPEN; }
    public void setStatus(String status) { this.status = StoreStatus.fromString(status); }

    public LocalizedText getDeliveryArea() { return deliveryArea; }
    public void setDeliveryArea(LocalizedText deliveryArea) { this.deliveryArea = deliveryArea; }
    public void setDeliveryArea(String deliveryArea) { this.deliveryArea = LocalizedText.ofSingle(deliveryArea); }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
