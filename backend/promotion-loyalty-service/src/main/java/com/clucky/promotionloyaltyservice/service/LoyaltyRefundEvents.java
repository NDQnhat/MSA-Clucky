package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.dto.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Separate refund consumer: order.completed and refund.processed may be replayed. */
@Component
public class LoyaltyRefundEvents {
    // Refund topics are not part of the older Order/Promotion topic declarations.
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        KafkaAdmin.NewTopics loyaltyRefundTopics(
                @Value("${clucky.kafka.partitions:3}") int partitions,
                @Value("${clucky.kafka.replicas:1}") int replicas) {
            if (partitions < 1 || replicas < 1) throw new IllegalArgumentException("Invalid refund topic settings");
            return new KafkaAdmin.NewTopics(
                    TopicBuilder.name("refund.processed").partitions(partitions).replicas(replicas).build(),
                    TopicBuilder.name("refund.processed.DLT").partitions(partitions).replicas(replicas).build());
        }
    }

    private final LoyaltyRefundService refunds;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final RestClient orders;
    private final String orderUrl;
    private final InternalServiceCredential credential;

    public LoyaltyRefundEvents(LoyaltyRefundService refunds, ObjectMapper mapper, Validator validator,
            @LoadBalanced RestClient.Builder builder,
            @Value("${clucky.loyalty.order-url:http://cart-order-service}") String orderUrl,
            InternalServiceCredential credential) {
        this.refunds = refunds;
        this.mapper = mapper;
        this.validator = validator;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.orders = builder.clone().requestFactory(factory).build();
        this.orderUrl = orderUrl;
        this.credential = credential;
    }

    public record RefundSnapshot(Long orderId, Long customerId, String status, Long refundId,
            String refundStatus, BigDecimal refundAmount, String paymentStatus,
            BigDecimal totalAmount, String orderSource) {}

    @KafkaListener(topics = "refund.processed", groupId = "loyalty-refund-compensation-v1")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgement) throws Exception {
        DomainEvent event = mapper.readValue(record.value(), DomainEvent.class);
        if (!validator.validate(event).isEmpty()
                || !"cart-order-service".equals(event.sourceService())
                || !"refund.processed".equals(event.eventType())
                || !record.topic().equals(event.eventType()))
            throw new IllegalArgumentException("Invalid refund event origin");
        var payload = event.payload();
        if (!payload.path("refundId").isIntegralNumber() || !payload.path("refundId").canConvertToLong()
                || !payload.path("orderId").isIntegralNumber() || !payload.path("orderId").canConvertToLong()
                || payload.path("refundId").asLong() <= 0 || payload.path("orderId").asLong() <= 0
                || !"REFUNDED".equals(payload.path("status").asText())
                || !payload.path("amount").isNumber())
            throw new IllegalArgumentException("Invalid refund event payload");
        long refundId = payload.path("refundId").asLong();
        long orderId = payload.path("orderId").asLong();
        BigDecimal amount = payload.path("amount").decimalValue();
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0 || amount.precision() > 12)
            throw new IllegalArgumentException("Invalid refund amount");

        // This GET is protected by the existing /internal/loyalty/orders/* rule,
        // restricted to promotion-loyalty-service credentials. Kafka is only a trigger.
        RefundSnapshot snapshot = orders.get()
                .uri(orderUrl + "/internal/loyalty/orders/" + orderId + "?refundId=" + refundId)
                .headers(headers -> headers.setBearerAuth(credential.token()))
                .retrieve().body(RefundSnapshot.class);
        if (snapshot == null || !Objects.equals(snapshot.orderId(), orderId)
                || !Objects.equals(snapshot.refundId(), refundId)
                || !"REFUNDED".equals(snapshot.refundStatus())
                || !"REFUNDED".equals(snapshot.paymentStatus())
                || snapshot.refundAmount() == null || snapshot.refundAmount().compareTo(amount) != 0
                || snapshot.totalAmount() == null || snapshot.totalAmount().compareTo(amount) != 0
                || !Set.of("COMPLETED", "CANCELLED", "CONFIRMED").contains(snapshot.status())
                || !Set.of("WEB", "COUNTER").contains(snapshot.orderSource()))
            throw new IllegalStateException("Refund does not match authoritative Order state");
        if (payload.path("customerId").isIntegralNumber()
                && (!payload.path("customerId").canConvertToLong()
                    || !Objects.equals(snapshot.customerId(), payload.path("customerId").asLong())))
            throw new IllegalStateException("Refund customer mismatch");

        refunds.process(refundId, orderId, amount, snapshot.status(), snapshot.customerId(),
                snapshot.orderSource(), event.eventId(), event.correlationId());
        acknowledgement.acknowledge();
    }
}
