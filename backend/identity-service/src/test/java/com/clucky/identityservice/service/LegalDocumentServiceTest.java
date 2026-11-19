package com.clucky.identityservice.service;

import com.clucky.identityservice.dto.auth.LegalConsentRequest;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegalDocumentServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private LegalDocumentService legal;

    @BeforeEach
    void init() {
        legal = new LegalDocumentService(jdbc, audit);
    }

    private void published() {
        when(jdbc.queryForList(startsWith("SELECT d.document_type"), eq("TERMS_OF_SERVICE"),
                eq("PRIVACY_POLICY"))).thenReturn(List.of(
                        Map.of("document_type", "PRIVACY_POLICY", "id", 80L, "version", 4),
                        Map.of("document_type", "TERMS_OF_SERVICE", "id", 40L, "version", 7)));
    }

    @Test
    void sanitizedHtmlRemovesScriptsEventsImagesAndUnsafeLinks() {
        String safe = LegalDocumentService.sanitize(
                "<h2 onclick='alert(1)'>Safe</h2><script>alert(9)</script>"
                + "<p><a href='javascript:alert(2)' target='_blank'>Click</a>"
                + "<img src='x' onerror='alert(3)'> body</p>");
        assertTrue(safe.contains("<h2>Safe</h2>"));
        assertFalse(safe.toLowerCase().contains("<script"));
        assertFalse(safe.toLowerCase().contains("onclick"));
        assertFalse(safe.toLowerCase().contains("onerror"));
        assertFalse(safe.toLowerCase().contains("javascript:"));
        assertFalse(safe.contains("<img"));
        assertFalse(safe.contains("target="));
    }

    @Test
    void emptyOrMarkupOnlyIsRejected() {
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> LegalDocumentService.sanitize("<img src='x'>")).getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> LegalDocumentService.sanitize("<script>evil()</script>")).getCode());
    }

    @Test
    void exactCurrentPublishedPairIsRequiredAndRecordedByServer() {
        published();
        var revision = legal.requireCurrentConsent(new LegalConsentRequest(true, 7, 4));
        assertEquals(40L, revision.termsId());
        assertEquals(80L, revision.privacyId());
        legal.recordAcceptance(9L, revision, "PHONE_REGISTER");
        verify(jdbc).update(startsWith("INSERT INTO identity_service.legal_consents"),
                eq(9L), eq(40L), eq(80L), eq("PHONE_REGISTER"));
    }

    @Test
    void missingCheckboxAndStaleRevisionFailClosed() {
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> legal.requireCurrentConsent(null)).getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class,
                        () -> legal.requireCurrentConsent(new LegalConsentRequest(false, 7, 4))).getCode());
        published();
        ApiException stale = assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(new LegalConsentRequest(true, 6, 4)));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, stale.getCode());
        assertEquals("OUTDATED", stale.getFieldErrors().get("consent"));
    }

    @Test
    void incompletePublicationCannotBeAccepted() {
        when(jdbc.queryForList(startsWith("SELECT d.document_type"), eq("TERMS_OF_SERVICE"),
                eq("PRIVACY_POLICY"))).thenReturn(List.of(
                        Map.of("document_type", "TERMS_OF_SERVICE", "id", 40L, "version", 7)));
        ApiException missing = assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(new LegalConsentRequest(true, 7, 4)));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, missing.getCode());
        assertEquals("NOT_PUBLISHED", missing.getFieldErrors().get("consent"));
    }

    @Test
    void explicitlyDisabledForStagedRolloutAllowsLegacyClientsWithoutForgingConsent() {
        legal.setConsentRequired(false);
        assertNull(legal.requireCurrentConsent(null));
        legal.recordAcceptance(9L, null, "PHONE_REGISTER");
        verifyNoInteractions(jdbc);
    }
}
