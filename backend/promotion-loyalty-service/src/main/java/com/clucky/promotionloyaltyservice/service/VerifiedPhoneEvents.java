package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.dto.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class VerifiedPhoneEvents {
    private final CounterLoyaltyService counter;
    private final ObjectMapper mapper;
    private final Validator validator;
    public VerifiedPhoneEvents(CounterLoyaltyService counter, ObjectMapper mapper, Validator validator) {
        this.counter = counter; this.mapper = mapper; this.validator = validator;
    }
    @KafkaListener(topics = "identity.customer-phone-verified.v1", groupId = "loyalty-phone-claim-v1")
    public void onVerified(ConsumerRecord<String,String> record, Acknowledgment acknowledgment) throws Exception {
        DomainEvent event = mapper.readValue(record.value(), DomainEvent.class);
        if (!"identity.customer-phone-verified.v1".equals(event.eventType())
                || !"identity-service".equals(event.sourceService())
                || !record.topic().equals(event.eventType()) || !validator.validate(event).isEmpty()) {
            throw new IllegalArgumentException("Invalid identity verification event");
        }
        var payload = event.payload();
        if (!payload.path("customerId").canConvertToLong() || !payload.path("phoneKeyVersion").isInt()
                || !payload.path("phoneBindingVersion").canConvertToLong())
            throw new IllegalArgumentException("Invalid verified phone event");
        counter.verifiedPhone(payload.path("customerId").asLong(), payload.path("phoneKey").asText(),
                payload.path("phoneKeyVersion").asInt(), payload.path("phoneBindingVersion").asLong(), event.eventId());
        acknowledgment.acknowledge();
    }
}
