package com.clucky.identityservice.controller;

import com.clucky.identityservice.dto.Role;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import com.clucky.identityservice.repository.UserRepository;
import com.clucky.identityservice.service.IdentityChallenges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lookup for earn-only Loyalty wallet resolution; never exposes contact PII. */
@RestController
@RequestMapping("/internal/identity")
public class VerifiedEmailResolverController {
    private final UserRepository users;

    public VerifiedEmailResolverController(UserRepository users) {
        this.users = users;
    }

    public record ResolveRequest(@NotBlank @Email @Size(max = 255) String email) {}
    public record ResolveResponse(long customerId) {}

    @PostMapping("/verified-email/resolve")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<ResolveResponse> resolve(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResolveRequest request) {
        if (!"promotion-loyalty-service".equals(jwt.getClaimAsString("service"))
                || !Long.toString(Long.MAX_VALUE - 1).equals(jwt.getSubject())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String email = IdentityChallenges.normalizedEmail(request.email());
        var user = users.findByEmail(email)
                .filter(candidate -> candidate.getRole() == Role.CUSTOMER
                        && !candidate.isCurrentBranchAccount()
                        && "ACTIVE".equals(candidate.getStatus())
                        && Boolean.TRUE.equals(candidate.getEmailVerified()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ResolveResponse(user.getId()));
    }
}
