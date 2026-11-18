package com.clucky.promotionloyaltyservice.controller;

import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.dto.Role;
import com.clucky.promotionloyaltyservice.dto.RejectVoucherRequest;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherRequest;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherResponse;
import com.clucky.promotionloyaltyservice.dto.VoucherRequest;
import com.clucky.promotionloyaltyservice.dto.VoucherResponse;
import com.clucky.promotionloyaltyservice.exception.ApiException;
import com.clucky.promotionloyaltyservice.exception.ErrorCode;
import com.clucky.promotionloyaltyservice.model.VoucherScope;
import com.clucky.promotionloyaltyservice.model.VoucherCampaignKind;
import com.clucky.promotionloyaltyservice.model.VoucherApplicability;
import com.clucky.promotionloyaltyservice.model.VoucherStatus;
import com.clucky.promotionloyaltyservice.service.VoucherService;
import com.clucky.promotionloyaltyservice.service.PersonalVoucherService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/promotions/vouchers")
public class VoucherController {

    private final VoucherService voucherService;
    private final PersonalVoucherService personal;

    @Autowired
    public VoucherController(VoucherService voucherService, PersonalVoucherService personal) {
        this.voucherService = voucherService;
        this.personal = personal;
    }
    public VoucherController(VoucherService voucherService) {
        this(voucherService, null);
    }

    /**
     * Validate a voucher for an order (called by customer app or internal services during checkout).
     * POST /api/promotions/vouchers/validate
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidateVoucherResponse> validateVoucher(@Valid @RequestBody ValidateVoucherRequest request) {
        String code = request.getCode() == null ? "" : request.getCode().strip().toUpperCase(java.util.Locale.ROOT);
        boolean personalCode = code.startsWith("CK1") || code.startsWith("CLUCKY:V1:");
        ValidateVoucherResponse response = request.getVoucherInstanceId() != null || personalCode
                ? personal.validate(request) : voucherService.validateVoucher(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{campaignId}/claim")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PersonalVoucherService.PersonalVoucherView claim(@PathVariable Long campaignId) {
        return personal.claim(campaignId, CurrentActor.require().userId());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<PersonalVoucherService.PersonalVoucherView> mine() {
        return personal.mine(CurrentActor.require().userId());
    }

    @GetMapping("/my/{instanceId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PersonalVoucherService.PersonalVoucherView myDetail(@PathVariable UUID instanceId) {
        return personal.detail(instanceId, CurrentActor.require().userId());
    }

    /**
     * Get list of currently active and valid vouchers for customers.
     * GET /api/promotions/vouchers/available?storeId=...
     */
    @GetMapping("/available")
    public ResponseEntity<List<VoucherResponse>> getAvailableVouchers(@RequestParam(name = "storeId", required = false) Long storeId) {
        List<VoucherResponse> vouchers = voucherService.getAvailableVouchers(storeId);
        return ResponseEntity.ok(vouchers);
    }

