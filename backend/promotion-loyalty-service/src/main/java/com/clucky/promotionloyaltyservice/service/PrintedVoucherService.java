package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.CreatePrintBatchRequest;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.PrintBatchResponse;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.PrintQuotaSummaryResponse;
import com.clucky.promotionloyaltyservice.dto.PrintBatchDtos.RevokePrintBatchRequest;
import com.clucky.promotionloyaltyservice.dto.Role;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherRequest;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherResponse;
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
import com.clucky.promotionloyaltyservice.service.VoucherPdfExportService.BatchRenderModel;
import com.clucky.promotionloyaltyservice.service.VoucherPdfExportService.TicketRenderItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages printed paper voucher batches (CKP1-...), quota locking without
 * double-counting, counter-only validation/reservation/settlement, and PDF export.
 */
@Service
public class PrintedVoucherService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final Pattern CODE = Pattern.compile("CKP1([A-Z2-7]{12})([A-Z2-7]{32})");
    private static final DateTimeFormatter BATCH_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final VoucherRepository campaigns;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final VoucherAuditOutbox audit;
    private final VoucherStoreVerifier stores;
    private final VoucherPdfExportService pdfExport;
    private final String hmacKey;
    private final String encryptionKey;

    public PrintedVoucherService(
            VoucherRepository campaigns,
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            VoucherAuditOutbox audit,
            VoucherStoreVerifier stores,
            VoucherPdfExportService pdfExport,
            @Value("${CLUCKY_VOUCHER_HMAC_KEY:}") String hmacKey,
            @Value("${CLUCKY_VOUCHER_ENC_KEY:}") String encryptionKey,
            @Value("${JWT_SECRET:}") String jwtSecret
    ) {
        this.campaigns = campaigns;
        this.jdbc = jdbc;
        this.redis = redis;
        this.audit = audit;
        this.stores = stores;
        this.pdfExport = pdfExport;
        this.hmacKey = hmacKey == null || hmacKey.isBlank()
                ? derivedKey(jwtSecret, "clucky-printed-voucher-hmac-v1") : hmacKey;
        this.encryptionKey = encryptionKey == null || encryptionKey.isBlank()
                ? derivedKey(jwtSecret, "clucky-printed-voucher-aes-v1") : encryptionKey;
    }

    public static boolean isPrintedCode(String rawCode) {
        if (rawCode == null) {
            return false;
        }
        String normalized = rawCode.strip().toUpperCase(Locale.ROOT);
        return normalized.startsWith("CKP1") || normalized.startsWith("CLUCKY:P1:");
    }

    @Transactional(readOnly = true)
    public PrintQuotaSummaryResponse getPrintQuota(Long campaignId, Long requestedStoreId) {
        if (campaignId == null || campaignId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        CurrentActor actor = requirePrintOperator();
        Voucher campaign = campaigns.findById(campaignId)
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));

        Long effectiveStoreId = resolveEffectiveStoreForRead(actor, campaign, requestedStoreId);
        return buildQuotaSummary(campaign, effectiveStoreId, actor.role());
    }

    @Transactional
    public PrintBatchResponse createBatch(Long campaignId, CreatePrintBatchRequest request) {
        if (campaignId == null || campaignId <= 0 || request == null
                || request.quantity() == null || request.quantity() < 1
                || request.quantity() > VoucherPdfExportService.MAX_BATCH_TICKETS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        CurrentActor actor = requirePrintOperator();
        int quantity = request.quantity();

        Voucher campaign = campaigns.findByIdWithLock(campaignId)
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));

        if (!campaign.isPrintable() || campaign.getTotalLimit() == null || campaign.getTotalLimit() <= 0) {
            throw new ConflictException("Voucher này chưa được cấu hình cho phép in hoặc không có giới hạn tổng hợp lệ");
        }
        Instant now = Instant.now();
        VoucherStatus effectiveStatus = campaign.computeEffectiveStatus(now);
        if (effectiveStatus != VoucherStatus.ACTIVE) {
            throw switch (effectiveStatus) {
                case UPCOMING -> new ApiException(ErrorCode.VOUCHER_NOT_STARTED);
                case EXPIRED -> new ApiException(ErrorCode.VOUCHER_EXPIRED);
                case EXHAUSTED -> new ApiException(ErrorCode.VOUCHER_EXHAUSTED);
                default -> new ApiException(ErrorCode.VOUCHER_INACTIVE);
            };
        }

        StoreTarget storeTarget = resolveStoreTargetForCreate(actor, campaign, request.storeId());
        PrintQuotaSummaryResponse quota = buildQuotaSummary(campaign, storeTarget.storeId(), actor.role());
        if (quantity > quota.globalPrintableRemaining()) {
            throw new ConflictException("Vượt hạn mức in tối đa của chương trình (còn "
                    + quota.globalPrintableRemaining() + " phiếu)");
        }
        if (quantity > quota.poolRemaining()) {
            throw new ApiException(ErrorCode.VOUCHER_EXHAUSTED);
        }
        if (quota.storePrintLimit() != null && quota.storePrintedCount() + quantity > quota.storePrintLimit()) {
            int remaining = Math.max(0, quota.storePrintLimit() - quota.storePrintedCount());
            throw new ConflictException("Chi nhánh vượt hạn mức in cho phép (còn " + remaining + " phiếu)");
        }

        Long batchId = jdbc.queryForObject("SELECT nextval('promotion_service.voucher_print_batches_id_seq')", Long.class);
        if (batchId == null) {
            throw new IllegalStateException("Cannot allocate print batch sequence");
        }
        String batchCode = String.format("PB-%s-%05d", BATCH_DATE_FMT.format(now), batchId);
        String cleanNote = request.note() == null || request.note().isBlank() ? null : request.note().strip();

        jdbc.update("""
                INSERT INTO promotion_service.voucher_print_batches
                  (id, batch_code, campaign_id, store_id, store_name, quantity, allowed_channels,
                   status, note, created_by, created_by_role)
                VALUES (?, ?, ?, ?, ?, ?, 'COUNTER', 'ACTIVE', ?, ?, ?)
                """, batchId, batchCode, campaign.getId(), storeTarget.storeId(), storeTarget.storeName(),
                quantity, cleanNote, actor.userId(), actor.role().name());

        Timestamp expiresAt = Timestamp.from(campaign.getEndAt());
        for (int seq = 1; seq <= quantity; seq++) {
            String selector = randomChars(12);
            String code = "CKP1-" + selector + "-" + randomChars(32);
            byte[] salt = randomBytes(24);
            byte[] verifier = verifier(salt, normalize(code));
            String ciphertext = encrypt(code);
            UUID instanceId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO promotion_service.printed_voucher_instances
                      (id, batch_id, campaign_id, store_id, sequence_no, state,
                       selector, code_salt, code_verifier, code_ciphertext, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'ISSUED', ?, ?, ?, ?, ?)
                    """, instanceId, batchId, campaign.getId(), storeTarget.storeId(), seq,
                    selector, salt, verifier, ciphertext, expiresAt);
        }

        campaign.setPrintedCount(Math.addExact(campaign.getPrintedCount(), quantity));
        campaign.setPrintedUnredeemed(Math.addExact(campaign.getPrintedUnredeemed(), quantity));
        campaigns.saveAndFlush(campaign);

        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("batchId", batchId);
        auditAfter.put("batchCode", batchCode);
        auditAfter.put("campaignId", campaign.getId());
        auditAfter.put("storeId", storeTarget.storeId());
        auditAfter.put("quantity", quantity);
        auditAfter.put("printedCount", campaign.getPrintedCount());
        auditAfter.put("printedUnredeemed", campaign.getPrintedUnredeemed());
        audit.record(actor.userId(), actor.role().name(), storeTarget.storeId(),
                "VOUCHER_PRINT_BATCH_CREATED", "VOUCHER_PRINT_BATCH", batchId,
                Map.of(), auditAfter);

        return getBatchByIdInternal(batchId);
    }

    @Transactional(readOnly = true)
    public List<PrintBatchResponse> listBatches(Long campaignId, Long storeId, String status) {
        return listBatches(campaignId, storeId, status, "vi");
    }

    @Transactional(readOnly = true)
    public List<PrintBatchResponse> listBatches(Long campaignId, Long storeId, String status, String lang) {
        CurrentActor actor = requirePrintOperator();
        String resolvedLang = "en".equalsIgnoreCase(lang) ? "en" : "vi";
        Long effectiveStoreId = storeId;
        if (actor.role() == Role.STORE_MANAGER) {
            if (actor.storeId() == null || actor.storeId() <= 0) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (storeId != null && !storeId.equals(actor.storeId())) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            effectiveStoreId = actor.storeId();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.batch_code, b.campaign_id,
                       COALESCE(v.title ->> ?, v.title ->> 'vi', v.title ->> 'en', '') AS campaign_title,
                       v.type AS campaign_type, v.value AS discount_value,
                       v.min_order_amount, v.max_discount, v.start_at, v.end_at,
                       b.store_id, b.store_name, b.quantity, b.allowed_channels, b.status,
                       b.note, b.created_by, b.created_by_role, b.created_at,
                       b.revoked_by, b.revoked_at, b.revoke_reason,
                       COALESCE(SUM(CASE WHEN p.state = 'ISSUED' AND p.expires_at >= now() THEN 1 ELSE 0 END), 0) AS issued_count,
                       COALESCE(SUM(CASE WHEN p.state = 'RESERVED' THEN 1 ELSE 0 END), 0) AS reserved_count,
                       COALESCE(SUM(CASE WHEN p.state = 'REDEEMED' THEN 1 ELSE 0 END), 0) AS redeemed_count,
                       COALESCE(SUM(CASE WHEN p.state = 'EXPIRED' OR (p.state = 'ISSUED' AND p.expires_at < now()) THEN 1 ELSE 0 END), 0) AS expired_count,
                       COALESCE(SUM(CASE WHEN p.state = 'REVOKED' THEN 1 ELSE 0 END), 0) AS revoked_count
                FROM promotion_service.voucher_print_batches b
                JOIN promotion_service.vouchers v ON v.id = b.campaign_id
                LEFT JOIN promotion_service.printed_voucher_instances p ON p.batch_id = b.id
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        params.add(resolvedLang);
        if (campaignId != null) {
            sql.append(" AND b.campaign_id = ?");
            params.add(campaignId);
        }
        if (effectiveStoreId != null) {
            sql.append(" AND b.store_id = ?");
            params.add(effectiveStoreId);
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append(" AND b.status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        sql.append("""
                 GROUP BY b.id, v.title, v.type, v.value, v.min_order_amount, v.max_discount, v.start_at, v.end_at
                 ORDER BY b.created_at DESC, b.id DESC
                """);

        return jdbc.queryForList(sql.toString(), params.toArray()).stream()
                .map(this::toBatchResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BatchPdfResult exportBatchPdf(Long batchId) {
        return exportBatchPdf(batchId, "vi");
    }

    @Transactional(readOnly = true)
    public BatchPdfResult exportBatchPdf(Long batchId, String lang) {
        if (batchId == null || batchId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String resolvedLang = "en".equalsIgnoreCase(lang) ? "en" : "vi";
        CurrentActor actor = requirePrintOperator();
        var batchRows = jdbc.queryForList("""
                SELECT b.*,
                       COALESCE(v.title ->> ?, v.title ->> 'vi', v.title ->> 'en', '') AS campaign_title,
                       v.type AS campaign_type, v.value AS discount_value,
                       v.min_order_amount, v.max_discount, v.end_at
                FROM promotion_service.voucher_print_batches b
                JOIN promotion_service.vouchers v ON v.id = b.campaign_id
                WHERE b.id = ?
                """, resolvedLang, batchId);
        if (batchRows.isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy lô in #" + batchId);
        }
        var batch = batchRows.getFirst();
        enforceBatchAccess(actor, batch);

        if ("REVOKED".equals(batch.get("status"))) {
            throw new ConflictException("Lô phiếu đã bị thu hồi, không thể xuất PDF");
        }

        var tickets = jdbc.queryForList("""
                SELECT sequence_no, code_ciphertext
                FROM promotion_service.printed_voucher_instances
                WHERE batch_id = ? AND state = 'ISSUED'
                ORDER BY sequence_no ASC
                """, batchId);
        if (tickets.isEmpty()) {
            throw new ConflictException("Lô phiếu không còn tờ nào ở trạng thái khả dụng (ISSUED) để in");
        }

        List<TicketRenderItem> renderItems = new ArrayList<>(tickets.size());
        for (var ticket : tickets) {
            String rawCode = decrypt((String) ticket.get("code_ciphertext"));
            renderItems.add(new TicketRenderItem(
                    ((Number) ticket.get("sequence_no")).intValue(),
                    rawCode,
                    "CLUCKY:P1:" + rawCode
            ));
        }

        Long storeId = batch.get("store_id") == null ? null : ((Number) batch.get("store_id")).longValue();
        String storeName = (String) batch.get("store_name");
        if (storeId != null && "en".equalsIgnoreCase(resolvedLang)) {
            try {
                var store = stores.getStore(storeId, resolvedLang);
                if (store.name() != null && !store.name().isBlank()) {
                    storeName = store.name();
                }
            } catch (Exception ignored) {
                // Keep snapshot store_name if store lookup is temporarily unavailable during PDF export.
            }
        }
        BatchRenderModel model = new BatchRenderModel(
                (String) batch.get("batch_code"),
                (String) batch.get("campaign_title"),
                (String) batch.get("campaign_type"),
                (BigDecimal) batch.get("discount_value"),
                (BigDecimal) batch.get("min_order_amount"),
                (BigDecimal) batch.get("max_discount"),
                instant(batch, "end_at"),
                storeId,
                storeName,
                instant(batch, "created_at"),
                renderItems,
                resolvedLang
        );
        byte[] pdfBytes = pdfExport.renderBatchPdf(model);
        return new BatchPdfResult((String) batch.get("batch_code"), pdfBytes);
    }

    public record BatchPdfResult(String batchCode, byte[] pdfBytes) {}

    @Transactional
    public PrintBatchResponse revokeBatch(Long batchId, RevokePrintBatchRequest request) {
        if (batchId == null || batchId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        CurrentActor actor = requirePrintOperator();
        var initialRows = jdbc.queryForList(
                "SELECT id, campaign_id, store_id, status FROM promotion_service.voucher_print_batches WHERE id = ?",
                batchId);
        if (initialRows.isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy lô in #" + batchId);
        }
        var initial = initialRows.getFirst();
        enforceBatchAccess(actor, initial);

        long campaignId = number(initial, "campaign_id");
        Voucher campaign = campaigns.findByIdWithLock(campaignId)
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        var batch = jdbc.queryForMap(
                "SELECT * FROM promotion_service.voucher_print_batches WHERE id = ? FOR UPDATE", batchId);
        if ("REVOKED".equals(batch.get("status"))) {
            return getBatchByIdInternal(batchId);
        }

        // Lock all instances of the batch in deterministic sequence order
        jdbc.queryForList("""
                SELECT id FROM promotion_service.printed_voucher_instances
                WHERE batch_id = ? ORDER BY sequence_no ASC FOR UPDATE
                """, batchId);

        int revokedNow = jdbc.update("""
                UPDATE promotion_service.printed_voucher_instances
                SET state = 'REVOKED', revoked_at = now(), version = version + 1
                WHERE batch_id = ? AND state = 'ISSUED'
                """, batchId);

        int remainingNonRevoked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM promotion_service.printed_voucher_instances
                WHERE batch_id = ? AND state IN ('RESERVED', 'REDEEMED', 'EXPIRED')
                """, Integer.class, batchId);

        String nextBatchStatus = remainingNonRevoked == 0 ? "REVOKED" : "PARTIALLY_REVOKED";
        if ("PARTIALLY_REVOKED".equals(batch.get("status")) && revokedNow == 0) {
            return getBatchByIdInternal(batchId);
        }

        String reason = request != null && request.reason() != null && !request.reason().isBlank()
                ? request.reason().strip() : "Thu hồi lô phiếu chưa sử dụng";

        jdbc.update("""
                UPDATE promotion_service.voucher_print_batches
                SET status = ?, revoked_by = ?, revoked_at = now(), revoke_reason = ?
                WHERE id = ?
                """, nextBatchStatus, actor.userId(), reason, batchId);

        if (revokedNow > 0) {
            campaign.setPrintedUnredeemed(Math.max(0, campaign.getPrintedUnredeemed() - revokedNow));
            campaign.setPrintedCount(Math.max(0, campaign.getPrintedCount() - revokedNow));
            campaigns.saveAndFlush(campaign);
        }

        Long batchStoreId = batch.get("store_id") == null ? null : number(batch, "store_id");
        audit.record(actor.userId(), actor.role().name(), batchStoreId,
                "VOUCHER_PRINT_BATCH_REVOKED", "VOUCHER_PRINT_BATCH", batchId,
                Map.of("status", batch.get("status")),
                Map.of("status", nextBatchStatus, "revokedCount", revokedNow, "reason", reason));

        return getBatchByIdInternal(batchId);
    }

    @Transactional(readOnly = true)
    public ValidateVoucherResponse validate(ValidateVoucherRequest request) {
        return validate(request, "vi");
    }

    @Transactional(readOnly = true)
    public ValidateVoucherResponse validate(ValidateVoucherRequest request, String lang) {
        if (request == null || request.getOrderAmount() == null || request.getOrderAmount().signum() < 0
                || request.getOrderAmount().stripTrailingZeros().scale() > 0
                || request.getOrderAmount().precision() > 12
                || request.getStoreId() == null || request.getStoreId() <= 0
                || request.getVoucherInstanceId() != null
                || request.getCode() == null || request.getCode().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (request.getChannel() != null && !request.getChannel().isBlank()
                && !"COUNTER".equalsIgnoreCase(request.getChannel().strip())) {
            throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
        }
        CurrentActor actor = CurrentActor.require();
        requireStaff(actor, request.getStoreId());
        limitAttempts(actor.userId());

        Map<String, Object> row = resolveCode(request.getCode());
        Map<String, Object> batch = jdbc.queryForMap(
                "SELECT id, store_id, allowed_channels, status FROM promotion_service.voucher_print_batches WHERE id = ?",
                number(row, "batch_id"));

        if (!"COUNTER".equalsIgnoreCase(String.valueOf(batch.get("allowed_channels")))) {
            throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
        }
        if ("REVOKED".equals(batch.get("status")) || "REVOKED".equals(row.get("state"))) {
            throw new ApiException(ErrorCode.VOUCHER_INACTIVE);
        }
        if (batch.get("store_id") != null && number(batch, "store_id") != request.getStoreId()) {
            throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
        }

        Voucher campaign = campaigns.findById(number(row, "campaign_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        checkInstance(row);
        checkCampaign(campaign, Instant.now());
        checkStore(campaign, request.getStoreId());

        BigDecimal discount = discount(campaign, request.getOrderAmount());
        ValidateVoucherResponse response = ValidateVoucherResponse.valid(
                discount, VoucherResponse.fromPublicEntity(campaign, Instant.now(), lang));
        response.setCode(null);
        response.setVoucherInstanceId(null);
        return response;
    }

    /** Internal Order service only. Lock order: Campaign -> printed instance -> reservation. */
    @Transactional
    public void reserve(long orderId, Long orderCustomerId, long storeId, String rawCode,
            BigDecimal amount, BigDecimal expectedDiscount) {
        if (orderId <= 0 || storeId <= 0 || amount == null || amount.signum() < 0
                || expectedDiscount == null || expectedDiscount.signum() < 0
                || amount.stripTrailingZeros().scale() > 0 || expectedDiscount.stripTrailingZeros().scale() > 0
                || expectedDiscount.compareTo(amount) > 0 || rawCode == null || rawCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (orderCustomerId != null && orderCustomerId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> initial = resolveCode(rawCode);
        Voucher campaign = campaigns.findByIdWithLock(number(initial, "campaign_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        UUID id = (UUID) initial.get("id");
        var row = jdbc.queryForMap(
                "SELECT * FROM promotion_service.printed_voucher_instances WHERE id = ? FOR UPDATE", id);
        if (!verifyCode(rawCode, row)) {
            throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
        }
        var batch = jdbc.queryForMap(
                "SELECT id, store_id, allowed_channels, status FROM promotion_service.voucher_print_batches WHERE id = ?",
                number(row, "batch_id"));

        var existing = jdbc.queryForList(
                "SELECT * FROM promotion_service.voucher_reservations WHERE order_id = ? FOR UPDATE", orderId);
        if (!existing.isEmpty()) {
            var reservation = existing.getFirst();
            if (!"RESERVED".equals(reservation.get("state")) && !"COMPLETED".equals(reservation.get("state"))) {
                throw new ConflictException("Voucher order reservation is closed");
            }
            if (!id.equals(reservation.get("printed_voucher_id"))
                    || number(reservation, "store_id") != storeId
                    || !Objects.equals(reservation.get("customer_id"), orderCustomerId)
                    || amount.compareTo((BigDecimal) reservation.get("order_amount")) != 0
                    || expectedDiscount.compareTo((BigDecimal) reservation.get("discount_amount")) != 0) {
                throw new ConflictException("Voucher reservation mismatch");
            }
            return;
        }

        if (!"COUNTER".equalsIgnoreCase(String.valueOf(batch.get("allowed_channels")))) {
            throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
        }
        if ("REVOKED".equals(batch.get("status"))) {
            throw new ApiException(ErrorCode.VOUCHER_INACTIVE);
        }
        if (batch.get("store_id") != null && number(batch, "store_id") != storeId) {
            throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
        }
        checkInstance(row);
        checkCampaign(campaign, Instant.now());
        checkStore(campaign, storeId);
        if (discount(campaign, amount).compareTo(expectedDiscount) != 0) {
            throw new ConflictException("Voucher discount has changed");
        }
        if (campaign.getPrintedUnredeemed() <= 0) {
            throw new ConflictException("Printed voucher quota inconsistency");
        }

        jdbc.update("""
                INSERT INTO promotion_service.voucher_reservations
                  (order_id, voucher_id, customer_id, store_id, code, order_amount, discount_amount, state, printed_voucher_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?)
                """, orderId, campaign.getId(), orderCustomerId, storeId,
                "CKP1-" + row.get("selector"), amount, expectedDiscount, id);
        jdbc.update("""
                UPDATE promotion_service.printed_voucher_instances
                SET state = 'RESERVED', reserved_order_id = ?, version = version + 1
                WHERE id = ?
                """, orderId, id);

        campaign.setPrintedUnredeemed(Math.subtractExact(campaign.getPrintedUnredeemed(), 1));
        campaign.setReservedCount(Math.addExact(campaign.getReservedCount(), 1));
        campaigns.saveAndFlush(campaign);
    }

    /** Called by VoucherReservationService settlement in the same transaction. */
    @Transactional
    public void finish(Map<String, Object> reservation, String target, String eventId) {
        UUID id = (UUID) reservation.get("printed_voucher_id");
        Voucher campaign = campaigns.findByIdWithLock(number(reservation, "voucher_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        var instance = jdbc.queryForMap(
                "SELECT * FROM promotion_service.printed_voucher_instances WHERE id = ? FOR UPDATE", id);
        String current = (String) reservation.get("state");
        long orderId = number(reservation, "order_id");

        if ("RESERVED".equals(current)) {
            if (!"RESERVED".equals(instance.get("state")) || number(instance, "reserved_order_id") != orderId) {
                throw new ConflictException("Printed voucher instance/reservation mismatch");
            }
            campaign.setReservedCount(Math.subtractExact(campaign.getReservedCount(), 1));
            boolean success = "COMPLETED".equals(target);
            if (success) {
                campaign.setUsedCount(Math.addExact(campaign.getUsedCount(), 1));
                jdbc.update("""
                        INSERT INTO promotion_service.voucher_usages
                          (voucher_id, customer_id, order_id, printed_voucher_id)
                        VALUES (?, ?, ?, ?)
                        """, campaign.getId(), reservation.get("customer_id"), orderId, id);
                jdbc.update("""
                        UPDATE promotion_service.printed_voucher_instances
                        SET state = 'REDEEMED', reserved_order_id = NULL, redeemed_order_id = ?, version = version + 1
                        WHERE id = ?
                        """, orderId, id);
            } else {
                boolean expired = Instant.now().isAfter(instant(instance, "expires_at"));
                if (!expired) {
                    campaign.setPrintedUnredeemed(Math.addExact(campaign.getPrintedUnredeemed(), 1));
                }
                String next = expired ? "EXPIRED" : "ISSUED";
                jdbc.update("""
                        UPDATE promotion_service.printed_voucher_instances
                        SET state = ?, reserved_order_id = NULL, redeemed_order_id = NULL, version = version + 1
                        WHERE id = ?
                        """, next, id);
                auditCancellation(orderId, id, "RELEASE", eventId);
            }
            jdbc.update("""
                    UPDATE promotion_service.voucher_reservations
                    SET state = ?, event_id = ?, updated_at = now()
                    WHERE order_id = ?
                    """, target, eventId, orderId);
            campaigns.saveAndFlush(campaign);
        } else if ("COMPLETED".equals(current) && "RELEASED".equals(target)) {
            if (!"REDEEMED".equals(instance.get("state")) || number(instance, "redeemed_order_id") != orderId) {
                throw new ConflictException("Printed voucher reversal state mismatch");
            }
            String reversalId = eventId == null ? "cancel-" + orderId : eventId;
            int count = jdbc.update("""
                    UPDATE promotion_service.voucher_usages
                    SET reversed_at = now(), reversal_id = ?
                    WHERE order_id = ? AND printed_voucher_id = ? AND reversed_at IS NULL
                    """, reversalId, orderId, id);
            if (count != 1) {
                throw new ConflictException("Printed voucher reversal usage mismatch");
            }
            campaign.setUsedCount(Math.subtractExact(campaign.getUsedCount(), 1));
            boolean expired = Instant.now().isAfter(instant(instance, "expires_at"));
            if (!expired) {
                campaign.setPrintedUnredeemed(Math.addExact(campaign.getPrintedUnredeemed(), 1));
            }
            String next = expired ? "EXPIRED" : "ISSUED";
            jdbc.update("""
                    UPDATE promotion_service.printed_voucher_instances
                    SET state = ?, redeemed_order_id = NULL, version = version + 1
                    WHERE id = ?
                    """, next, id);
            jdbc.update("""
                    UPDATE promotion_service.voucher_reservations
                    SET state = 'RELEASED', event_id = ?, updated_at = now()
                    WHERE order_id = ?
                    """, eventId, orderId);
            auditCancellation(orderId, id, "REVERSE", reversalId);
            campaigns.saveAndFlush(campaign);
        }
    }

    @Transactional
    public void recordRefund(long orderId, String refundId) {
        if (orderId <= 0 || refundId == null || !refundId.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        var rows = jdbc.queryForList(
                "SELECT * FROM promotion_service.voucher_reservations WHERE order_id = ? FOR UPDATE", orderId);
        if (rows.isEmpty() || rows.getFirst().get("printed_voucher_id") == null) {
            return;
        }
        if (!"COMPLETED".equals(rows.getFirst().get("state"))) {
            return;
        }
        auditCancellation(orderId, (UUID) rows.getFirst().get("printed_voucher_id"),
                "REFUND_NO_RESTORE", "refund-" + refundId);
    }

    private void auditCancellation(long orderId, UUID printedVoucherId, String action, String eventId) {
        jdbc.update("""
                INSERT INTO promotion_service.voucher_cancellation_audit(order_id, printed_voucher_id, action, event_id)
                VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
                """, orderId, printedVoucherId, action,
                eventId == null ? action.toLowerCase(Locale.ROOT) + "-" + orderId : eventId);
    }

    private PrintBatchResponse getBatchByIdInternal(Long batchId) {
        var rows = jdbc.queryForList("""
                SELECT b.id, b.batch_code, b.campaign_id,
                       COALESCE(v.title ->> 'vi', v.title ->> 'en', '') AS campaign_title,
                       v.type AS campaign_type, v.value AS discount_value,
                       v.min_order_amount, v.max_discount, v.start_at, v.end_at,
                       b.store_id, b.store_name, b.quantity, b.allowed_channels, b.status,
                       b.note, b.created_by, b.created_by_role, b.created_at,
                       b.revoked_by, b.revoked_at, b.revoke_reason,
                       COALESCE(SUM(CASE WHEN p.state = 'ISSUED' AND p.expires_at >= now() THEN 1 ELSE 0 END), 0) AS issued_count,
                       COALESCE(SUM(CASE WHEN p.state = 'RESERVED' THEN 1 ELSE 0 END), 0) AS reserved_count,
                       COALESCE(SUM(CASE WHEN p.state = 'REDEEMED' THEN 1 ELSE 0 END), 0) AS redeemed_count,
                       COALESCE(SUM(CASE WHEN p.state = 'EXPIRED' OR (p.state = 'ISSUED' AND p.expires_at < now()) THEN 1 ELSE 0 END), 0) AS expired_count,
                       COALESCE(SUM(CASE WHEN p.state = 'REVOKED' THEN 1 ELSE 0 END), 0) AS revoked_count
                FROM promotion_service.voucher_print_batches b
                JOIN promotion_service.vouchers v ON v.id = b.campaign_id
                LEFT JOIN promotion_service.printed_voucher_instances p ON p.batch_id = b.id
                WHERE b.id = ?
                GROUP BY b.id, v.title, v.type, v.value, v.min_order_amount, v.max_discount, v.start_at, v.end_at
                """, batchId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy lô in #" + batchId);
        }
        return toBatchResponse(rows.getFirst());
    }

    private PrintBatchResponse toBatchResponse(Map<String, Object> row) {
        return new PrintBatchResponse(
                number(row, "id"),
                (String) row.get("batch_code"),
                number(row, "campaign_id"),
                (String) row.get("campaign_title"),
                row.get("campaign_type") != null ? row.get("campaign_type").toString() : null,
                (BigDecimal) row.get("discount_value"),
                (BigDecimal) row.get("min_order_amount"),
                (BigDecimal) row.get("max_discount"),
                instant(row, "start_at"),
                instant(row, "end_at"),
                row.get("store_id") == null ? null : number(row, "store_id"),
                (String) row.get("store_name"),
                ((Number) row.get("quantity")).intValue(),
                (String) row.get("allowed_channels"),
                (String) row.get("status"),
                (String) row.get("note"),
                ((Number) row.get("issued_count")).intValue(),
                ((Number) row.get("reserved_count")).intValue(),
                ((Number) row.get("redeemed_count")).intValue(),
                ((Number) row.get("expired_count")).intValue(),
                ((Number) row.get("revoked_count")).intValue(),
                number(row, "created_by"),
                (String) row.get("created_by_role"),
                instant(row, "created_at"),
                row.get("revoked_by") == null ? null : number(row, "revoked_by"),
                row.get("revoked_at") == null ? null : instant(row, "revoked_at"),
                (String) row.get("revoke_reason")
        );
    }

    private record StoreTarget(Long storeId, String storeName) {}

    private Long resolveEffectiveStoreForRead(CurrentActor actor, Voucher campaign, Long requestedStoreId) {
        if (actor.role() == Role.STORE_MANAGER) {
            if (actor.storeId() == null || actor.storeId() <= 0) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (requestedStoreId != null && !requestedStoreId.equals(actor.storeId())) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (!isStoreEligible(campaign, actor.storeId())) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return actor.storeId();
        }
        if (requestedStoreId != null) {
            if (!isStoreEligible(campaign, requestedStoreId)) {
                throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
            }
            return requestedStoreId;
        }
        return singleStoreIdOf(campaign);
    }

    private StoreTarget resolveStoreTargetForCreate(CurrentActor actor, Voucher campaign, Long requestedStoreId) {
        if (actor.role() == Role.STORE_MANAGER) {
            if (actor.storeId() == null || actor.storeId() <= 0) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (requestedStoreId != null && !requestedStoreId.equals(actor.storeId())) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (!isStoreEligible(campaign, actor.storeId())) {
                throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
            }
            var store = stores.getStore(actor.storeId());
            return new StoreTarget(store.id(), store.name());
        }
        Long targetId = requestedStoreId != null ? requestedStoreId : singleStoreIdOf(campaign);
        if (targetId != null) {
            if (!isStoreEligible(campaign, targetId)) {
                throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
            }
            var store = stores.getStore(targetId);
            return new StoreTarget(store.id(), store.name());
        }
        return new StoreTarget(null, "Toàn hệ thống (HQ)");
    }

    private Long singleStoreIdOf(Voucher campaign) {
        if (campaign.getCampaignKind() == VoucherCampaignKind.LEGACY) {
            return campaign.getScope() == VoucherScope.STORE ? campaign.getStoreId() : null;
        }
        if (campaign.getApplicability() == VoucherApplicability.SELECTED
                && campaign.getStoreIds() != null && campaign.getStoreIds().size() == 1) {
            return campaign.getStoreIds().iterator().next();
        }
        if (campaign.getScope() == VoucherScope.STORE && campaign.getStoreId() != null) {
            return campaign.getStoreId();
        }
        return null;
    }

    private PrintQuotaSummaryResponse buildQuotaSummary(Voucher campaign, Long storeId, Role actorRole) {
        int maxPrintable = campaign.computeMaxPrintableLimit();
        int used = campaign.getUsedCount() != null ? campaign.getUsedCount() : 0;
        int reserved = campaign.getReservedCount();
        int printedCount = campaign.getPrintedCount();
        int printedUnredeemed = campaign.getPrintedUnredeemed();
        int globalPrintableRemaining = Math.max(0, maxPrintable - printedCount);
        int poolRemaining = campaign.getTotalLimit() != null && campaign.getTotalLimit() > 0
                ? Math.max(0, campaign.getTotalLimit() - used - reserved - printedUnredeemed)
                : 0;

        int applicableStoreCount = resolveApplicableStoreCount(campaign);
        boolean multiStore = applicableStoreCount > 1;

        Integer storePrintLimit = null;
        int storePrintedCount = 0;
        if (storeId != null) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM promotion_service.printed_voucher_instances
                    WHERE campaign_id = ? AND store_id = ? AND state <> 'REVOKED'
                    """, Integer.class, campaign.getId(), storeId);
            storePrintedCount = count != null ? count : 0;

            if (campaign.getMaxPrintPerStore() != null) {
                storePrintLimit = Math.min(maxPrintable, campaign.getMaxPrintPerStore());
            } else if (actorRole == Role.STORE_MANAGER && multiStore && maxPrintable > 0) {
                storePrintLimit = Math.max(1, maxPrintable / applicableStoreCount);
            } else if (!multiStore) {
                storePrintLimit = maxPrintable;
            }
        }

        int maxPrintableNow = Math.min(globalPrintableRemaining, poolRemaining);
        if (storePrintLimit != null) {
            maxPrintableNow = Math.min(maxPrintableNow, Math.max(0, storePrintLimit - storePrintedCount));
        }
        if (!campaign.isPrintable() || campaign.computeEffectiveStatus(Instant.now()) != VoucherStatus.ACTIVE) {
            maxPrintableNow = 0;
        }

        return new PrintQuotaSummaryResponse(
                campaign.getId(),
                storeId,
                campaign.isPrintable(),
                campaign.getTotalLimit(),
                used,
                reserved,
                printedCount,
                printedUnredeemed,
                maxPrintable,
                globalPrintableRemaining,
                poolRemaining,
                storePrintLimit,
                storePrintedCount,
                Math.max(0, maxPrintableNow),
                multiStore
        );
    }

    private int resolveApplicableStoreCount(Voucher campaign) {
        if (campaign.getCampaignKind() == VoucherCampaignKind.LEGACY) {
            if (campaign.getScope() == VoucherScope.STORE) {
                return 1;
            }
            return stores.countActiveStores();
        }
        if (campaign.getApplicability() == VoucherApplicability.SELECTED) {
            return Math.max(1, campaign.getStoreIds() != null ? campaign.getStoreIds().size() : 1);
        }
        return stores.countActiveStores();
    }

    private boolean isStoreEligible(Voucher campaign, Long storeId) {
        if (storeId == null || storeId <= 0) {
            return false;
        }
        if (campaign.getCampaignKind() == VoucherCampaignKind.LEGACY) {
            return campaign.getScope() == VoucherScope.ALL
                    || (campaign.getScope() == VoucherScope.STORE && storeId.equals(campaign.getStoreId()));
        }
        return campaign.getApplicability() == VoucherApplicability.ALL
                || (campaign.getStoreIds() != null && campaign.getStoreIds().contains(storeId));
    }

    private CurrentActor requirePrintOperator() {
        CurrentActor actor = CurrentActor.require();
        if (actor.role() != Role.ADMIN && actor.role() != Role.STORE_MANAGER) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }

    private void enforceBatchAccess(CurrentActor actor, Map<String, Object> batch) {
        if (actor.role() == Role.ADMIN) {
            return;
        }
        if (actor.role() != Role.STORE_MANAGER || actor.storeId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        Object batchStoreId = batch.get("store_id");
        if (batchStoreId == null || ((Number) batchStoreId).longValue() != actor.storeId()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private Map<String, Object> resolveCode(String raw) {
        String normalized = normalize(raw);
        Matcher matcher = CODE.matcher(normalized);
        if (!matcher.matches()) {
            throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
        }
        var result = jdbc.queryForList(
                "SELECT * FROM promotion_service.printed_voucher_instances WHERE selector = ?",
                matcher.group(1));
        if (result.isEmpty() || !verifyCode(raw, result.getFirst())) {
            throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
        }
        return result.getFirst();
    }

    private boolean verifyCode(String raw, Map<String, Object> row) {
        String normalized = normalize(raw);
        if (!CODE.matcher(normalized).matches() || ((Number) row.get("code_key_version")).intValue() != 1) {
            return false;
        }
        byte[] expected = (byte[]) row.get("code_verifier");
        return MessageDigest.isEqual(expected, verifier((byte[]) row.get("code_salt"), normalized));
    }

    private void checkInstance(Map<String, Object> row) {
        String state = (String) row.get("state");
        if ("REVOKED".equals(state)) {
            throw new ApiException(ErrorCode.VOUCHER_INACTIVE);
        }
        if (!"ISSUED".equals(state)) {
            throw new ApiException(ErrorCode.VOUCHER_EXHAUSTED);
        }
        if (!Instant.now().isBefore(instant(row, "expires_at"))) {
            throw new ApiException(ErrorCode.VOUCHER_EXPIRED);
        }
    }

    private void checkCampaign(Voucher campaign, Instant now) {
        if (campaign.getStatus() != VoucherStatus.ACTIVE) {
            throw new ApiException(ErrorCode.VOUCHER_INACTIVE);
        }
        if (now.isBefore(campaign.getStartAt())) {
            throw new ApiException(ErrorCode.VOUCHER_NOT_STARTED);
        }
        if (!now.isBefore(campaign.getEndAt())) {
            throw new ApiException(ErrorCode.VOUCHER_EXPIRED);
        }
    }

    private void checkStore(Voucher campaign, Long storeId) {
        if (!isStoreEligible(campaign, storeId)) {
            throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
        }
    }

    private BigDecimal discount(Voucher campaign, BigDecimal orderAmount) {
        if (orderAmount.compareTo(campaign.getMinOrderAmount()) < 0) {
            throw new ApiException(ErrorCode.MIN_ORDER_NOT_MET);
        }
        BigDecimal discount = campaign.getType() == VoucherType.PERCENT
                ? orderAmount.multiply(campaign.getValue()).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                : campaign.getValue();
        if (campaign.getMaxDiscount() != null && campaign.getMaxDiscount().signum() > 0) {
            discount = discount.min(campaign.getMaxDiscount());
        }
        return discount.min(orderAmount);
    }

    private void requireStaff(CurrentActor actor, long storeId) {
        if (actor.role() != Role.STAFF && actor.role() != Role.STORE_MANAGER && actor.role() != Role.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (actor.role() != Role.ADMIN && (actor.storeId() == null || actor.storeId() != storeId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private void limitAttempts(long actorId) {
        try {
            String key = "clucky:voucher:attempt:" + actorId;
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            if (count == 1) {
                redis.expire(key, Duration.ofMinutes(1));
            }
            if (count > 30) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
            }
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String derivedKey(String secret, String purpose) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Configure voucher keys or a strong JWT_SECRET");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(purpose.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot derive printed voucher key", failure);
        }
    }

    private byte[] verifier(byte[] salt, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes(hmacKey, false), "HmacSHA256"));
            mac.update(salt);
            return mac.doFinal(code.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception failure) {
            throw new IllegalStateException("Printed voucher verifier unavailable");
        }
    }

    private String encrypt(String code) {
        try {
            byte[] nonce = randomBytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes(encryptionKey, true), "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(code.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception failure) {
            throw new IllegalStateException("Printed voucher encryption unavailable");
        }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] packed = Base64.getUrlDecoder().decode(ciphertext);
            if (packed.length < 29) {
                throw new IllegalArgumentException("Invalid printed voucher ciphertext");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes(encryptionKey, true), "AES"),
                    new GCMParameterSpec(128, packed, 0, 12));
            return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.US_ASCII);
        } catch (Exception failure) {
            throw new IllegalStateException("Printed voucher decryption unavailable");
        }
    }

    private byte[] keyBytes(String configured, boolean aes) {
        try {
            byte[] key = Base64.getDecoder().decode(configured);
            if ((aes && key.length != 32) || (!aes && key.length < 32)) {
                throw new IllegalArgumentException("Invalid key length");
            }
            return key;
        } catch (RuntimeException failure) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String normalize(String raw) {
        if (raw == null || raw.length() > 100) {
            return "";
        }
        String candidate = raw.strip().toUpperCase(Locale.ROOT);
        if (candidate.startsWith("CLUCKY:P1:")) {
            candidate = candidate.substring("CLUCKY:P1:".length());
        }
        return candidate.replace("-", "").replace(" ", "");
    }

    private static byte[] randomBytes(int size) {
        byte[] result = new byte[size];
        RANDOM.nextBytes(result);
        return result;
    }

    private static String randomChars(int length) {
        byte[] bytes = randomBytes(length);
        StringBuilder result = new StringBuilder(length);
        for (byte b : bytes) {
            result.append(ALPHABET[b & 31]);
        }
        return result.toString();
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private static Instant instant(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : (Instant) value;
    }
}
