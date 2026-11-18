package com.clucky.catalogstoreservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Optional;

@Component
public class OrderServiceClient {
    private static final Logger LOG = LoggerFactory.getLogger(OrderServiceClient.class);

    private final RestClient client;
    private final DiscoveryClient discovery;
    private final InternalServiceCredential credential;
    private final String fallbackUrl;

    @Autowired
    public OrderServiceClient(
            @Autowired(required = false) DiscoveryClient discovery,
            InternalServiceCredential credential,
            @Value("${clucky.cart-order.service-url:http://cart-order-service}") String fallbackUrl
    ) {
        this.discovery = discovery;
        this.credential = credential;
        this.fallbackUrl = fallbackUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public record RemoteOrderStatus(
            Long orderId,
            String orderCode,
            String status,
            Long storeId,
            Instant createdAt
    ) {}

    public Optional<RemoteOrderStatus> getOrderStatus(long orderId) {
        String baseUrl = fallbackUrl;
        if (discovery != null) {
            try {
                var instances = discovery.getInstances("cart-order-service");
                if (instances != null && !instances.isEmpty()) {
                    baseUrl = instances.getFirst().getUri().toString();
                }
            } catch (Exception ex) {
                LOG.debug("Discovery resolution failed for cart-order-service, using fallback {}", fallbackUrl);
            }
        }

        String uri = baseUrl + "/internal/orders/" + orderId + "/status";
        String token = credential.token();

        try {
            RemoteOrderStatus status = client.get()
                    .uri(uri)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .body(RemoteOrderStatus.class);
            return Optional.ofNullable(status);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                LOG.info("event=reconciliation_order_not_found orderId={}", orderId);
                return Optional.empty();
            }
            LOG.warn("event=order_status_query_failed orderId={} status={} err={}",
                    orderId, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw ex;
        } catch (Exception ex) {
            LOG.warn("event=order_status_query_error orderId={} err={}", orderId, ex.getMessage());
            throw ex;
        }
    }
}
