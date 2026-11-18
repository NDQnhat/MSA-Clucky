package com.clucky.identityservice.controller;

import com.clucky.identityservice.config.JwtSettings;
import com.clucky.identityservice.dto.Role;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import com.clucky.identityservice.repository.UserRepository;
import com.clucky.identityservice.service.PhoneNumbers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * The Loyalty service can claim guest points only for contacts whose ownership
 * Identity has independently verified by OTP. No raw contact leaves Identity.
 */
@RestController
@RequestMapping("/internal/identity")
public class VerifiedLoyaltyContactsController {
    private final UserRepository users;
    private final byte[] key;

    public VerifiedLoyaltyContactsController(UserRepository users, JwtSettings jwt) {
        this.users = users;
        this.key = jwt.secret().getBytes(StandardCharsets.UTF_8);
    }

    public record Request(@Positive long customerId) {}
    public record VerifiedContacts(String phoneKey, String emailKey, long phoneBindingVersion) {}

    @PostMapping("/loyalty-contacts/resolve")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<VerifiedContacts> verified(@AuthenticationPrincipal Jwt service,
            @Valid @RequestBody Request request) {
        if (!"promotion-loyalty-service".equals(service.getClaimAsString("service"))
                || !Long.toString(Long.MAX_VALUE - 1).equals(service.getSubject())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        var user = users.findById(request.customerId())
                .filter(account -> account.getRole() == Role.CUSTOMER
                        && !account.isCurrentBranchAccount()
                        && "ACTIVE".equals(account.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        String phone = Boolean.TRUE.equals(user.getPhoneVerified()) && user.getPhone() != null
                ? keyed("clucky:loyalty:phone-key:v1:", PhoneNumbers.normalize(user.getPhone())) : null;
        String email = Boolean.TRUE.equals(user.getEmailVerified()) && user.getEmail() != null
                ? keyed("clucky:loyalty:email-key:v1:",
                        user.getEmail().trim().toLowerCase(java.util.Locale.ROOT)) : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new VerifiedContacts(phone, email, user.getPhoneBindingVersion()));
    }

    private String keyed(String domain, String value) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal((domain + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Contact key unavailable");
        }
    }
}
