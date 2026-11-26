package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.dto.LocalizedText;
import com.clucky.promotionloyaltyservice.dto.Role;
import com.clucky.promotionloyaltyservice.dto.RejectVoucherRequest;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherRequest;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherResponse;
import com.clucky.promotionloyaltyservice.dto.VoucherRequest;
import com.clucky.promotionloyaltyservice.dto.VoucherResponse;
import com.clucky.promotionloyaltyservice.exception.ApiException;
import com.clucky.promotionloyaltyservice.exception.BadRequestException;
import com.clucky.promotionloyaltyservice.exception.ConflictException;
import com.clucky.promotionloyaltyservice.exception.ErrorCode;
import com.clucky.promotionloyaltyservice.exception.ResourceNotFoundException;
import com.clucky.promotionloyaltyservice.model.Voucher;
import com.clucky.promotionloyaltyservice.model.VoucherApplicability;
import com.clucky.promotionloyaltyservice.model.VoucherCampaignKind;
import com.clucky.promotionloyaltyservice.model.VoucherScope;
import com.clucky.promotionloyaltyservice.model.VoucherStatus;
import com.clucky.promotionloyaltyservice.model.VoucherType;
import com.clucky.promotionloyaltyservice.repository.VoucherRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

@Service
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final VoucherAuditOutbox audit;
    private final VoucherStoreVerifier stores;

    public VoucherService(VoucherRepository voucherRepository, VoucherAuditOutbox audit, VoucherStoreVerifier stores) {
        this.voucherRepository = voucherRepository;
        this.audit = audit;
        this.stores = stores;
    }

    /**
     * Validate a voucher for an order amount and optional store.
     */
    @Transactional(readOnly = true)
    public ValidateVoucherResponse validateVoucher(ValidateVoucherRequest request) {
        return validateVoucher(request, "vi");
    }

    @Transactional(readOnly = true)
    public ValidateVoucherResponse validateVoucher(ValidateVoucherRequest request, String lang) {
        boolean isEn = "en".equalsIgnoreCase(lang);
        if (request == null || request.getCode() == null || request.getCode().trim().isEmpty()) {
            return ValidateVoucherResponse.invalid(null, ErrorCode.VALIDATION_ERROR.name(),
                    isEn ? "Voucher code is required." : "Mã ưu đãi không được để trống.");
        }

        String cleanCode = request.getCode().trim().toUpperCase(Locale.ROOT);
        if (cleanCode.startsWith("CLUCKY:LOYALTY:"))
            return ValidateVoucherResponse.invalid(null, ErrorCode.VOUCHER_NOT_FOUND.name(),
                    isEn ? "Voucher code not found." : "Không tìm thấy mã voucher.");
        Optional<Voucher> optVoucher = voucherRepository.findByCodeIgnoreCase(cleanCode);

        if (optVoucher.isEmpty()) {
            return ValidateVoucherResponse.invalid(null, ErrorCode.VOUCHER_NOT_FOUND.name(),
                    isEn ? "Voucher code not found." : "Không tìm thấy mã voucher.");
        }

        Voucher voucher = optVoucher.get();
        Instant now = Instant.now();

        // 1. Status Check
        if (voucher.getStatus() == VoucherStatus.DISABLED) {
            return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_INACTIVE.name(),
                    isEn ? "This voucher is temporarily disabled." : "Mã ưu đãi hiện đang tạm khóa.");
        }
        if (voucher.getStatus() == VoucherStatus.PENDING_APPROVAL) {
            return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_INACTIVE.name(),
                    isEn ? "This voucher is pending approval." : "Mã ưu đãi đang trong trạng thái chờ duyệt.");
        }
        if (voucher.getStatus() == VoucherStatus.REJECTED) {
            return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_INACTIVE.name(),
                    isEn ? "This voucher proposal was rejected." : "Mã ưu đãi đã bị từ chối phê duyệt.");
        }
        if (voucher.getStatus() != VoucherStatus.ACTIVE) {
            return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_INACTIVE.name(),
                    isEn ? "This voucher is not active." : "Mã ưu đãi không ở trạng thái khả dụng.");
        }

        // 2. Temporal Check
        if (now.isBefore(voucher.getStartAt())) {
            return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_NOT_STARTED.name(),
                    isEn ? "This voucher is not yet valid." : "Mã ưu đãi chưa đến thời gian áp dụng.");
        }
        if (now.isAfter(voucher.getEndAt())) {
            return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_EXPIRED.name(),
                    isEn ? "This voucher has expired." : "Mã ưu đãi đã hết hạn sử dụng.");
        }

        // 3. Usage Limits
        if (voucher.getTotalLimit() != null && voucher.getTotalLimit() > 0) {
            long used = (voucher.getUsedCount() != null ? voucher.getUsedCount() : 0L)
                    + voucher.getReservedCount()
                    + voucher.getPrintedUnredeemed();
            if (used >= voucher.getTotalLimit()) {
                return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_EXHAUSTED.name(),
                        isEn ? "This voucher has reached its usage limit." : "Mã ưu đãi đã hết lượt sử dụng.");
            }
        }

        // 4. Scope and Store validation
        if (voucher.getScope() == VoucherScope.STORE) {
            if (voucher.getStoreId() == null) {
                return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_SCOPE_MISMATCH.name(),
                        isEn ? "Invalid branch configuration for this voucher." : "Cấu hình chi nhánh áp dụng của mã không hợp lệ.");
            }
            if (request.getStoreId() == null) {
                return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_SCOPE_MISMATCH.name(),
                        isEn
                            ? "This voucher applies only to branch #" + voucher.getStoreId() + ". Please select a branch."
                            : "Mã ưu đãi chỉ áp dụng cho chi nhánh #" + voucher.getStoreId() + ". Vui lòng chọn chi nhánh đặt hàng.");
            }
            if (!voucher.getStoreId().equals(request.getStoreId())) {
                return ValidateVoucherResponse.invalid(cleanCode, ErrorCode.VOUCHER_SCOPE_MISMATCH.name(),
                        isEn
                            ? "This voucher applies only to branch #" + voucher.getStoreId() + ", not the selected branch."
                            : "Mã ưu đãi chỉ áp dụng tại chi nhánh #" + voucher.getStoreId() + ", không áp dụng cho chi nhánh hiện tại.");
            }
        }

        // 5. Minimum Order Amount
        BigDecimal orderAmount = request.getOrderAmount() != null ? request.getOrderAmount() : BigDecimal.ZERO;
        BigDecimal minOrder = voucher.getMinOrderAmount() != null ? voucher.getMinOrderAmount() : BigDecimal.ZERO;

        if (orderAmount.compareTo(minOrder) < 0) {
            BigDecimal missing = minOrder.subtract(orderAmount);
            return ValidateVoucherResponse.invalid(
                cleanCode,
                ErrorCode.MIN_ORDER_NOT_MET.name(),
                isEn
                    ? String.format("Minimum order amount is %sđ (%sđ more needed).",
                        minOrder.toPlainString(), missing.toPlainString())
                    : String.format("Đơn hàng chưa đạt mức tối thiểu %sđ (còn thiếu %sđ).",
                        minOrder.toPlainString(), missing.toPlainString())
            );
        }

        // 6. Calculate Discount Amount
        BigDecimal calculatedDiscount = BigDecimal.ZERO;
        if (voucher.getType() == VoucherType.PERCENT) {
            BigDecimal pct = voucher.getValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            calculatedDiscount = orderAmount.multiply(pct).setScale(0, RoundingMode.HALF_UP);

            if (voucher.getMaxDiscount() != null && voucher.getMaxDiscount().compareTo(BigDecimal.ZERO) > 0) {
                if (calculatedDiscount.compareTo(voucher.getMaxDiscount()) > 0) {
                    calculatedDiscount = voucher.getMaxDiscount();
                }
            }
        } else {
            calculatedDiscount = voucher.getValue();
        }

        // Discount cannot exceed total order amount
        if (calculatedDiscount.compareTo(orderAmount) > 0) {
            calculatedDiscount = orderAmount;
        }

        VoucherResponse voucherDto = VoucherResponse.fromEntity(voucher, now, lang);
        return ValidateVoucherResponse.valid(calculatedDiscount, voucherDto);
    }

    /**
     * Get available vouchers for customers.
     */
    @Transactional(readOnly = true)
    public List<VoucherResponse> getAvailableVouchers(Long storeId) {
        return getAvailableVouchers(storeId, "vi");
    }

    @Transactional(readOnly = true)
    public List<VoucherResponse> getAvailableVouchers(Long storeId, String lang) {
        Instant now = Instant.now();
        List<Voucher> list = voucherRepository.findAvailableVouchers(now, storeId);
        return list.stream()
            .filter(v -> v.getCampaignKind() != VoucherCampaignKind.PERSONAL || storeId == null
                    || v.getApplicability() == VoucherApplicability.ALL || v.getStoreIds().contains(storeId))
            .map(v -> VoucherResponse.fromPublicEntity(v, now, lang))
            .toList();
    }

    /**
     * Query vouchers with optional filters (Admin/Manager).
     */
    @Transactional(readOnly = true)
    public List<VoucherResponse> getVouchers(VoucherScope scope, Long storeId, VoucherStatus status, String keyword) {
        return getVouchers(scope, storeId, status, keyword, "vi");
    }

    @Transactional(readOnly = true)
    public List<VoucherResponse> getVouchers(VoucherScope scope, Long storeId, VoucherStatus status, String keyword, String lang) {
        Instant now = Instant.now();
        String cleanKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.strip();
        String needle = cleanKeyword != null ? LocalizedText.normalizeForSearch(cleanKeyword) : null;
        List<Voucher> list = voucherRepository.findWithFilters(scope, null, status, null);
        return list.stream()
            .filter(v -> storeId == null || (v.getCampaignKind() == VoucherCampaignKind.PERSONAL
                    ? v.getApplicability() == VoucherApplicability.ALL || v.getStoreIds().contains(storeId)
                    : v.getScope() == VoucherScope.ALL || Objects.equals(v.getStoreId(), storeId)))
            .filter(v -> needle == null
                    || (v.getCode() != null && LocalizedText.normalizeForSearch(v.getCode()).contains(needle))
                    || (v.getTitle() != null && v.getTitle().matches(cleanKeyword))
                    || (v.getDescription() != null && v.getDescription().matches(cleanKeyword)))
            .map(v -> VoucherResponse.fromEntity(v, now, lang))
            .toList();
    }

    /**
     * Get voucher details by ID.
     */
    @Transactional(readOnly = true)
    public VoucherResponse getVoucherById(Long id) {
        return getVoucherById(id, "vi");
    }

    @Transactional(readOnly = true)
    public VoucherResponse getVoucherById(Long id, String lang) {
        Voucher v = voucherRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy voucher với id: " + id));
        return VoucherResponse.fromEntity(v, Instant.now(), lang);
    }

    /**
     * Create a new voucher.
     */
    @Transactional
    public VoucherResponse createVoucher(VoucherRequest request, Long actorId, String actorRole, Long actorStoreId) {
        requireEditor(actorId, actorRole);
        if ("ADMIN".equalsIgnoreCase(actorRole)) validateAdminStatus(request);
        // RBAC: Store Manager can only propose/create vouchers for their assigned store
        if ("STORE_MANAGER".equalsIgnoreCase(actorRole)) {
            if (actorStoreId == null) {
                throw new BadRequestException("Store Manager chưa được phân công chi nhánh");
            }
            if (request.getStoreId() != null && !request.getStoreId().equals(actorStoreId)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            request.setScope(VoucherScope.STORE);
            request.setStoreId(actorStoreId);
            request.setApplicability(VoucherApplicability.SELECTED);
            request.setStoreIds(Set.of(actorStoreId));
            // By default, manager proposals require Admin approval and cannot enable printing
            request.setStatus(VoucherStatus.PENDING_APPROVAL);
            request.setPrintable(false);
            request.setMaxPrintRatio(new BigDecimal("0.5000"));
            request.setMaxPrintPerStore(null);
        } else if (actorRole != null && !"ADMIN".equalsIgnoreCase(actorRole)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }

        validateVoucherRequest(request, null);
        Voucher voucher = new Voucher();
        voucher.setCampaignKind(VoucherCampaignKind.PERSONAL);
        mapDtoToEntity(request, voucher);
        voucher.setCreatedBy(actorId);

        Voucher saved = voucherRepository.save(voucher);
        audit.record(actorId, actorRole, saved.getStoreId(), "VOUCHER_CREATED", "VOUCHER", saved.getId(), Map.of(), snapshot(saved));
        return VoucherResponse.fromEntity(saved);
    }

    /**
     * Store Manager creates a proposal for a voucher scoped to their assigned store.
     * Initial status is PENDING_APPROVAL and requires Admin approval before checkout use.
     * POST /api/promotions/vouchers/store-proposals
     */
    @Transactional
    public VoucherResponse createStoreProposal(VoucherRequest request, Long actorId, String actorRole, Long actorStoreId) {
        if (actorId == null || actorRole == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        boolean isManager = Role.STORE_MANAGER.name().equalsIgnoreCase(actorRole);
        boolean isAdmin = Role.ADMIN.name().equalsIgnoreCase(actorRole);
        if (!isManager && !isAdmin) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }

        Long targetStoreId;
        if (isManager) {
            if (actorStoreId == null) {
                throw new BadRequestException("Store Manager chưa được phân công chi nhánh");
            }
            // AC-02: Manager A sending storeId of B is forbidden
            if (request.getStoreId() != null && !request.getStoreId().equals(actorStoreId)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            targetStoreId = actorStoreId;
        } else {
            targetStoreId = request.getStoreId() != null ? request.getStoreId() : actorStoreId;
            if (targetStoreId == null) {
                throw new BadRequestException("Cần chỉ định storeId cho voucher chi nhánh");
            }
        }

        // Strict enforcement: store scope, target storeId, and pending approval status
        request.setScope(VoucherScope.STORE);
        request.setStoreId(targetStoreId);
        request.setApplicability(VoucherApplicability.SELECTED);
        request.setStoreIds(Set.of(targetStoreId));
        request.setStatus(VoucherStatus.PENDING_APPROVAL);
        request.setPrintable(false);
        request.setMaxPrintRatio(new BigDecimal("0.5000"));
        request.setMaxPrintPerStore(null);

        Instant now = Instant.now();
        if (request.getEndAt() != null && request.getEndAt().isBefore(now)) {
            throw new BadRequestException("Thời gian kết thúc voucher phải ở tương lai");
        }

        validateVoucherRequest(request, null);

        Voucher voucher = new Voucher();
        voucher.setCampaignKind(VoucherCampaignKind.PERSONAL);
        mapDtoToEntity(request, voucher);
        voucher.setScope(VoucherScope.STORE);
        voucher.setStoreId(targetStoreId);
        voucher.setStatus(VoucherStatus.PENDING_APPROVAL);
        voucher.setCreatedBy(actorId);
        voucher.setApprovedBy(null);
        voucher.setUsedCount(0);

        Voucher saved = voucherRepository.save(voucher);
        voucherRepository.flush();
        audit.record(actorId, actorRole, saved.getStoreId(), "VOUCHER_PROPOSED", "VOUCHER", saved.getId(), Map.of(), snapshot(saved));
        return VoucherResponse.fromEntity(saved);
    }

    /**
     * Admin approves a voucher proposal, transitioning it from PENDING_APPROVAL to ACTIVE.
     * Repeated calls on an already-approved voucher return the existing response idempotently.
     * PUT /api/promotions/vouchers/{id}/approve
     */
    @Transactional
    public VoucherResponse approveVoucher(Long id, Long actorId, String actorRole) {
        if (actorId == null || actorRole == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        // AC-04: Manager or any non-ADMIN calling approve is rejected with FORBIDDEN
        if (!Role.ADMIN.name().equalsIgnoreCase(actorRole)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }

        // AC-05: Pessimistic write lock ensures concurrent approve calls are serialized safely
        Voucher voucher = voucherRepository.findByIdWithLock(id)
            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đề xuất voucher với id: " + id));

        // Idempotency: repeated call on already active voucher returns current response
        if (voucher.getStatus() == VoucherStatus.ACTIVE) {
            return VoucherResponse.fromEntity(voucher);
        }

        // AC-06: Admin cannot approve an already expired proposal
        Instant now = Instant.now();
        if (voucher.getEndAt() != null && voucher.getEndAt().isBefore(now)) {
            throw new ApiException(ErrorCode.VOUCHER_EXPIRED);
        }

        if (voucher.getStatus() != VoucherStatus.PENDING_APPROVAL) {
            throw new BadRequestException("Chỉ có thể phê duyệt voucher đang ở trạng thái chờ duyệt (PENDING_APPROVAL). Trạng thái hiện tại: " + voucher.getStatus());
        }

        if (voucher.getScope() != VoucherScope.STORE) {
            throw new BadRequestException("Only store proposals can be approved");
        }
        stores.requireExists(voucher.getStoreId());
        var before = snapshot(voucher);
        voucher.setStatus(VoucherStatus.ACTIVE);
        voucher.setApprovedBy(actorId);
        voucher.setUpdatedAt(now);

        Voucher saved = voucherRepository.save(voucher);
        voucherRepository.flush();
        audit.record(actorId, actorRole, saved.getStoreId(), "VOUCHER_APPROVED", "VOUCHER", saved.getId(), before, snapshot(saved));
        return VoucherResponse.fromEntity(saved);
    }

    @Transactional
    public VoucherResponse rejectVoucher(Long id, RejectVoucherRequest request, Long actorId, String actorRole) {
        if (actorId == null || actorRole == null) throw new ApiException(ErrorCode.UNAUTHENTICATED);
        if (!Role.ADMIN.name().equalsIgnoreCase(actorRole)) throw new ApiException(ErrorCode.FORBIDDEN);
        if (request == null || request.reason() == null || request.reason().isBlank()
                || request.reason().length() > 500 || request.version() == null || request.version() < 0)
            throw new BadRequestException("A rejection reason and expected voucher version are required");
        String reason = request.reason().strip();
        Voucher voucher = voucherRepository.findByIdWithLock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Voucher proposal not found"));
        if (voucher.getStatus() == VoucherStatus.REJECTED && reason.equals(voucher.getRejectionReason()))
            return VoucherResponse.fromEntity(voucher);
        if (voucher.getStatus() != VoucherStatus.PENDING_APPROVAL || voucher.getScope() != VoucherScope.STORE)
            throw new ConflictException("Only pending store proposals can be rejected");
        if (request.version() != voucher.getVersion()) throw new ConflictException("Voucher changed; reload before reviewing");
        var before = snapshot(voucher);
        voucher.setStatus(VoucherStatus.REJECTED);
        voucher.setRejectionReason(reason);
        voucher.setRejectedBy(actorId);
        voucher.setRejectedAt(Instant.now());
        Voucher saved = voucherRepository.save(voucher);
        voucherRepository.flush();
        audit.record(actorId, actorRole, saved.getStoreId(), "VOUCHER_REJECTED", "VOUCHER", saved.getId(), before, snapshot(saved));
        return VoucherResponse.fromEntity(saved);
    }

    @Transactional
    public VoucherResponse updateVoucher(Long id, VoucherRequest request, Long actorId, String actorRole, Long actorStoreId) {
        requireEditor(actorId, actorRole);
        Voucher existing = voucherRepository.findByIdWithLock(id)
            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy voucher với id: " + id));
        if (request.getVersion() == null || request.getVersion() < 0) throw new BadRequestException("Expected voucher version is required");
        if (request.getVersion() != existing.getVersion())
            throw new ConflictException("Voucher changed; reload before saving");
        if (existing.getReservedCount() > 0 && (!Objects.equals(existing.getCode(), request.getCode())
                || existing.getType() != request.getType() || !sameAmount(existing.getValue(), request.getValue())
                || !sameAmount(existing.getMinOrderAmount(), request.getMinOrderAmount()) || !sameAmount(existing.getMaxDiscount(), request.getMaxDiscount())
                || existing.getScope() != request.getScope() || !Objects.equals(existing.getStoreId(), request.getStoreId())
                || existing.getApplicability() != request.getApplicability() || !existing.getStoreIds().equals(request.getStoreIds())))
            throw new ConflictException("Reserved voucher terms cannot change until orders settle");
        if (request.getTotalLimit() != null && request.getTotalLimit() > 0
                && request.getTotalLimit() < (long) existing.getUsedCount() + existing.getReservedCount() + existing.getPrintedUnredeemed())
            throw new ConflictException("Quota is below committed voucher usage");
        BigDecimal requestedRatio = request.getMaxPrintRatio() != null
                ? request.getMaxPrintRatio().setScale(4, RoundingMode.HALF_UP)
                : (existing.getMaxPrintRatio() != null ? existing.getMaxPrintRatio() : new BigDecimal("0.5000"));
        if (existing.getPrintedCount() > 0) {
            if (request.getTotalLimit() == null || request.getTotalLimit() <= 0) {
                throw new ConflictException("Cannot remove usage limit when vouchers have already been printed");
            }
            int maxPrintable = BigDecimal.valueOf(request.getTotalLimit())
                    .multiply(requestedRatio)
                    .setScale(0, RoundingMode.FLOOR)
                    .intValue();
            if (maxPrintable < existing.getPrintedCount()) {
                throw new ConflictException("New print limit is below already printed voucher count");
            }
        }
        if ("ADMIN".equalsIgnoreCase(actorRole)) {
            validateAdminStatus(request);
            if (existing.getStatus() == VoucherStatus.PENDING_APPROVAL || existing.getStatus() == VoucherStatus.REJECTED)
                throw new ConflictException("Proposals cannot bypass the approval workflow through voucher updates");
        }
        var before = snapshot(existing);

        // RBAC check for Store Manager
        if ("STORE_MANAGER".equalsIgnoreCase(actorRole)) {
            if (actorStoreId == null || existing.getStoreId() == null || !existing.getStoreId().equals(actorStoreId)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (request.getStoreId() != null && !request.getStoreId().equals(actorStoreId)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            request.setScope(VoucherScope.STORE);
            request.setStoreId(actorStoreId);
            request.setApplicability(VoucherApplicability.SELECTED);
            request.setStoreIds(Set.of(actorStoreId));
            request.setStatus(VoucherStatus.PENDING_APPROVAL);
            request.setPrintable(false);
            request.setMaxPrintRatio(existing.getMaxPrintRatio() != null ? existing.getMaxPrintRatio() : new BigDecimal("0.5000"));
            request.setMaxPrintPerStore(null);
            existing.setApprovedBy(null);
        }

        validateVoucherRequest(request, id);
        mapDtoToEntity(request, existing);

        Voucher saved = voucherRepository.save(existing);
        voucherRepository.flush();
        audit.record(actorId, actorRole, saved.getStoreId(), "VOUCHER_UPDATED", "VOUCHER", saved.getId(), before, snapshot(saved));
        return VoucherResponse.fromEntity(saved);
    }

    /**
     * Delete or disable a voucher.
     */
    @Transactional
    public void deleteVoucher(Long id, Long actorId, String actorRole, Long actorStoreId) {
        requireEditor(actorId, actorRole);
        Voucher existing = voucherRepository.findByIdWithLock(id)
            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy voucher với id: " + id));
        if (existing.getStatus() == VoucherStatus.PENDING_APPROVAL || existing.getStatus() == VoucherStatus.REJECTED)
            throw new ConflictException("Review proposals instead of deleting their history");

        if ("STORE_MANAGER".equalsIgnoreCase(actorRole)) {
            if (actorStoreId == null || existing.getStoreId() == null || !existing.getStoreId().equals(actorStoreId)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
        }

        // If voucher was already used in orders, soft delete by disabling
        var before = snapshot(existing);
        if (existing.getCampaignKind() == VoucherCampaignKind.PERSONAL
                || (existing.getUsedCount() != null && existing.getUsedCount() > 0)
                || existing.getPrintedCount() > 0
                || voucherRepository.hasReservations(id)) {
            existing.setStatus(VoucherStatus.DISABLED);
            voucherRepository.save(existing);
            voucherRepository.flush();
            audit.record(actorId, actorRole, existing.getStoreId(), "VOUCHER_DISABLED", "VOUCHER", id, before, snapshot(existing));
        } else {
            voucherRepository.delete(existing);
            audit.record(actorId, actorRole, existing.getStoreId(), "VOUCHER_DELETED", "VOUCHER", id, before, Map.of());
        }
    }

    private void requireEditor(Long actorId, String actorRole) {
        if (actorId == null || actorRole == null) throw new ApiException(ErrorCode.UNAUTHENTICATED);
        if (!"ADMIN".equalsIgnoreCase(actorRole) && !"STORE_MANAGER".equalsIgnoreCase(actorRole)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private void validateVoucherRequest(VoucherRequest request, Long existingId) {
        Voucher existing = existingId == null ? null : voucherRepository.findById(existingId).orElse(null);
        if (existing == null || existing.getCampaignKind() == VoucherCampaignKind.PERSONAL) {
            if (request.getCode() != null && !request.getCode().isBlank())
                throw new BadRequestException("Campaign cá nhân không nhận mã dùng chung");
            request.setCode(null);
            VoucherApplicability applicability = request.getApplicability() == null ? VoucherApplicability.ALL : request.getApplicability();
            Set<Long> selected = request.getStoreIds() == null ? Set.of() : request.getStoreIds();
            if (applicability == VoucherApplicability.SELECTED) {
                if (selected.isEmpty() || selected.size() > 100 || selected.stream().anyMatch(id -> id == null || id <= 0))
                    throw new ApiException(ErrorCode.VALIDATION_ERROR);
                selected.forEach(stores::requireExists);
            } else if (!selected.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        } else {
            String cleanCode = request.getCode() == null ? existing.getCode() : request.getCode().trim().toUpperCase(Locale.ROOT);
            request.setCode(cleanCode);
            if (voucherRepository.existsByCodeIgnoreCaseAndIdNot(cleanCode, existingId))
                throw new ConflictException("Mã voucher đã tồn tại trong hệ thống");
        }

        if (request.getEndAt() != null && request.getStartAt() != null) {
            if (!request.getEndAt().isAfter(request.getStartAt())) {
                throw new BadRequestException("Thời gian kết thúc (endAt) phải sau thời gian bắt đầu (startAt)");
            }
        }

        if (request.getType() == VoucherType.PERCENT) {
            if (request.getValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BadRequestException("Mức giảm theo phần trăm không được vượt quá 100%");
            }
        }

        if (existing != null && existing.getCampaignKind() == VoucherCampaignKind.LEGACY
                && request.getScope() == VoucherScope.STORE && request.getStoreId() == null) {
            throw new BadRequestException("Voucher phạm vi chi nhánh (STORE) bắt buộc phải có storeId");
        }
        if (existing != null && existing.getCampaignKind() == VoucherCampaignKind.LEGACY
                && request.getScope() == VoucherScope.STORE) stores.requireExists(request.getStoreId());

        if (Boolean.TRUE.equals(request.getPrintable())) {
            if (request.getTotalLimit() == null || request.getTotalLimit() <= 0) {
                throw new BadRequestException("Voucher cho phép in bắt buộc phải có tổng giới hạn lượt dùng (totalLimit > 0)");
            }
        }
        if (request.getMaxPrintRatio() != null) {
            if (request.getMaxPrintRatio().compareTo(BigDecimal.ZERO) <= 0
                    || request.getMaxPrintRatio().compareTo(BigDecimal.ONE) > 0) {
                throw new BadRequestException("Tỷ lệ in tối đa phải nằm trong khoảng (0, 1]");
            }
        }
        if (request.getMaxPrintPerStore() != null && request.getMaxPrintPerStore() <= 0) {
            throw new BadRequestException("Giới hạn in mỗi chi nhánh phải lớn hơn 0");
        }
    }

    private boolean sameAmount(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private void validateAdminStatus(VoucherRequest request) {
        if (request.getStatus() != null && request.getStatus() != VoucherStatus.ACTIVE && request.getStatus() != VoucherStatus.DISABLED)
            throw new BadRequestException("Voucher status must be ACTIVE or DISABLED");
    }

    private Map<String, Object> snapshot(Voucher voucher) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", voucher.getCode());
        values.put("campaignKind", voucher.getCampaignKind());
        values.put("applicability", voucher.getApplicability());
        values.put("storeIds", voucher.getStoreIds());
        values.put("title", voucher.getTitle());
        values.put("description", voucher.getDescription());
        values.put("type", voucher.getType());
        values.put("value", voucher.getValue());
        values.put("minOrderAmount", voucher.getMinOrderAmount());
        values.put("maxDiscount", voucher.getMaxDiscount());
        values.put("totalLimit", voucher.getTotalLimit());
        values.put("usedCount", voucher.getUsedCount());
        values.put("reservedCount", voucher.getReservedCount());
        values.put("printable", voucher.isPrintable());
        values.put("maxPrintRatio", voucher.getMaxPrintRatio());
        values.put("maxPrintPerStore", voucher.getMaxPrintPerStore());
        values.put("printedCount", voucher.getPrintedCount());
        values.put("printedUnredeemed", voucher.getPrintedUnredeemed());
        values.put("startAt", voucher.getStartAt());
        values.put("endAt", voucher.getEndAt());
        values.put("scope", voucher.getScope());
        values.put("storeId", voucher.getStoreId());
        values.put("status", voucher.getStatus());
        values.put("reason", voucher.getRejectionReason());
        values.put("version", voucher.getVersion());
        return values;
    }

    private void mapDtoToEntity(VoucherRequest dto, Voucher entity) {
        entity.setCode(dto.getCode());
        if (entity.getCampaignKind() == VoucherCampaignKind.PERSONAL) {
            VoucherApplicability applicability = dto.getApplicability() == null ? VoucherApplicability.ALL : dto.getApplicability();
            // Store Manager proposals are permanently scoped to the manager's
            // verified branch, including after Admin approval/update/toggle.
            // Never widen a proposal into an ALL-stores campaign by rewriting
            // its legacy scope/storeId metadata during an Admin save.
            if (entity.getScope() == VoucherScope.STORE && entity.getStoreId() != null) {
                if (applicability != VoucherApplicability.SELECTED
                        || !Set.of(entity.getStoreId()).equals(dto.getStoreIds())) {
                    throw new ConflictException("Store proposal must remain restricted to its original branch");
                }
                entity.setApplicability(VoucherApplicability.SELECTED);
                entity.setStoreIds(Set.of(entity.getStoreId()));
            } else {
                entity.setApplicability(applicability);
                entity.setStoreIds(applicability == VoucherApplicability.ALL ? Set.of() : dto.getStoreIds());
            }
        }
        entity.setTitle(dto.getTitle());
        entity.setDescription(dto.getDescription());
        entity.setType(dto.getType());
        entity.setValue(dto.getValue());
        entity.setMinOrderAmount(dto.getMinOrderAmount() != null ? dto.getMinOrderAmount() : BigDecimal.ZERO);
        entity.setMaxDiscount(dto.getMaxDiscount());
        entity.setTotalLimit(dto.getTotalLimit());
        boolean printable = Boolean.TRUE.equals(dto.getPrintable());
        entity.setPrintable(printable);
        BigDecimal ratio = dto.getMaxPrintRatio() != null
                ? dto.getMaxPrintRatio().setScale(4, RoundingMode.HALF_UP)
                : (entity.getMaxPrintRatio() != null ? entity.getMaxPrintRatio() : new BigDecimal("0.5000"));
        entity.setMaxPrintRatio(ratio);
        entity.setMaxPrintPerStore(printable ? dto.getMaxPrintPerStore() : null);
        entity.setStartAt(dto.getStartAt());
        entity.setEndAt(dto.getEndAt());
        if (entity.getCampaignKind() == VoucherCampaignKind.LEGACY) {
            entity.setScope(dto.getScope() != null ? dto.getScope() : VoucherScope.ALL);
            entity.setStoreId(entity.getScope() == VoucherScope.STORE ? dto.getStoreId() : null);
        } else if (entity.getScope() != VoucherScope.STORE || entity.getStoreId() == null) {
            // Admin-created PERSONAL campaigns use applicability + storeIds;
            // retain this canonical shape (even when SELECTED is one store).
            entity.setScope(VoucherScope.ALL);
            entity.setStoreId(null);
        }
        if (dto.getStatus() != null) {
            entity.setStatus(dto.getStatus());
        }
    }
}

