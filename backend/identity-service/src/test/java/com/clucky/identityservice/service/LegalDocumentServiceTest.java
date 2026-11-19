package com.clucky.identityservice.service;

import com.clucky.identityservice.dto.auth.LegalConsentRequest;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
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

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void actor(String role) {
        Jwt token = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject("7").claim("role", role).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token, AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private static Map<String, Object> row(String type, Long id, Integer version) {
        Map<String, Object> result = new HashMap<>();
        result.put("document_type", type);
        result.put("id", id);
        result.put("version", version);
        result.put("published_at", id == null ? null : Timestamp.from(Instant.parse("2026-11-19T00:00:00Z")));
        return result;
    }

    private void publication(Long termsId, Integer termsVersion, Long privacyId, Integer privacyVersion) {
        when(jdbc.queryForList(startsWith("SELECT d.document_type, d.published_revision_id"),
                eq("TERMS_OF_SERVICE"), eq("PRIVACY_POLICY"))).thenReturn(List.of(
                        row("PRIVACY_POLICY", privacyId, privacyVersion),
                        row("TERMS_OF_SERVICE", termsId, termsVersion)));
    }

    private void published() {
        publication(40L, 7, 80L, 4);
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
        verify(jdbc).queryForList(argThat(sql -> sql.contains("LEFT JOIN") && sql.contains("FOR SHARE OF d")),
                eq("TERMS_OF_SERVICE"), eq("PRIVACY_POLICY"));
    }

    @Test
    void missingCheckboxAndStaleRevisionFailClosed() {
        published();
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> legal.requireCurrentConsent(null)).getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class,
                        () -> legal.requireCurrentConsent(new LegalConsentRequest(false, 7, 4))).getCode());
        ApiException stale = assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(new LegalConsentRequest(true, 6, 4)));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, stale.getCode());
        assertEquals("OUTDATED", stale.getFieldErrors().get("consent"));
    }

    @Test
    void noPublishedDocumentsAllowLegacySignupWithoutRecordingSyntheticConsent() {
        publication(null, null, null, null);
        assertNull(legal.requireCurrentConsent(null));
        legal.recordAcceptance(9L, null, "PHONE_REGISTER");
        verify(jdbc, never()).update(startsWith("INSERT INTO identity_service.legal_consents"),
                any(), any(), any(), any());
        ApiException synthetic = assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(new LegalConsentRequest(true, 7, 4)));
        assertEquals(ErrorCode.VALIDATION_ERROR, synthetic.getCode());
        assertEquals("NOT_REQUIRED", synthetic.getFieldErrors().get("consent"));
    }

    @Test
    void onlyTermsPublishedRequiresOnlyTermsAndRecordsNullablePrivacy() {
        publication(40L, 7, null, null);
        assertEquals("REQUIRED", assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(null)).getFieldErrors().get("consent"));
        var approved = legal.requireCurrentConsent(new LegalConsentRequest(true, 7, null));
        assertEquals(40L, approved.termsId());
        assertNull(approved.privacyId());
        legal.recordAcceptance(9L, approved, "PHONE_REGISTER");
        verify(jdbc).update(startsWith("INSERT INTO identity_service.legal_consents"),
                eq(9L), eq(40L), isNull(), eq("PHONE_REGISTER"));
        assertEquals("OUTDATED", assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(new LegalConsentRequest(true, 7, 5)))
                .getFieldErrors().get("consent"));
    }

    @Test
    void onlyPrivacyPublishedRequiresOnlyPrivacyAndRecordsNullableTerms() {
        publication(null, null, 80L, 4);
        var approved = legal.requireCurrentConsent(new LegalConsentRequest(true, null, 4));
        assertNull(approved.termsId());
        assertEquals(80L, approved.privacyId());
        legal.recordAcceptance(10L, approved, "GOOGLE_COMPLETE");
        verify(jdbc).update(startsWith("INSERT INTO identity_service.legal_consents"),
                eq(10L), isNull(), eq(80L), eq("GOOGLE_COMPLETE"));
        assertEquals("REQUIRED", assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(new LegalConsentRequest(true, null, null)))
                .getFieldErrors().get("consent"));
        assertEquals("OUTDATED", assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(new LegalConsentRequest(true, 8, 4)))
                .getFieldErrors().get("consent"));
    }

    @Test
    void missingDatabaseRowOrUnpublishedRevisionDoesNotBecomeConsentBypass() {
        when(jdbc.queryForList(startsWith("SELECT d.document_type, d.published_revision_id"),
                eq("TERMS_OF_SERVICE"), eq("PRIVACY_POLICY")))
                .thenReturn(List.of(row("TERMS_OF_SERVICE", null, null)));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(null)).getCode());
        publication(null, null, null, null);
        var corrupt = row("TERMS_OF_SERVICE", 40L, 7);
        corrupt.put("published_at", null);
        when(jdbc.queryForList(startsWith("SELECT d.document_type, d.published_revision_id"),
                eq("TERMS_OF_SERVICE"), eq("PRIVACY_POLICY")))
                .thenReturn(List.of(corrupt, row("PRIVACY_POLICY", null, null)));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> legal.requireCurrentConsent(null)).getCode());
        verify(jdbc, never()).update(startsWith("INSERT INTO identity_service.legal_consents"),
                any(), any(), any(), any());
    }

    @Test
    void publicStatusReflectsEachPublishedDocumentIndependently() {
        when(jdbc.queryForList(startsWith("SELECT d.document_type, ("), eq("TERMS_OF_SERVICE"),
                eq("PRIVACY_POLICY"))).thenReturn(
                        List.of(Map.of("document_type", "TERMS_OF_SERVICE", "published", false),
                                Map.of("document_type", "PRIVACY_POLICY", "published", false)),
                        List.of(Map.of("document_type", "TERMS_OF_SERVICE", "published", true),
                                Map.of("document_type", "PRIVACY_POLICY", "published", false)),
                        List.of(Map.of("document_type", "TERMS_OF_SERVICE", "published", false),
                                Map.of("document_type", "PRIVACY_POLICY", "published", true)),
                        List.of(Map.of("document_type", "TERMS_OF_SERVICE", "published", true),
                                Map.of("document_type", "PRIVACY_POLICY", "published", true)));
        assertEquals(new LegalDocumentService.PublicStatus(false, false, false, false), legal.publicStatus());
        assertEquals(new LegalDocumentService.PublicStatus(true, true, false, false), legal.publicStatus());
        assertEquals(new LegalDocumentService.PublicStatus(true, false, true, false), legal.publicStatus());
        assertEquals(new LegalDocumentService.PublicStatus(true, true, true, true), legal.publicStatus());
    }

    @Test
    void unpublishRequiresAdminAndAuditsOnlyTheExpectedPublishedRevision() {
        actor("STAFF");
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(ApiException.class,
                () -> legal.unpublish("TERMS_OF_SERVICE", 7)).getCode());
        verifyNoInteractions(jdbc, audit);

        actor("ADMIN");
        when(jdbc.queryForMap(startsWith("SELECT d.published_revision_id"), eq("TERMS_OF_SERVICE")))
                .thenReturn(Map.of("published_revision_id", 40L, "published_version", 7));
        legal.unpublish("TERMS_OF_SERVICE", 7);
        verify(jdbc).queryForMap(argThat(sql -> sql.contains("FOR UPDATE OF d")), eq("TERMS_OF_SERVICE"));
        verify(jdbc).update(startsWith("UPDATE identity_service.legal_documents SET published_revision_id=NULL"),
                eq("TERMS_OF_SERVICE"));
        verify(audit).record(eq(7L), eq("ADMIN"), isNull(), isNull(),
                eq("LEGAL_VERSION_UNPUBLISHED"), eq(40L), eq("LegalRevision"),
                eq(Map.of("type", "TERMS_OF_SERVICE", "version", 7)), isNull());
    }

    @Test
    void unpublishNeverClearsNewlyPublishedOrAlreadyRemovedRevision() {
        actor("ADMIN");
        when(jdbc.queryForMap(startsWith("SELECT d.published_revision_id"), eq("PRIVACY_POLICY")))
                .thenReturn(Map.of("published_revision_id", 80L, "published_version", 5))
                .thenReturn(Map.of("published_revision_id", 80L, "published_version", 5));
        assertEquals("OUTDATED", assertThrows(ApiException.class,
                () -> legal.unpublish("PRIVACY_POLICY", 4)).getFieldErrors().get("published"));
        var absent = new HashMap<String, Object>();
        absent.put("published_revision_id", null);
        absent.put("published_version", null);
        when(jdbc.queryForMap(startsWith("SELECT d.published_revision_id"), eq("PRIVACY_POLICY")))
                .thenReturn(absent);
        assertEquals("OUTDATED", assertThrows(ApiException.class,
                () -> legal.unpublish("PRIVACY_POLICY", 5)).getFieldErrors().get("published"));
        verify(jdbc, never()).update(startsWith("UPDATE identity_service.legal_documents SET published_revision_id=NULL"),
                (Object) any());
        verifyNoInteractions(audit);
    }

    @Test
    void staleAdminDraftCannotOverwriteNewerDraftEvenWhenRevisionCounterAdvanced() {
        assertDoesNotThrow(() -> LegalDocumentService.requireExpectedDraft(5, 5, 5, 5));
        ApiException overwritten = assertThrows(ApiException.class,
                () -> LegalDocumentService.requireExpectedDraft(5, 5, 6, 6));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, overwritten.getCode());
        assertEquals("OUTDATED", overwritten.getFieldErrors().get("draft"));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(ApiException.class,
                () -> LegalDocumentService.requireExpectedDraft(5, null, 5, 5)).getCode());
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(ApiException.class,
                () -> LegalDocumentService.requireExpectedDraft(null, null, 0, null)).getCode());
        assertDoesNotThrow(() -> LegalDocumentService.requireExpectedDraft(0, null, 0, null));
    }
}
