package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.dto.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class LoyaltyEvents {
    private static final Logger LOG = LoggerFactory.getLogger(LoyaltyEvents.class);
    private final LoyaltyService loyalty;
    private final CounterLoyaltyService counter;
    private final VoucherReservationService vouchers;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final RestClient orders;
    private final String orderUrl;
    private final InternalServiceCredential credential;
    public LoyaltyEvents(LoyaltyService loyalty, CounterLoyaltyService counter, VoucherReservationService vouchers, ObjectMapper mapper, Validator validator,
            @LoadBalanced RestClient.Builder builder, @Value("${clucky.loyalty.order-url:http://cart-order-service}") String orderUrl,
            InternalServiceCredential credential) {
        this.loyalty = loyalty; this.counter = counter; this.mapper = mapper; this.validator = validator;
        this.vouchers = vouchers;
        this.orders = builder.build(); this.orderUrl = orderUrl; this.credential = credential;
    }
    @KafkaListener(topics = {"order.created", "order.completed", "order.cancelled"}, groupId = "loyalty-ledger-v1")
    public void consume(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record, Acknowledgment acknowledgment) throws Exception {
        DomainEvent event = mapper.readValue(record.value(), DomainEvent.class);
        if (!validator.validate(event).isEmpty() || !"cart-order-service".equals(event.sourceService())
                || !record.topic().equals(event.eventType())) throw new IllegalArgumentException("Invalid loyalty event");
        var payload = event.payload();
        boolean counterOrder = "COUNTER".equals(payload.path("orderSource").asText());
        if (!payload.path("orderId").isIntegralNumber() || !payload.path("orderId").canConvertToLong()
                || payload.path("orderId").asLong() <= 0
                || (!counterOrder && (!payload.path("customerId").canConvertToLong()
                    || payload.path("customerId").asLong() <= 0)))
            throw new IllegalArgumentException("Invalid loyalty event identity");
        long orderId = payload.path("orderId").asLong();
        Long customerId = payload.path("customerId").isIntegralNumber() ? payload.path("customerId").asLong() : null;
        OrderState state = orderState(orderId, customerId);
        if (counterOrder) counter.settle(orderId, state.status(), event.eventId(), event.correlationId());
        else loyalty.settle(orderId, customerId, state.status(), event.eventId(), event.correlationId());
        vouchers.settle(orderId, customerId, state.status(), event.eventId(), event.correlationId());
        acknowledgment.acknowledge();
    }
    public record OrderState(Long orderId, Long customerId, String status) {}
    private OrderState orderState(long orderId, Long customerId) {
        OrderState state = orders.get().uri(orderUrl + "/internal/loyalty/orders/" + orderId)
                .headers(headers -> headers.setBearerAuth(credential.token())).retrieve().body(OrderState.class);
        if (state == null || state.orderId() == null || state.orderId() != orderId || state.status() == null)
            throw new IllegalStateException("Invalid order reconciliation response");
        if ("MISSING".equals(state.status()) && state.customerId() == null) return state;
        if (!java.util.Objects.equals(state.customerId(), customerId) || !Set.of("PENDING_PAYMENT", "CONFIRMED", "PREPARING", "READY", "SHIPPING", "COMPLETED", "CANCELLED").contains(state.status()))
            throw new IllegalStateException("Invalid order reconciliation response");
        return state;
    }
    @Scheduled(fixedDelayString="${clucky.loyalty.reconcile-delay-ms:60000}")
    public void reconcile() {
        for (var pending : loyalty.pending()) {
            try {
                OrderState state = orderState(pending.orderId(), pending.customerId());
                if (pending.customerId() == null) counter.settle(pending.orderId(), state.status(), null, null);
                else loyalty.settle(pending.orderId(), pending.customerId(), state.status(), null, null);
                vouchers.settle(pending.orderId(), pending.customerId(), state.status(), null, null);
            } catch (RestClientResponseException failure) {
                LOG.warn("event=loyalty_reconcile_deferred orderId={} status={}", pending.orderId(), failure.getStatusCode().value());
            } catch (Exception failure) {
                LOG.warn("event=loyalty_reconcile_deferred orderId={}", pending.orderId());
            }
        }
        // Vouchers can be reserved for an anonymous counter transaction without
        // a loyalty wallet; they must still be reconciled against the order.
        for (var pending : vouchers.pending()) {
            try {
                OrderState state = orderState(pending.orderId(), pending.customerId());
                vouchers.settle(pending.orderId(), pending.customerId(), state.status(), null, null);
            } catch (RestClientResponseException failure) {
                LOG.warn("event=voucher_reconcile_deferred orderId={} status={}",
                        pending.orderId(), failure.getStatusCode().value());
            } catch (Exception failure) {
                LOG.warn("event=voucher_reconcile_deferred orderId={}", pending.orderId());
            }
        }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    static class ClientConfig {
        @Bean
        @LoadBalanced
        RestClient.Builder loyaltyOrderClient(RestClientBuilderConfigurer configurer) {
            var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
            factory.setReadTimeout(Duration.ofSeconds(10));
            return configurer.configure(RestClient.builder()).requestFactory(factory);
        }
    }
}
