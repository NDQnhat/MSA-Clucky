package com.clucky.notificationservice.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EmailSenderRetryPolicyTest {
    @Test
    void securityEmailsRetryConnectionFailuresUntilTheirOtpIsAboutToExpire() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        assertTrue(EmailSenderService.shouldRetry("SMTP_CONNECT_FAILED", true, 12, 3,
                now.plusSeconds(120), 30, now));
        assertFalse(EmailSenderService.shouldRetry("SMTP_CONNECT_FAILED", true, 12, 3,
                now.plusSeconds(20), 30, now));
        assertFalse(EmailSenderService.shouldRetry("SMTP_AUTH_FAILED", true, 1, 3,
                now.plusSeconds(120), 30, now));
        assertFalse(EmailSenderService.shouldRetry("SMTP_CONNECT_FAILED", false, 3, 3,
                now.plusSeconds(120), 30, now));
        assertTrue(EmailSenderService.shouldRetry("SMTP_CONNECT_FAILED", false, 2, 3,
                now.plusSeconds(120), 30, now));
    }
}
