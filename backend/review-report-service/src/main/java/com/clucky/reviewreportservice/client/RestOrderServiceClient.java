package com.clucky.reviewreportservice.client;

import com.clucky.reviewreportservice.exception.ApiException;
import com.clucky.reviewreportservice.exception.ErrorCode;
import com.clucky.reviewreportservice.service.InternalServiceCredential;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class RestOrderServiceClient implements OrderServiceClient {
    private final RestTemplate restTemplate;
    private final InternalServiceCredential internalCredential;

    public RestOrderServiceClient(
            @Qualifier("orderRestTemplate") RestTemplate restTemplate,
            InternalServiceCredential internalCredential
    ) {
        this.restTemplate = restTemplate;
        this.internalCredential = internalCredential;
    }

    @Override
    public OrderVerificationDto getOrderForReview(Long orderId, Long customerId) {
        return get("/api/internal/orders/{orderId}/verification?customerId={customerId}",
                OrderVerificationDto.class, orderId, customerId);
    }

    @Override
    public Long getOrderStoreId(Long orderId) {
        OrderStoreDto order = get("/api/internal/orders/{orderId}/store", OrderStoreDto.class, orderId);
        if (order == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!orderId.equals(order.orderId()) || order.storeId() == null || order.storeId() <= 0) {
            throw new ApiException(ErrorCode.UPSTREAM_ERROR);
        }
        return order.storeId();
    }

    @Override
    public List<BackfillOrderDto> getCompletedOrdersForBackfill(long afterOrderId, int limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(internalCredential.token());
        try {
            List<BackfillOrderDto> result = restTemplate.exchange(
                    "http://cart-order-service/internal/orders/report-backfill?afterOrderId={afterOrderId}&limit={limit}",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<List<BackfillOrderDto>>() {},
                    afterOrderId,
                    limit
            ).getBody();
            return result != null ? result : List.of();
        } catch (ResourceAccessException | HttpServerErrorException.ServiceUnavailable | IllegalStateException failure) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (RestClientException failure) {
            throw new ApiException(ErrorCode.UPSTREAM_ERROR);
        }
    }

    private <Response> Response get(String path, Class<Response> responseType, Object... parameters) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.getToken().getTokenValue());
        try {
            Response response = restTemplate.exchange("http://cart-order-service" + path,
                    HttpMethod.GET, new HttpEntity<>(headers), responseType, parameters).getBody();
            if (response == null) {
                throw new ApiException(ErrorCode.UPSTREAM_ERROR);
            }
            return response;
        } catch (HttpClientErrorException.NotFound failure) {
            return null;
        } catch (HttpClientErrorException.Unauthorized failure) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        } catch (HttpClientErrorException.Forbidden failure) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        } catch (ResourceAccessException | HttpServerErrorException.ServiceUnavailable | IllegalStateException failure) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (RestClientException failure) {
            throw new ApiException(ErrorCode.UPSTREAM_ERROR);
        }
    }
}