    /**
     * Query vouchers with filters (Admin and Store Manager views).
     * GET /api/promotions/vouchers
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<List<VoucherResponse>> getVouchers(
        @RequestParam(name = "scope", required = false) VoucherScope scope,
        @RequestParam(name = "storeId", required = false) Long storeId,
        @RequestParam(name = "status", required = false) VoucherStatus status,
        @RequestParam(name = "keyword", required = false) String keyword
    ) {
        CurrentActor actor = CurrentActor.require();
        if (actor.role() != Role.ADMIN && actor.role() != Role.STORE_MANAGER) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        Long effectiveStoreId = storeId;
        if (actor.role() == Role.STORE_MANAGER) {
            if (actor.storeId() == null || (storeId != null && !storeId.equals(actor.storeId()))) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            effectiveStoreId = actor.storeId();
            // Multi-store and ALL personal campaigns also apply to this manager's store.
            // VoucherService filters by the authenticated storeId below.
            scope = null;
        }

        List<VoucherResponse> vouchers = voucherService.getVouchers(scope, effectiveStoreId, status, keyword);
        return ResponseEntity.ok(vouchers);
    }

    /**
     * Get voucher details by ID.
     * GET /api/promotions/vouchers/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<VoucherResponse> getVoucherById(@PathVariable("id") Long id) {
        CurrentActor actor = CurrentActor.require();
        if (actor.role() != Role.ADMIN && actor.role() != Role.STORE_MANAGER) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        VoucherResponse voucher = voucherService.getVoucherById(id);
        if (actor.role() == Role.STORE_MANAGER
                && (actor.storeId() == null || !(actor.storeId().equals(voucher.getStoreId())
                    || voucher.getStoreIds() != null && voucher.getStoreIds().contains(actor.storeId())
                    || voucher.getCampaignKind() == VoucherCampaignKind.LEGACY
                        && voucher.getScope() == VoucherScope.ALL
                    || voucher.getCampaignKind() == VoucherCampaignKind.PERSONAL
                        && voucher.getApplicability() == VoucherApplicability.ALL))) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return ResponseEntity.ok(voucher);
    }

    /**
     * Create a new voucher (Admin / Store Manager).
     * POST /api/promotions/vouchers
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VoucherResponse> createVoucher(
        @Valid @RequestBody VoucherRequest request
    ) {
        CurrentActor actor = CurrentActor.require();
        VoucherResponse created = voucherService.createVoucher(
            request,
            actor.userId(),
            actor.role() != null ? actor.role().name() : null,
            actor.storeId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Store proposal endpoint for Store Manager to submit a voucher proposal.
     * Enforces storeId from authenticated actor, scope STORE, and status PENDING_APPROVAL.
     * POST /api/promotions/vouchers/store-proposals
     */
    @PostMapping("/store-proposals")
    @PreAuthorize("hasRole('STORE_MANAGER')")
    public ResponseEntity<VoucherResponse> createStoreProposal(
        @Valid @RequestBody VoucherRequest request
    ) {
        CurrentActor actor = CurrentActor.require();
        VoucherResponse created = voucherService.createStoreProposal(
            request,
            actor.userId(),
            actor.role() != null ? actor.role().name() : null,
            actor.storeId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Admin approves a voucher proposal, transitioning it from PENDING_APPROVAL to ACTIVE.
     * Manager or unauthorized caller is rejected with FORBIDDEN.
     * PUT /api/promotions/vouchers/{id}/approve
     */
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VoucherResponse> approveVoucher(
        @PathVariable("id") Long id
    ) {
        CurrentActor actor = CurrentActor.require();
        VoucherResponse approved = voucherService.approveVoucher(
            id,
            actor.userId(),
            actor.role() != null ? actor.role().name() : null
        );
        return ResponseEntity.ok(approved);
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VoucherResponse> rejectVoucher(
        @PathVariable("id") Long id,
        @Valid @RequestBody RejectVoucherRequest request
    ) {
        CurrentActor actor = CurrentActor.require();
        return ResponseEntity.ok(voucherService.rejectVoucher(id, request, actor.userId(), actor.role().name()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VoucherResponse> updateVoucher(
        @PathVariable("id") Long id,
        @Valid @RequestBody VoucherRequest request
    ) {
        CurrentActor actor = CurrentActor.require();
        VoucherResponse updated = voucherService.updateVoucher(
            id,
            request,
            actor.userId(),
            actor.role() != null ? actor.role().name() : null,
            actor.storeId()
        );
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete or disable a voucher.
     * DELETE /api/promotions/vouchers/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteVoucher(
        @PathVariable("id") Long id
    ) {
        CurrentActor actor = CurrentActor.require();
        voucherService.deleteVoucher(
            id,
            actor.userId(),
            actor.role() != null ? actor.role().name() : null,
            actor.storeId()
        );
        return ResponseEntity.noContent().build();
    }

}
