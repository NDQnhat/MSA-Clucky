package com.clucky.catalogstoreservice.dto;

import com.clucky.catalogstoreservice.entity.StoreStatus;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class StoreDto {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static class Response {
        private BigDecimal deliveryRadiusKm;
        private long version;
        public BigDecimal getDeliveryRadiusKm() { return deliveryRadiusKm; }
        public void setDeliveryRadiusKm(BigDecimal radius) { this.deliveryRadiusKm = radius; }
        public long getVersion() { return version; }
        public void setVersion(long version) { this.version = version; }
        private String accountReadiness;
        public String getAccountReadiness() { return accountReadiness; }
        public void setAccountReadiness(String readiness) { this.accountReadiness = readiness; }
        private Long id;
        private String name;
        private LocalizedText nameI18n;
        private String address;
        private String district;
        private LocalizedText districtI18n;
        private String city;
        private LocalizedText cityI18n;
        private String phone;
        private String openTime;
        private String closeTime;
        private String status;
        private String deliveryArea;
        private LocalizedText deliveryAreaI18n;
        private Boolean isOpenNow;
        private Integer lowStockThreshold;
        public Integer getLowStockThreshold() { return lowStockThreshold; }
        public void setLowStockThreshold(Integer lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }

        private Double latitude;
        private Double longitude;
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }

        public Response() {}

        public Response(Long id, String name, String address, String district, String city, String phone,
                        LocalTime openTime, LocalTime closeTime, StoreStatus status, String deliveryArea) {
            this(id, name, address, district, city, phone, openTime, closeTime, status, deliveryArea, null);
        }

        public Response(Long id, String name, String address, String district, String city, String phone,
                        LocalTime openTime, LocalTime closeTime, StoreStatus status, String deliveryArea, Boolean isOpenNow) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.district = district;
            this.city = city;
            this.phone = phone;
            this.openTime = openTime != null ? openTime.format(TIME_FORMAT) : null;
            this.closeTime = closeTime != null ? closeTime.format(TIME_FORMAT) : null;
            this.status = status != null ? status.name() : null;
            this.deliveryArea = deliveryArea;
            this.isOpenNow = isOpenNow;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) { this.nameI18n = nameI18n; }

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }

        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }

        public LocalizedText getDistrictI18n() { return districtI18n; }
        public void setDistrictI18n(LocalizedText districtI18n) { this.districtI18n = districtI18n; }

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        public LocalizedText getCityI18n() { return cityI18n; }
        public void setCityI18n(LocalizedText cityI18n) { this.cityI18n = cityI18n; }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        public String getOpenTime() { return openTime; }
        public void setOpenTime(String openTime) { this.openTime = openTime; }

        public String getCloseTime() { return closeTime; }
        public void setCloseTime(String closeTime) { this.closeTime = closeTime; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getDeliveryArea() { return deliveryArea; }
        public void setDeliveryArea(String deliveryArea) { this.deliveryArea = deliveryArea; }

        public LocalizedText getDeliveryAreaI18n() { return deliveryAreaI18n; }
        public void setDeliveryAreaI18n(LocalizedText deliveryAreaI18n) { this.deliveryAreaI18n = deliveryAreaI18n; }

        public Boolean getIsOpenNow() { return isOpenNow; }
        public void setIsOpenNow(Boolean isOpenNow) { this.isOpenNow = isOpenNow; }
    }

    public static class Request {
        @PositiveOrZero
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
        @Email @Size(max = 255)
        private String recoveryEmail;
        public String getRecoveryEmail() { return recoveryEmail; }
        public void setRecoveryEmail(String email) { this.recoveryEmail = email; }
        @DecimalMin("-90") @DecimalMax("90")
        private Double latitude;
        @DecimalMin("-180") @DecimalMax("180")
        private Double longitude;
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }
        @AssertTrue
        public boolean isCoordinatePairValid() {
            return (latitude == null && longitude == null) || (latitude != null && longitude != null
                    && Double.isFinite(latitude) && Double.isFinite(longitude));
        }

        @ValidLocalizedText(required = true, max = 160, message = "Store name is required")
        private LocalizedText name;
        private LocalizedText nameI18n;

        @NotBlank(message = "Address is required")
        private String address;

        @NotBlank(message = "Phone is required")
        private String phone;

        @ValidLocalizedText(required = true, max = 120, message = "District is required")
        private LocalizedText district;
        private LocalizedText districtI18n;

        @ValidLocalizedText(required = true, max = 120, message = "City is required")
        private LocalizedText city;
        private LocalizedText cityI18n;

        @NotBlank
        @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        private String openTime;

        @NotBlank
        @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        private String closeTime;

        private String status;
        private LocalizedText deliveryArea;
        private LocalizedText deliveryAreaI18n;

        public LocalizedText getName() { return name != null ? name : nameI18n; }
        public void setName(LocalizedText name) { this.name = name; }

        public LocalizedText getNameI18n() { return nameI18n; }
        public void setNameI18n(LocalizedText nameI18n) {
            this.nameI18n = nameI18n;
            if (this.name == null) this.name = nameI18n;
        }

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        public String getOpenTime() { return openTime; }
        public void setOpenTime(String openTime) { this.openTime = openTime; }

        public String getCloseTime() { return closeTime; }
        public void setCloseTime(String closeTime) { this.closeTime = closeTime; }

        public String getStatus() { return status; }
        public void setStatus(String status) {
            this.status = status != null ? StoreStatus.fromString(status).name() : null;
        }

        public LocalizedText getDeliveryArea() { return deliveryArea != null ? deliveryArea : deliveryAreaI18n; }
        public void setDeliveryArea(LocalizedText deliveryArea) { this.deliveryArea = deliveryArea; }

        public LocalizedText getDeliveryAreaI18n() { return deliveryAreaI18n; }
        public void setDeliveryAreaI18n(LocalizedText deliveryAreaI18n) {
            this.deliveryAreaI18n = deliveryAreaI18n;
            if (this.deliveryArea == null) this.deliveryArea = deliveryAreaI18n;
        }

        public LocalizedText getDistrict() { return district != null ? district : districtI18n; }
        public void setDistrict(LocalizedText district) { this.district = district; }

        public LocalizedText getDistrictI18n() { return districtI18n; }
        public void setDistrictI18n(LocalizedText districtI18n) {
            this.districtI18n = districtI18n;
            if (this.district == null) this.district = districtI18n;
        }

        public LocalizedText getCity() { return city != null ? city : cityI18n; }
        public void setCity(LocalizedText city) { this.city = city; }

        public LocalizedText getCityI18n() { return cityI18n; }
        public void setCityI18n(LocalizedText cityI18n) {
            this.cityI18n = cityI18n;
            if (this.city == null) this.city = cityI18n;
        }
    }

    public record LocationRequest(
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @PositiveOrZero Long expectedVersion) {
        public LocationRequest(Double latitude, Double longitude) { this(latitude, longitude, null); }
        @AssertTrue
        public boolean isCoordinatePairValid() {
            return (latitude == null && longitude == null) || (latitude != null && longitude != null
                    && Double.isFinite(latitude) && Double.isFinite(longitude));
        }
    }

    public record DeliverySettingsRequest(
            @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 5, fraction = 2) BigDecimal radiusKm,
            @NotNull @PositiveOrZero Long version) {}

    public static class OperationRequest {
        @PositiveOrZero
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) { throw new IllegalArgumentException("Unsupported field: " + field); }
        @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$", message = "Open time must use HH:mm format")
        private String openTime;
        @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$", message = "Close time must use HH:mm format")
        private String closeTime;
        private String status; // 'OPEN', 'CLOSED_TEMP', 'INACTIVE'
        @Pattern(regexp = "^(0|\\+84)[0-9]{8,11}$", message = "Invalid phone number")
        private String phone;
        @DecimalMin("-90") @DecimalMax("90")
        private Double latitude;
        @DecimalMin("-180") @DecimalMax("180")
        private Double longitude;
        private LocalizedText deliveryArea;
        private LocalizedText deliveryAreaI18n;
        @Min(0)
        private Integer lowStockThreshold;

        public String getOpenTime() { return openTime; }
        public void setOpenTime(String openTime) { this.openTime = openTime; }

        public String getCloseTime() { return closeTime; }
        public void setCloseTime(String closeTime) { this.closeTime = closeTime; }

        public String getStatus() { return status; }
        public void setStatus(String status) {
            this.status = status != null ? StoreStatus.fromString(status).name() : null;
        }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }

        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }

        public LocalizedText getDeliveryArea() { return deliveryArea != null ? deliveryArea : deliveryAreaI18n; }
        public void setDeliveryArea(LocalizedText deliveryArea) { this.deliveryArea = deliveryArea; }

        public LocalizedText getDeliveryAreaI18n() { return deliveryAreaI18n; }
        public void setDeliveryAreaI18n(LocalizedText deliveryAreaI18n) {
            this.deliveryAreaI18n = deliveryAreaI18n;
            if (this.deliveryArea == null) this.deliveryArea = deliveryAreaI18n;
        }

        public Integer getLowStockThreshold() { return lowStockThreshold; }
        public void setLowStockThreshold(Integer lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }
    }

    public static class StoreThresholdRequest {
        @PositiveOrZero
        private Long expectedVersion;
        public Long getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
        private Integer threshold;
        private Integer lowStockThreshold;

        public StoreThresholdRequest() {}

        public StoreThresholdRequest(Integer threshold) {
            this.threshold = threshold;
        }

        @JsonIgnore
        @AssertTrue(message = "Threshold is required")
        public boolean isPresent() {
            return getEffectiveThreshold() != null;
        }

        @JsonIgnore
        @AssertTrue(message = "Threshold must be a non-negative integer")
        public boolean isNonNegative() {
            Integer val = getEffectiveThreshold();
            return val == null || val >= 0;
        }

        @JsonIgnore
        public Integer getEffectiveThreshold() {
            return threshold != null ? threshold : lowStockThreshold;
        }

        @JsonIgnore
        public Integer threshold() {
            return getEffectiveThreshold();
        }

        public Integer getThreshold() { return threshold; }
        public void setThreshold(Integer threshold) { this.threshold = threshold; }
        public Integer getLowStockThreshold() { return lowStockThreshold; }
        public void setLowStockThreshold(Integer lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }
    }
}
