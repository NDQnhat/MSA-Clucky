package com.clucky.identityservice.service;

import com.clucky.identityservice.config.JwtSettings;
import com.clucky.identityservice.dto.Role;
import com.clucky.identityservice.entity.User;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persist a phone verification in the same transaction as the account update.
 * The scheduled publisher retries delivery with the same event ID until Kafka ACKs.
 * Consumers must deduplicate by eventId and (customerId, phoneBindingVersion).
 */
@Component
public class CustomerPhoneVerifiedOutbox {
    public static final String TOPIC = "identity.customer-phone-verified.v1";
    private static final Logger LOG = LoggerFactory.getLogger(CustomerPhoneVerifiedOutbox.class);
    private static final String TABLE = "identity_service.customer_phone_verified_outbox";
    private static final String PHONE_KEY_DOMAIN = "clucky:loyalty:phone-key:v1:";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final byte[] phoneKeySecret;
    private final TransactionTemplate transactions;

    public CustomerPhoneVerifiedOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            ObjectMapper mapper, JwtSettings jwt, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
        // Shared across internal services. Domain separation prevents using this
        // HMAC as a JWT signature or as an OTP verifier.
        this.phoneKeySecret = jwt.secret().getBytes(StandardCharsets.UTF_8);
        this.transactions = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(User user) {
        if (user.getRole() != Role.CUSTOMER || !Boolean.TRUE.equals(user.getPhoneVerified())
                || user.getPhoneBindingVersion() == null || user.getPhoneBindingVersion() < 1) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
        }
        PhoneNumbers.normalize(user.getPhone());
        jdbc.update("INSERT INTO " + TABLE
                        + " (event_id,customer_id,binding_version) VALUES (?,?,?) "
                        + "ON CONFLICT (customer_id,binding_version) DO NOTHING",
                UUID.randomUUID(), user.getId(), user.getPhoneBindingVersion());
    }

    @Scheduled(fixedDelayString = "${clucky.identity.phone-outbox-delay-ms:5000}")
    public void publish() {
        try {
            transactions.executeWithoutResult(transaction -> {
                var pending = jdbc.queryForList(
                        "SELECT e.event_id,e.customer_id,e.binding_version,e.occurred_at,"
                                + "u.phone,u.phone_verified,u.phone_binding_version,u.role "
                                + "FROM " + TABLE + " e JOIN identity_service.users u ON u.id=e.customer_id "
                                + "WHERE e.published_at IS NULL ORDER BY e.occurred_at "
                                + "LIMIT 20 FOR UPDATE OF e SKIP LOCKED");
                for (var event : pending) {
                    UUID eventId = (UUID) event.get("event_id");
                    long customerId = ((Number) event.get("customer_id")).longValue();
                    long bindingVersion = ((Number) event.get("binding_version")).longValue();
                    // A stale binding must never claim the current wallet. This
                    // is a terminal skip; failed broker sends remain pending.
                    if (!Boolean.TRUE.equals(event.get("phone_verified"))
                            || !"CUSTOMER".equals(event.get("role"))
                            || !Objects.equals(bindingVersion,
                                    ((Number) event.get("phone_binding_version")).longValue())) {
                        jdbc.update("UPDATE " + TABLE + " SET published_at=now() WHERE event_id=?", eventId);
                        continue;
                    }
                    try {
                        String phoneKey = phoneKey(PhoneNumbers.normalize((String) event.get("phone")));
                        String occurredAt = occurredAt(event.get("occurred_at")).toString();
                        var payload = Map.of(
                                "customerId", customerId,
                                "phoneKey", phoneKey,
                                "phoneKeyVersion", 1,
                                "phoneBindingVersion", bindingVersion);
                        String json = mapper.writeValueAsString(Map.of(
                                "eventId", eventId.toString(),
                                "correlationId", eventId.toString(),
                                "eventType", TOPIC,
                                "sourceService", "identity-service",
                                "occurredAt", occurredAt,
                                "payload", payload));
                        ProducerRecord<String, String> record =
                                new ProducerRecord<>(TOPIC, Long.toString(customerId), json);
                        record.headers().add("X-Event-ID", eventId.toString().getBytes(StandardCharsets.UTF_8));
                        record.headers().add("X-Correlation-ID", eventId.toString().getBytes(StandardCharsets.UTF_8));
                        kafka.send(record).get(10, TimeUnit.SECONDS);
                        jdbc.update("UPDATE " + TABLE + " SET published_at=now() WHERE event_id=?", eventId);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Phone verification event delivery interrupted");
                    } catch (Exception failure) {
                        throw new IllegalStateException("Phone verification event delivery pending");
                    }
                }
            });
        } catch (RuntimeException failure) {
            // Never print the phone number, phone key or serialized event.
            LOG.warn("event=customer_phone_verified_outbox_pending");
        }
    }

    /** Requeue missing records from existing verified accounts for reconciliation. */
    @Scheduled(fixedDelayString = "${clucky.identity.phone-reconcile-delay-ms:60000}")
    public void reconcile() {
        try {
            jdbc.update("INSERT INTO " + TABLE
                    + " (event_id,customer_id,binding_version) "
                    + "SELECT gen_random_uuid(),id,phone_binding_version "
                    + "FROM identity_service.users "
                    + "WHERE role='CUSTOMER' AND phone_verified AND phone IS NOT NULL "
                    + "AND phone_binding_version > 0 "
                    + "ON CONFLICT (customer_id,binding_version) DO NOTHING");
        } catch (RuntimeException failure) {
            LOG.warn("event=customer_phone_verified_reconciliation_pending");
        }
    }

    private String phoneKey(String canonicalE164) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(phoneKeySecret, "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(
                    (PHONE_KEY_DOMAIN + canonicalE164).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Phone key HMAC unavailable");
        }
    }

    private static Instant occurredAt(Object raw) {
        if (raw instanceof Timestamp time) return time.toInstant();
        if (raw instanceof OffsetDateTime time) return time.toInstant();
        if (raw instanceof Instant time) return time;
        throw new IllegalStateException("Unsupported event timestamp");
    }
}
