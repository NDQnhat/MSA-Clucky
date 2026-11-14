package com.clucky.promotionloyaltyservice.controller;

import com.clucky.promotionloyaltyservice.config.AccessGuard;
import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.service.CounterLoyaltyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController
public class CounterLoyaltyController {
    private final CounterLoyaltyService counter;
    private final AccessGuard guard;
    public CounterLoyaltyController(CounterLoyaltyService counter, AccessGuard guard) {
        this.counter = counter; this.guard = guard;
    }
    public record LookupRequest(@NotBlank String kind, @NotBlank String value, @NotNull @Positive Long storeId) {}
    public record PreviewRequest(String loyaltySubject, String scanProof, int pointsUsed, @NotNull BigDecimal amount,
            @NotNull @Positive Long staffId, @NotNull @Positive Long storeId, String draftHash) {}
    public record ReserveRequest(@NotNull @Positive Long orderId, String loyaltySubject, String scanProof, int pointsUsed,
            @NotNull BigDecimal amount, long policyVersion, @NotNull BigDecimal pointsAmount,
            @NotNull BigDecimal tierDiscount, long pointsToEarn,
            @NotNull @Positive Long staffId, @NotNull @Positive Long storeId,
            String draftHash) {}

    @PostMapping("/api/loyalty/counter/lookup")
    @PreAuthorize("hasAnyRole('STAFF','STORE_MANAGER','ADMIN')")
    public CounterLoyaltyService.Lookup lookup(@Valid @RequestBody LookupRequest request) {
        guard.requireStore(request.storeId());
        return counter.lookup(request.kind(), request.value(), request.storeId());
    }
    @PostMapping("/internal/loyalty/counter/preview")
    public CounterLoyaltyService.CounterQuote preview(@Valid @RequestBody PreviewRequest request) {
        return counter.preview(new CounterLoyaltyService.CounterRequest(request.loyaltySubject(),
                request.scanProof(), request.pointsUsed(), request.amount(), request.staffId(),
                request.storeId(), request.draftHash()));
    }
    @PostMapping("/internal/loyalty/counter/reserve")
    public CounterLoyaltyService.CounterQuote reserve(@Valid @RequestBody ReserveRequest request) {
        return counter.reserve(new CounterLoyaltyService.CounterReserve(request.orderId(), request.loyaltySubject(),
                request.scanProof(), request.pointsUsed(), request.amount(), request.policyVersion(),
                request.pointsAmount(), request.tierDiscount(), request.pointsToEarn(),
                request.staffId(), request.storeId(), request.draftHash()));
    }
}
