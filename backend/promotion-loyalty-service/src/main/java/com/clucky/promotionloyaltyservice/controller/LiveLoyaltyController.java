package com.clucky.promotionloyaltyservice.controller;

import com.clucky.promotionloyaltyservice.config.AccessGuard;
import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.service.LiveLoyaltySessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/loyalty")
public class LiveLoyaltyController {
    private final LiveLoyaltySessionService sessions;
    private final com.clucky.promotionloyaltyservice.service.CounterLoyaltyService counter;
    private final AccessGuard guard;
    public LiveLoyaltyController(LiveLoyaltySessionService sessions,
            com.clucky.promotionloyaltyservice.service.CounterLoyaltyService counter, AccessGuard guard) {
        this.sessions = sessions;
        this.counter = counter;
        this.guard = guard;
    }
    public record SessionRequest(@NotBlank String sessionId) {}
    public record Heartbeat(Instant serverTime) {}
    public record ScanRequest(@NotBlank String qrToken, @Positive @NotNull Long storeId, @NotBlank String draftHash) {}

    @PostMapping("/live-session/open")
    @PreAuthorize("hasRole('CUSTOMER')")
    public LiveLoyaltySessionService.CurrentCode open() {
        return sessions.open(CurrentActor.require().userId());
    }
    @GetMapping("/claim-status")
    @PreAuthorize("hasRole('CUSTOMER')")
    public com.clucky.promotionloyaltyservice.service.CounterLoyaltyService.ClaimStatus claimStatus() {
        return counter.claimStatus(CurrentActor.require().userId());
    }
    @PostMapping("/live-session/heartbeat")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Heartbeat heartbeat(@Valid @RequestBody SessionRequest request) {
        return new Heartbeat(sessions.heartbeat(CurrentActor.require().userId(), request.sessionId()));
    }
    @PostMapping("/live-session/rotate")
    @PreAuthorize("hasRole('CUSTOMER')")
    public LiveLoyaltySessionService.CurrentCode rotate(@Valid @RequestBody SessionRequest request) {
        return sessions.rotate(CurrentActor.require().userId(), request.sessionId());
    }
    @PostMapping("/live-session/close")
    @PreAuthorize("hasRole('CUSTOMER')")
    public void close(@Valid @RequestBody SessionRequest request) {
        sessions.close(CurrentActor.require().userId(), request.sessionId());
    }
    @PostMapping("/counter/loyalty/scan")
    @PreAuthorize("hasAnyRole('STAFF','STORE_MANAGER','ADMIN')")
    public LiveLoyaltySessionService.ScanResult scan(@Valid @RequestBody ScanRequest request) {
        guard.requireStore(request.storeId());
        return sessions.scan(request.qrToken(), CurrentActor.require().userId(),
                CurrentActor.require().role().name(), request.storeId(), request.draftHash());
    }
}
