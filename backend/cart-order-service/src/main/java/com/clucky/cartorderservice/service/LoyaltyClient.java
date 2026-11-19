package com.clucky.cartorderservice.service;

import com.clucky.cartorderservice.config.CurrentActor;
import com.clucky.cartorderservice.exception.ApiException;
import com.clucky.cartorderservice.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class LoyaltyClient {
    private final RestClient client;
    private final String url;
    private final InternalServiceCredential credential;
    public LoyaltyClient(RestClient client, @Value("${clucky.promotion.service-url:http://promotion-loyalty-service}") String url,
            InternalServiceCredential credential) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        this.client = client.mutate().requestFactory(factory).build(); this.url = url; this.credential = credential;
    }
    public record Quote(long policyVersion, long availablePoints, int pointsUsed, BigDecimal pointsAmount,
            long pointsToEarn, String tier, BigDecimal tierDiscount, Long walletId) {
        public Quote(long policyVersion, long availablePoints, int pointsUsed, BigDecimal pointsAmount,
                long pointsToEarn, String tier, BigDecimal tierDiscount) {
            this(policyVersion, availablePoints, pointsUsed, pointsAmount, pointsToEarn, tier, tierDiscount, null);
        }
    }
    public record CounterQuote(long policyVersion, long availablePoints, int pointsUsed, BigDecimal pointsAmount,
            long pointsToEarn, String tier, BigDecimal tierDiscount, boolean redeemAllowed, Long walletId,
            int maxRedeemablePoints) {}
    public Quote preview(Integer requested, BigDecimal amount) {
        int points = requested == null ? 0 : requested;
        if (points < 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) {
            if (points > 0) throw new ApiException(ErrorCode.UNAUTHENTICATED);
            return new Quote(0, 0, 0, BigDecimal.ZERO, 0, "MEMBER", BigDecimal.ZERO);
        }
        return call("preview", Map.of("customerId", CurrentActor.require().userId(), "pointsToRedeem", points, "orderAmount", amount));
    }
    public void reserve(long orderId, long customerId, BigDecimal amount, Quote quote) {
        reserveWithReceipt(orderId, customerId, amount, quote);
    }
    public Quote reserveWithReceipt(long orderId, long customerId, BigDecimal amount, Quote quote) {
        Quote settled = call("reserve", Map.of("orderId", orderId, "customerId", customerId, "pointsToRedeem", quote.pointsUsed(), "orderAmount", amount,
                "policyVersion", quote.policyVersion(), "pointsAmount", quote.pointsAmount(), "tierDiscount", quote.tierDiscount()));
        if (settled == null || settled.walletId() == null || settled.walletId() <= 0
                || settled.policyVersion() != quote.policyVersion()
                || settled.pointsUsed() != quote.pointsUsed()
                || settled.pointsAmount().compareTo(quote.pointsAmount()) != 0
                || settled.tierDiscount().compareTo(quote.tierDiscount()) != 0
                || settled.pointsToEarn() != quote.pointsToEarn())
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Loyalty quote has changed");
        return settled;
    }
    public CounterQuote counterPreview(String loyaltySubject, String scanProof, int requested, BigDecimal amount,
            long staffId, long storeId, String draftHash) {
        Map<String,Object> body = new HashMap<>();
        body.put("loyaltySubject", loyaltySubject);
        body.put("scanProof", scanProof);
        body.put("pointsUsed", requested);
        body.put("amount", amount);
        body.put("staffId", staffId);
        body.put("storeId", storeId);
        body.put("draftHash", draftHash);
        return counterCall("preview", body);
    }
    public CounterQuote counterReserve(long orderId, String loyaltySubject, String scanProof, BigDecimal amount,
            CounterQuote quote, long staffId, long storeId, String draftHash) {
        Map<String,Object> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("loyaltySubject", loyaltySubject);
        body.put("scanProof", scanProof);
        body.put("pointsUsed", quote.pointsUsed());
        body.put("amount", amount);
        body.put("policyVersion", quote.policyVersion());
        body.put("pointsAmount", quote.pointsAmount());
        body.put("tierDiscount", quote.tierDiscount());
        body.put("pointsToEarn", quote.pointsToEarn());
        body.put("staffId", staffId);
        body.put("storeId", storeId);
        body.put("draftHash", draftHash);
        return counterCall("reserve", body);
    }
    private CounterQuote counterCall(String action, Object input) {
        try {
            CounterQuote result = client.post().uri(url + "/internal/loyalty/counter/" + action)
                    .headers(headers -> headers.setBearerAuth(credential.token()))
                    .body(input).retrieve().body(CounterQuote.class);
            if (result == null || result.pointsAmount() == null || result.tierDiscount() == null
                    || result.maxRedeemablePoints() < 0 || result.maxRedeemablePoints() > result.availablePoints())
                throw new ApiException(ErrorCode.UPSTREAM_ERROR);
            return result;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 409) throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Loyalty hết hạn hoặc đã thay đổi; vui lòng quét/tra lại");
            if (failure.getStatusCode().value() == 400) throw new ApiException(ErrorCode.VALIDATION_ERROR, "Mã Loyalty không đủ quyền hoặc không hợp lệ");
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (ApiException failure) { throw failure; }
        catch (Exception failure) { throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public record VoucherQuote(String code, UUID voucherInstanceId, BigDecimal discountAmount) {}
    public void reserveVoucher(long orderId, long customerId, long storeId, String code, BigDecimal amount, BigDecimal discount) {
        reserveVoucher(orderId, customerId, storeId, code, null, amount, discount);
    }
    public void reserveVoucher(long orderId, Long customerId, long storeId, String code, UUID voucherInstanceId,
            BigDecimal amount, BigDecimal discount) {
        try {
            Map<String,Object> body = new HashMap<>();
            body.put("orderId", orderId); body.put("customerId", customerId);
            body.put("storeId", storeId); body.put("code", code);
            body.put("voucherInstanceId", voucherInstanceId);
            body.put("orderAmount", amount); body.put("discountAmount", discount);
            VoucherQuote result = client.post().uri(url + "/internal/loyalty/voucher-reservations")
                    .headers(headers -> headers.setBearerAuth(credential.token()))
                    .body(body)
                    .retrieve().body(VoucherQuote.class);
            if (result == null || (code != null && result.code() == null && result.voucherInstanceId() == null)
                    || (voucherInstanceId != null && !voucherInstanceId.equals(result.voucherInstanceId()))
                    || result.discountAmount() == null
                    || discount.compareTo(result.discountAmount()) != 0) throw new ApiException(ErrorCode.UPSTREAM_ERROR);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 409) throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
            if (failure.getStatusCode().value() == 400) throw new ApiException(ErrorCode.VALIDATION_ERROR);
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (ApiException failure) { throw failure;
        } catch (Exception failure) { throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public void releaseVoucher(long orderId) {
        try {
            client.post().uri(url + "/internal/loyalty/voucher-reservations/" + orderId + "/release")
                    .headers(headers -> headers.setBearerAuth(credential.token())).retrieve().toBodilessEntity();
        } catch (Exception failure) { throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    private Quote call(String action, Object input) {
        try {
            Quote result = client.post().uri(url + "/internal/loyalty/" + action).headers(headers -> headers.setBearerAuth(credential.token()))
                    .body(input).retrieve().body(Quote.class);
            if (result == null || result.policyVersion() <= 0 || result.pointsUsed() < 0 || result.pointsAmount() == null || result.tierDiscount() == null
                    || result.pointsAmount().signum() < 0 || result.tierDiscount().signum() < 0) throw new IllegalStateException("Invalid loyalty response");
            return result;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 409) throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
            if (failure.getStatusCode().value() == 400) throw new ApiException(ErrorCode.VALIDATION_ERROR);
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (Exception failure) { throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
}
