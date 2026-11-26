package com.clucky.promotionloyaltyservice.controller;

import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.config.LocaleResolverHelper;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.CreatePrintBatchRequest;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.PrintBatchResponse;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.PrintQuotaSummaryResponse;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.RevokePrintBatchRequest;
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
import com.clucky.promotionloyaltyservice.service.PrintedVoucherService;
import com.clucky.promotionloyaltyservice.service.VoucherService;
import com.clucky.promotionloyaltyservice.service.PersonalVoucherService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private final PrintedVoucherService printed;

    @Autowired
    public VoucherController(VoucherService voucherService, PersonalVoucherService personal, PrintedVoucherService printed) {
        this.voucherService = voucherService;
        this.personal = personal;
        this.printed = printed;
    }
    public VoucherController(VoucherService voucherService, PersonalVoucherService personal) {
        this(voucherService, personal, null);
    }
    public VoucherController(VoucherService voucherService) {
        this(voucherService, null, null);
    }

    /**
     * Validate a voucher for an order (called by customer app or internal services during checkout).
     * POST /api/promotions/vouchers/validate
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidateVoucherResponse> validateVoucher(
            @Valid @RequestBody ValidateVoucherRequest request,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        String resolvedLang = LocaleResolverHelper.resolve(lang, acceptLanguage);
        String code = request.getCode() == null ? "" : request.getCode().strip().toUpperCase(java.util.Locale.ROOT);
        boolean printedCode = PrintedVoucherService.isPrintedCode(code);
        boolean personalCode = code.startsWith("CK1") || code.startsWith("CLUCKY:V1:");
        ValidateVoucherResponse response;
        if (printedCode) {
            if (printed == null) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            response = printed.validate(request, resolvedLang);
        } else if (request.getVoucherInstanceId() != null || personalCode) {
            response = personal.validate(request, resolvedLang);
        } else {
            response = voucherService.validateVoucher(request, resolvedLang);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{campaignId}/claim")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PersonalVoucherService.PersonalVoucherView claim(
            @PathVariable Long campaignId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return personal.claim(campaignId, CurrentActor.require().userId(), LocaleResolverHelper.resolve(lang, acceptLanguage));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<PersonalVoucherService.PersonalVoucherView> mine(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return personal.mine(CurrentActor.require().userId(), LocaleResolverHelper.resolve(lang, acceptLanguage));
    }

    @GetMapping("/my/{instanceId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PersonalVoucherService.PersonalVoucherView myDetail(
            @PathVariable UUID instanceId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return personal.detail(instanceId, CurrentActor.require().userId(), LocaleResolverHelper.resolve(lang, acceptLanguage));
    }

    /**
     * Get list of currently active and valid vouchers for customers.
     * GET /api/promotions/vouchers/available?storeId=...
     */
    @GetMapping("/available")
    public ResponseEntity<List<VoucherResponse>> getAvailableVouchers(
            @RequestParam(name = "storeId", required = false) Long storeId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        List<VoucherResponse> vouchers = voucherService.getAvailableVouchers(storeId, LocaleResolverHelper.resolve(lang, acceptLanguage));
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
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "lang", required = false) String lang,
        @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
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

        List<VoucherResponse> vouchers = voucherService.getVouchers(scope, effectiveStoreId, status, keyword, LocaleResolverHelper.resolve(lang, acceptLanguage));
        return ResponseEntity.ok(vouchers);
    }

    /**
     * Get voucher details by ID.
     * GET /api/promotions/vouchers/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<VoucherResponse> getVoucherById(
            @PathVariable("id") Long id,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        CurrentActor actor = CurrentActor.require();
        if (actor.role() != Role.ADMIN && actor.role() != Role.STORE_MANAGER) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        VoucherResponse voucher = voucherService.getVoucherById(id, LocaleResolverHelper.resolve(lang, acceptLanguage));
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

    @GetMapping("/{id}/print-quota")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<PrintQuotaSummaryResponse> getPrintQuota(
            @PathVariable("id") Long id,
            @RequestParam(name = "storeId", required = false) Long storeId
    ) {
        return ResponseEntity.ok(printed.getPrintQuota(id, storeId));
    }

    @PostMapping("/{id}/print-batches")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<PrintBatchResponse> createPrintBatch(
            @PathVariable("id") Long id,
            @Valid @RequestBody CreatePrintBatchRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(printed.createBatch(id, request));
    }

    @GetMapping("/print-batches")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<List<PrintBatchResponse>> listPrintBatches(
            @RequestParam(name = "campaignId", required = false) Long campaignId,
            @RequestParam(name = "storeId", required = false) Long storeId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        return ResponseEntity.ok(printed.listBatches(campaignId, storeId, status, LocaleResolverHelper.resolve(lang, acceptLanguage)));
    }

    @GetMapping("/print-batches/{batchId}/pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<byte[]> exportPrintBatchPdf(
            @PathVariable("batchId") Long batchId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        PrintedVoucherService.BatchPdfResult result = printed.exportBatchPdf(batchId, LocaleResolverHelper.resolve(lang, acceptLanguage));
        String filename = "clucky-vouchers-" + result.batchCode().toLowerCase(java.util.Locale.ROOT) + ".pdf";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(result.pdfBytes().length)
                .body(result.pdfBytes());
    }

    @PostMapping("/print-batches/{batchId}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER')")
    public ResponseEntity<PrintBatchResponse> revokePrintBatch(
            @PathVariable("batchId") Long batchId,
            @Valid @RequestBody(required = false) RevokePrintBatchRequest request
    ) {
        return ResponseEntity.ok(printed.revokeBatch(batchId, request));
    }

}
