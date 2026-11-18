package com.clucky.identityservice.service;

import com.clucky.identityservice.config.AuthProperties;
import com.clucky.identityservice.dto.BranchAccountData.RequestChallenge;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchAccountChallengeTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OtpService otp = mock(OtpService.class);
    private final BranchAccountService service = new BranchAccountService(jdbc, otp,
            mock(AuthProperties.class), mock(PasswordEncoder.class),
            mock(RefreshTokenService.class), mock(IdentityAuditService.class));

    @Test
    void opaqueChallengeIsThrottledAndRejectedBeforeGlobalBranchLock() {
        UUID unknownId = UUID.randomUUID();
        when(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity_service.branch_challenges WHERE id=?)",
                Boolean.class, unknownId)).thenReturn(false);
        assertEquals(ErrorCode.INVALID_OTP,
                assertThrows(ApiException.class, () -> service.precheckFinish(unknownId)).getCode());
        verify(otp).limitVerification("branch-challenge:" + unknownId, "BRANCH");
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void realChallengeUsesSamePreflightLimiter() {
        UUID realId = UUID.randomUUID();
        when(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity_service.branch_challenges WHERE id=?)",
                Boolean.class, realId)).thenReturn(true);
        service.precheckFinish(realId);
        verify(otp).limitVerification("branch-challenge:" + realId, "BRANCH");
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void resendActivationReplacesOldPendingChallengeAndTargetsBranchRecoveryEmail() {
        when(jdbc.queryForList(anyString(), eq("staff.7"), eq("staff.7"))).thenReturn(List.of(Map.of(
                "id", 17L, "role", "STAFF", "status", "ACTIVE", "current_branch_account", true,
                "email_verified", false, "store_id", 7L)));
        when(jdbc.queryForList("SELECT * FROM identity_service.branch_accounts WHERE store_id=? FOR UPDATE", 7L))
                .thenReturn(List.of(Map.of("recovery_email", "recovery@branch.com", "version", 0L)));
        var resent = service.request(new RequestChallenge("STAFF.7", "ACTIVATE"));
        assertNotNull(resent.challengeId());
        verify(jdbc).update(contains("SET used=TRUE WHERE target_id=? AND purpose=?"), eq(17L), eq("ACTIVATE"));
        verify(jdbc).update(contains("INSERT INTO identity_service.branch_challenges"),
                eq(resent.challengeId()), eq(17L), eq(7L), eq("ACTIVATE"),
                isNull(), isNull(), eq(0L), eq("recovery@branch.com"), isNull(), any());
    }

    @Test
    void passwordResetReissueUsesTheCurrentSharedRecoveryContact() {
        when(jdbc.queryForList(anyString(), eq("manager.7"), eq("manager.7"))).thenReturn(List.of(Map.of(
                "id", 16L, "role", "STORE_MANAGER", "status", "ACTIVE", "current_branch_account", true,
                "email_verified", true, "store_id", 7L, "email", "obsolete@personal.com")));
        when(jdbc.queryForList("SELECT * FROM identity_service.branch_accounts WHERE store_id=? FOR UPDATE", 7L))
                .thenReturn(List.of(Map.of("recovery_email", "shared@branch.com", "version", 3L)));
        var issued = service.request(new RequestChallenge("manager.7", "RESET"));
        verify(jdbc).update(contains("SET used=TRUE WHERE target_id=? AND purpose=?"), eq(16L), eq("RESET"));
        verify(jdbc).update(contains("INSERT INTO identity_service.branch_challenges"),
                eq(issued.challengeId()), eq(16L), eq(7L), eq("RESET"),
                isNull(), isNull(), eq(3L), eq("shared@branch.com"), isNull(), any());
    }

    @Test
    void completedActivationDoesNotIssueAnotherActivationCode() {
        when(jdbc.queryForList(anyString(), eq("staff.7"), eq("staff.7"))).thenReturn(List.of(Map.of(
                "id", 17L, "role", "STAFF", "status", "ACTIVE", "current_branch_account", true,
                "email_verified", true, "store_id", 7L)));
        assertNotNull(service.request(new RequestChallenge("staff.7", "ACTIVATE")).challengeId());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void retiredBranchAccountNeverResendsPasswordResetToPersonalEmail() {
        when(jdbc.queryForList(anyString(), eq("staff-old@personal.com"), eq("staff-old@personal.com")))
                .thenReturn(List.of(Map.of("id", 22L, "role", "STAFF", "status", "ACTIVE",
                        "current_branch_account", false, "email_verified", true,
                        "email", "staff-old@personal.com")));
        assertNotNull(service.request(new RequestChallenge("staff-old@personal.com", "RESET")).challengeId());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
