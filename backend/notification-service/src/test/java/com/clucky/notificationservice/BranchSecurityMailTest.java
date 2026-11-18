package com.clucky.notificationservice;

import com.clucky.notificationservice.dto.EmailMessage;
import com.clucky.notificationservice.service.BranchSecurityMail;
import com.clucky.notificationservice.service.EmailSenderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchSecurityMailTest {
    private static final String SECURITY_TOPIC = "identity.branch-email";
    private static final String REQUEST_TOPIC = "email.requested";

    @Test
    void transientPersistenceFailureLeavesTheSecurityMessageUnacknowledgedForRetry() throws Exception {
        var mapper = new ObjectMapper();
        var validator = mock(Validator.class);
        when(validator.validate(any(EmailMessage.class))).thenReturn(Set.of());
        var deliveries = mock(EmailSenderService.class);
        Instant expiry = Instant.parse("2026-11-18T10:10:00Z");
        var message = new EmailMessage("challenge.primary", "event-1", "trace-1", "branch@example.com",
                "branch-security", Map.of("purpose", "ACTIVATE", "otp", "12345678", "expiresAt", expiry.toString()));
        var record = new ConsumerRecord<>(SECURITY_TOPIC, 0, 0L, message.messageId(), mapper.writeValueAsString(message));
        var acknowledgment = mock(Acknowledgment.class);
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(deliveries).processEmailIntent(
                eq(message.messageId()), eq(message.eventId()), eq(message.correlationId()), eq(message.recipient()),
                eq(message.template()), eq(message.variables()), isNull(), eq(expiry));

        var listener = new BranchSecurityMail(mapper, validator, deliveries, SECURITY_TOPIC, REQUEST_TOPIC);
        assertThrows(IllegalStateException.class, () -> listener.receive(record, acknowledgment));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void acceptedSecurityIntentIsPersistedBeforeKafkaAcknowledgement() throws Exception {
        var mapper = new ObjectMapper();
        var validator = mock(Validator.class);
        when(validator.validate(any(EmailMessage.class))).thenReturn(Set.of());
        var deliveries = mock(EmailSenderService.class);
        Instant expiry = Instant.parse("2026-11-18T10:10:00Z");
        var message = new EmailMessage("challenge.primary", "event-1", "trace-1", "branch@example.com",
                "branch-security", Map.of("purpose", "ACTIVATE", "otp", "12345678", "expiresAt", expiry.toString()));
        var record = new ConsumerRecord<>(SECURITY_TOPIC, 0, 0L, message.messageId(), mapper.writeValueAsString(message));
        var acknowledgment = mock(Acknowledgment.class);

        var listener = new BranchSecurityMail(mapper, validator, deliveries, SECURITY_TOPIC, REQUEST_TOPIC);
        listener.receive(record, acknowledgment);

        InOrder order = inOrder(deliveries, acknowledgment);
        order.verify(deliveries).processEmailIntent(message.messageId(), message.eventId(), message.correlationId(),
                message.recipient(), message.template(), message.variables(), null, expiry);
        order.verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedSecurityPayloadIsClassifiedAsNonRetryablePoisonMessage() {
        var listener = new BranchSecurityMail(new ObjectMapper(), mock(Validator.class), mock(EmailSenderService.class),
                SECURITY_TOPIC, REQUEST_TOPIC);
        var record = new ConsumerRecord<>(SECURITY_TOPIC, 0, 0L, "challenge.primary", "{");
        assertThrows(IllegalArgumentException.class, () -> listener.receive(record, mock(Acknowledgment.class)));
    }

    @Test
    void missingExpiryIsClassifiedAsNonRetryablePoisonMessage() throws Exception {
        var mapper = new ObjectMapper();
        var validator = mock(Validator.class);
        when(validator.validate(any(EmailMessage.class))).thenReturn(Set.of());
        var message = new EmailMessage("challenge.primary", "event-1", "trace-1", "branch@example.com",
                "branch-security", Map.of("purpose", "ACTIVATE", "otp", "12345678"));
        var record = new ConsumerRecord<>(SECURITY_TOPIC, 0, 0L, message.messageId(), mapper.writeValueAsString(message));
        var listener = new BranchSecurityMail(mapper, validator, mock(EmailSenderService.class), SECURITY_TOPIC, REQUEST_TOPIC);
        assertThrows(IllegalArgumentException.class, () -> listener.receive(record, mock(Acknowledgment.class)));
    }

    @Test
    void listenerUsesTheSecurityRetryContainerFactory() throws Exception {
        var method = BranchSecurityMail.class.getMethod("receive", ConsumerRecord.class, Acknowledgment.class);
        var listener = method.getAnnotation(KafkaListener.class);
        assertNotNull(listener);
        assertEquals("securityEmailKafkaListenerContainerFactory", listener.containerFactory());
    }
}
