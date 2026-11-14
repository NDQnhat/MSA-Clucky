package com.clucky.cartorderservice.service;

import com.clucky.cartorderservice.exception.ApiException;
import com.clucky.cartorderservice.exception.ErrorCode;
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
            UUID voucherInstanceId
    ) {
        public VoucherValidationRequest(String code, Long storeId, BigDecimal orderAmount) {
            this(code, storeId, orderAmount, null);
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
        if ((rawCode == null || rawCode.isBlank()) && voucherInstanceId == null) {
            return new VoucherResult(false, null, BigDecimal.ZERO);
        }
        if (voucherInstanceId != null && rawCode != null && !rawCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Only one voucher per order");
        }
        String code = rawCode == null ? null : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        VoucherValidationRequest req = new VoucherValidationRequest(code, storeId, subtotal, voucherInstanceId);

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
            if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 404
                    || ex.getStatusCode().value() == 409) {
                return new VoucherResult(false, ErrorCode.VOUCHER_INACTIVE.message(), BigDecimal.ZERO);
            }
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (Exception ex) {
            LOG.warn("Promotion service voucher validation unavailable storeId={}", storeId);
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

}
