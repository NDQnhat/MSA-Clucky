package com.clucky.identityservice.service;

import com.clucky.identityservice.config.AuthProperties;
import com.clucky.identityservice.dto.BranchAccountData.OwnPasswordChangeConfirm;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchOwnPasswordChangeTest {
    private static final long ACTOR_ID = 17L;
    private static final long STORE_ID = 7L;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OtpService otp = mock(OtpService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final RefreshTokenService sessions = mock(RefreshTokenService.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final BranchAccountService service = new BranchAccountService(jdbc, otp,
            new AuthProperties(null, null, null, null, null), passwords, sessions, audit);

    @BeforeEach
    void setUp() {
        actor("STAFF", STORE_ID, "device-a");
        stubAccount("STAFF", STORE_ID, true, true, "ACTIVE");
        when(jdbc.queryForList("SELECT * FROM identity_service.branch_accounts WHERE store_id=? FOR UPDATE", STORE_ID))
                .thenReturn(List.of(Map.of("recovery_email", "shared@branch.example",
                        "email_verified", true, "version", 3L)));
        when(passwords.matches("OldPassword1", "hash-old")).thenReturn(true);
        when(passwords.encode("NewPassword1")).thenReturn("hash-new");
        when(otp.verifier(anyString())).thenReturn("0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void actor(String role, long storeId, String sid) {
        Jwt jwt = Jwt.withTokenValue("signed-test-token").header("alg", "HS256")
                .subject(String.valueOf(ACTOR_ID)).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("role", role).claim("storeId", storeId).claim("sid", sid).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void stubAccount(String role, long storeId, boolean current, boolean verified, String status) {
        Map<String, Object> account = new HashMap<>();
        account.put("id", ACTOR_ID);
        account.put("store_id", storeId);
        account.put("role", role);
        account.put("status", status);
        account.put("current_branch_account", current);
        account.put("email_verified", verified);
        account.put("password_hash", "hash-old");
        when(jdbc.queryForList("SELECT * FROM identity_service.users WHERE id=? FOR UPDATE", ACTOR_ID))
                .thenReturn(List.of(account));
    }

    @Test
    void staffChangeRequestUsesOnlyTheSharedRecoveryEmailAndBindsSession() {
        UUID challenge = service.requestOwnPasswordChange("OldPassword1").challengeId();
        assertNotNull(challenge);
        verify(otp).limitIssuance("branch-own-password:17", "BRANCH");
        verify(otp).limitVerification("branch-reauth:17", "BRANCH");
        verify(jdbc).update(contains("SET used=TRUE WHERE target_id=? AND purpose=?"),
                eq(ACTOR_ID), eq("RESET"));
        verify(jdbc).update(contains("INSERT INTO identity_service.branch_challenges"),
                eq(challenge), eq(ACTOR_ID), eq(STORE_ID), eq("RESET"),
                eq(ACTOR_ID), eq("device-a"), eq(3L), eq("shared@branch.example"),
                isNull(), any(Timestamp.class));
        verifyNoInteractions(sessions);
    }

    @Test
    void managerCanRequestOwnChangeButNotChangeAnotherStoreOrRole() {
        actor("STORE_MANAGER", STORE_ID, "device-manager");
        stubAccount("STORE_MANAGER", STORE_ID, true, true, "ACTIVE");
        assertNotNull(service.requestOwnPasswordChange("OldPassword1").challengeId());
        verify(jdbc).update(contains("INSERT INTO identity_service.branch_challenges"),
                any(UUID.class), eq(ACTOR_ID), eq(STORE_ID), eq("RESET"),
                eq(ACTOR_ID), eq("device-manager"), eq(3L), eq("shared@branch.example"),
                isNull(), any(Timestamp.class));
    }

    @Test
    void wrongCurrentPasswordDoesNotIssueChallenge() {
        assertEquals(ErrorCode.INVALID_CREDENTIALS,
                assertThrows(ApiException.class, () -> service.requestOwnPasswordChange("WrongPassword1")).getCode());
        verify(jdbc, never()).update(contains("INSERT INTO identity_service.branch_challenges"), any(Object[].class));
    }

    @Test
    void retiredOrOtherStoreAccountsAreRejectedBeforeAnyChallenge() {
        stubAccount("STAFF", STORE_ID, false, true, "ACTIVE");
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.requestOwnPasswordChange("OldPassword1")).getCode());
        stubAccount("STAFF", STORE_ID + 1, true, true, "ACTIVE");
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.requestOwnPasswordChange("OldPassword1")).getCode());
        verify(jdbc, never()).update(contains("INSERT INTO identity_service.branch_challenges"), any(Object[].class));
    }

    private UUID pendingChallenge(String session) {
        UUID id = UUID.randomUUID();
        Map<String, Object> challenge = new HashMap<>();
        challenge.put("id", id);
        challenge.put("target_id", ACTOR_ID);
        challenge.put("store_id", STORE_ID);
        challenge.put("purpose", "RESET");
        challenge.put("actor_id", ACTOR_ID);
        challenge.put("session_id", session);
        challenge.put("recipient", "shared@branch.example");
        challenge.put("branch_version", 3L);
        challenge.put("expires_at", Timestamp.from(Instant.now().plusSeconds(300)));
        challenge.put("used", false);
        when(jdbc.queryForList("SELECT * FROM identity_service.branch_challenges WHERE id=? FOR UPDATE", id))
                .thenReturn(List.of(challenge));
        return id;
    }

    @Test
    void confirmedOtpUpdatesOnlyOwnPasswordAndRevokesEverySession() {
        UUID id = pendingChallenge("device-a");
        String code = service.code(id, "primary");
        service.finishOwnPasswordChange(new OwnPasswordChangeConfirm(id, code, "NewPassword1"));
        verify(jdbc).update(contains("UPDATE identity_service.users SET password_hash="),
                eq("hash-new"), eq(ACTOR_ID));
        verify(sessions).revokeAllForUser(ACTOR_ID);
        verify(jdbc).update("UPDATE identity_service.branch_challenges SET used=TRUE WHERE target_id=?", ACTOR_ID);
        verify(audit).record(eq(ACTOR_ID), eq("STAFF"), eq(STORE_ID), eq("device-a"),
                eq("ACCOUNT_PASSWORD_CHANGE"), eq(ACTOR_ID), eq("Account"), anyMap(), isNull());
    }

    @Test
    void anotherDeviceCannotConsumePasswordChangeChallenge() {
        UUID id = pendingChallenge("device-b");
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.finishOwnPasswordChange(
                        new OwnPasswordChangeConfirm(id, service.code(id, "primary"), "NewPassword1"))).getCode());
        verifyNoInteractions(sessions);
        verify(jdbc, never()).update(contains("UPDATE identity_service.users SET password_hash="), any(Object[].class));
    }

    @Test
    void badOtpAndCurrentPasswordReuseAreRejectedWithoutRevocation() {
        UUID id = pendingChallenge("device-a");
        assertEquals(ErrorCode.INVALID_OTP, assertThrows(ApiException.class,
                () -> service.finishOwnPasswordChange(new OwnPasswordChangeConfirm(id, "00000000", "NewPassword1")))
                .getCode());
        when(passwords.matches("OldPassword1", "hash-old")).thenReturn(true);
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(ApiException.class,
                () -> service.finishOwnPasswordChange(
                        new OwnPasswordChangeConfirm(id, service.code(id, "primary"), "OldPassword1"))).getCode());
        verifyNoInteractions(sessions);
    }
}
