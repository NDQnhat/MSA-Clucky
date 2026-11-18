package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.controller.VoucherController;
import com.clucky.promotionloyaltyservice.exception.ApiException;
import com.clucky.promotionloyaltyservice.exception.ErrorCode;
import com.clucky.promotionloyaltyservice.model.Voucher;
import com.clucky.promotionloyaltyservice.model.VoucherCampaignKind;
import com.clucky.promotionloyaltyservice.repository.VoucherRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PersonalVoucherServiceTest {
    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedCustomerCannotClaimLegacyCampaignOrAccessInstanceTable() {
        var campaigns = mock(VoucherRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(VoucherAuditOutbox.class);
        var service = new PersonalVoucherService(campaigns, jdbc, mock(StringRedisTemplate.class),
                audit, "test-hmac-key", "test-encryption-key", "unused");

        var legacy = new Voucher();
        legacy.setCampaignKind(VoucherCampaignKind.LEGACY);
        legacy.setStartAt(Instant.now().minusSeconds(60));
        legacy.setEndAt(Instant.now().plusSeconds(3600));
        when(campaigns.findByIdWithLock(42L)).thenReturn(Optional.of(legacy));

        var jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("7")
                .claim("role", "CUSTOMER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        var controller = new VoucherController(mock(VoucherService.class), service);
        var error = assertThrows(ApiException.class, () -> controller.claim(42L));
        assertEquals(ErrorCode.VOUCHER_NOT_FOUND, error.getCode());
        verify(campaigns).findByIdWithLock(42L);
        verifyNoInteractions(jdbc, audit);
    }
}
