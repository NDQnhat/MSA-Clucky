package com.clucky.promotionloyaltyservice.controller;

import com.clucky.promotionloyaltyservice.service.GuestLoyaltyClaimService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Authenticated Customer explicitly claims previously earned guest points. */
@RestController
@RequestMapping("/api/loyalty/claims/me")
@PreAuthorize("hasRole('CUSTOMER')")
public class GuestLoyaltyClaimController {
    private final GuestLoyaltyClaimService claims;
    public GuestLoyaltyClaimController(GuestLoyaltyClaimService claims) { this.claims = claims; }
    public record Confirm(Boolean confirmed) {}
    @GetMapping
    public ResponseEntity<GuestLoyaltyClaimService.Preview> preview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(claims.preview());
    }
    @PostMapping("/confirm")
    public ResponseEntity<GuestLoyaltyClaimService.Result> confirm(@RequestBody Confirm request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(claims.confirm(request != null && Boolean.TRUE.equals(request.confirmed())));
    }
}
