package com.clucky.cartorderservice.service;

import com.clucky.cartorderservice.exception.ApiException;
import com.clucky.cartorderservice.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class PromotionClient {
    private static final Logger LOG = LoggerFactory.getLogger(PromotionClient.class);

    private final RestClient restClient;
    private final String promotionServiceUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromotionClient(
            RestClient restClient,
            @Value("${clucky.promotion.service-url:http://promotion-loyalty-service}") String promotionServiceUrl
    ) {
        this.restClient = restClient;
        this.promotionServiceUrl = promotionServiceUrl;
    }

    public record VoucherValidationRequest(
            String code,
            Long storeId,
            BigDecimal orderAmount,
            UUID voucherInstanceId,
            String channel
    ) {
        public VoucherValidationRequest(String code, Long storeId, BigDecimal orderAmount) {
            this(code, storeId, orderAmount, null, null);
        }

        public VoucherValidationRequest(String code, Long storeId, BigDecimal orderAmount, UUID voucherInstanceId) {
            this(code, storeId, orderAmount, voucherInstanceId, null);
        }
    }

    public record VoucherPolicy(Long id, Long version) {}
    public record VoucherValidationResponse(
            boolean valid,
            BigDecimal discountAmount,
            String reason,
            UUID voucherInstanceId,
            VoucherPolicy voucher
    ) {}

    public record VoucherResult(
            boolean valid,
            String message,
            BigDecimal discountAmount,
            UUID voucherInstanceId,
            Long campaignId,
            Long campaignVersion
    ) {
        public VoucherResult(boolean valid, String message, BigDecimal discountAmount) {
            this(valid, message, discountAmount, null, null, null);
        }
    }






    public VoucherResult validateVoucher(String rawCode, Long storeId, BigDecimal subtotal) {
        return validateVoucher(rawCode, null, storeId, subtotal);
    }

    public VoucherResult validateVoucher(String rawCode, UUID voucherInstanceId, Long storeId, BigDecimal subtotal) {
        return validateVoucher(rawCode, voucherInstanceId, storeId, subtotal, null);
    }

    public VoucherResult validateVoucher(String rawCode, UUID voucherInstanceId, Long storeId, BigDecimal subtotal, String channel) {
        if ((rawCode == null || rawCode.isBlank()) && voucherInstanceId == null) {
            return new VoucherResult(false, null, BigDecimal.ZERO);
        }
        if (voucherInstanceId != null && rawCode != null && !rawCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Only one voucher per order");
        }
        String code = rawCode == null ? null : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        VoucherValidationRequest req = new VoucherValidationRequest(code, storeId, subtotal, voucherInstanceId, channel);

        try {
            VoucherValidationResponse res = restClient.post()
                    .uri(promotionServiceUrl + "/api/promotions/vouchers/validate")
                    .headers(headers -> {
                        if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                                instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken authentication)
                            headers.setBearerAuth(authentication.getToken().getTokenValue());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(VoucherValidationResponse.class);

            if (res == null) {
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            if (res.valid() && res.discountAmount() != null && res.discountAmount().signum() >= 0) {
                return new VoucherResult(true, "Đã áp dụng mã giảm giá thành công",
                        res.discountAmount(), res.voucherInstanceId(),
                        res.voucher() == null ? null : res.voucher().id(),
                        res.voucher() == null ? null : res.voucher().version());
            }

            return new VoucherResult(false, res.reason(), BigDecimal.ZERO);
        } catch (ApiException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 400 || status == 403 || status == 404 || status == 409 || status == 429) {
                return new VoucherResult(false, resolveVoucherErrorMessage(ex), BigDecimal.ZERO);
            }
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (Exception ex) {
            LOG.warn("Promotion service voucher validation unavailable storeId={}", storeId);
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private String resolveVoucherErrorMessage(RestClientResponseException ex) {
        String code = null;
        String rawMessage = null;
        try {
            String body = ex.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                JsonNode node = objectMapper.readTree(body);
                if (node.hasNonNull("code")) {
                    code = node.get("code").asText();
                }
                if (node.hasNonNull("message")) {
                    rawMessage = node.get("message").asText();
                }
            }
        } catch (Exception ignored) {
            // Fall back to HTTP status mapping below
        }

        if (code != null) {
            return switch (code) {
                case "VOUCHER_EXHAUSTED" -> "Mã giảm giá đã được sử dụng hoặc hết lượt.";
                case "VOUCHER_EXPIRED" -> "Mã giảm giá đã hết hạn.";
                case "VOUCHER_NOT_STARTED" -> "Mã giảm giá chưa đến thời gian sử dụng.";
                case "VOUCHER_SCOPE_MISMATCH" -> "Mã giảm giá không áp dụng tại cửa hàng này hoặc kênh này.";
                case "MIN_ORDER_NOT_MET" -> "Đơn hàng chưa đạt giá trị tối thiểu để dùng mã giảm giá.";
                case "VOUCHER_NOT_FOUND" -> "Không tìm thấy mã giảm giá.";
                case "TOO_MANY_REQUESTS" -> "Thao tác kiểm tra mã quá nhanh. Vui lòng thử lại sau.";
                case "FORBIDDEN" -> "Bạn không có quyền áp dụng loại mã giảm giá này.";
                case "VALIDATION_ERROR" -> "Mã giảm giá không hợp lệ.";
                default -> "Mã giảm giá hiện không khả dụng.";
            };
        }
        if (ex.getStatusCode().value() == 404) {
            return "Không tìm thấy mã giảm giá.";
        }
        if (ex.getStatusCode().value() == 429) {
            return "Thao tác kiểm tra mã quá nhanh. Vui lòng thử lại sau.";
        }
        return rawMessage != null && !rawMessage.isBlank() ? rawMessage : "Mã giảm giá hiện không khả dụng.";
    }

}
