package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.dto.Role;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherRequest;
import com.clucky.promotionloyaltyservice.dto.ValidateVoucherResponse;
import com.clucky.promotionloyaltyservice.dto.VoucherResponse;
import com.clucky.promotionloyaltyservice.exception.ApiException;
import com.clucky.promotionloyaltyservice.exception.ConflictException;
import com.clucky.promotionloyaltyservice.exception.ErrorCode;
import com.clucky.promotionloyaltyservice.model.Voucher;
import com.clucky.promotionloyaltyservice.model.VoucherApplicability;
import com.clucky.promotionloyaltyservice.model.VoucherCampaignKind;
import com.clucky.promotionloyaltyservice.model.VoucherScope;
import com.clucky.promotionloyaltyservice.model.VoucherStatus;
import com.clucky.promotionloyaltyservice.model.VoucherType;
import com.clucky.promotionloyaltyservice.repository.VoucherRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Personal vouchers are bearer credentials at the counter and owner-scoped IDs on Web.
 * Raw codes exist only in the owner response and during verification, never in DB/logs.
 */
@Service
public class PersonalVoucherService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final Pattern CODE = Pattern.compile("CK1([A-Z2-7]{12})([A-Z2-7]{32})");
    private final VoucherRepository campaigns;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final VoucherAuditOutbox audit;
    private final String hmacKey;
    private final String encryptionKey;

    public PersonalVoucherService(VoucherRepository campaigns, JdbcTemplate jdbc, StringRedisTemplate redis,
            VoucherAuditOutbox audit, @Value("${CLUCKY_VOUCHER_HMAC_KEY:}") String hmacKey,
            @Value("${CLUCKY_VOUCHER_ENC_KEY:}") String encryptionKey,
            @Value("${JWT_SECRET:}") String jwtSecret) {
        this.campaigns = campaigns;
        this.jdbc = jdbc;
        this.redis = redis;
        this.audit = audit;
        // Explicit voucher keys take precedence. Existing installations without
        // them derive separate, stable keys from the already required JWT secret.
        this.hmacKey = hmacKey == null || hmacKey.isBlank()
                ? derivedKey(jwtSecret, "clucky-personal-voucher-hmac-v1") : hmacKey;
        this.encryptionKey = encryptionKey == null || encryptionKey.isBlank()
                ? derivedKey(jwtSecret, "clucky-personal-voucher-aes-v1") : encryptionKey;
    }

    private static String derivedKey(String secret, String purpose) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("Configure voucher keys or a strong JWT_SECRET");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(purpose.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot derive personal voucher key", failure);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonalVoucherView(UUID instanceId, Long campaignId, String state,
            Instant issuedAt, Instant expiresAt, String code, String qrPayload, VoucherResponse campaign) {}

    @Transactional
    public PersonalVoucherView claim(Long campaignId, long ownerId) {
        if (campaignId == null || campaignId <= 0 || ownerId <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        Voucher campaign = campaigns.findByIdWithLock(campaignId)
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        var existing = jdbc.queryForList("SELECT * FROM promotion_service.customer_voucher_instances WHERE campaign_id=? AND customer_id=?",
                campaignId, ownerId);
        if (!existing.isEmpty()) return view(existing.getFirst(), campaign, false);
        checkCampaign(campaign, Instant.now());
        // Claiming does not consume campaign usage or reserve a quota slot.
        String selector = randomChars(12);
        String code = "CK1-" + selector + "-" + randomChars(32);
        byte[] salt = randomBytes(24);
        String cipher = encrypt(code);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO promotion_service.customer_voucher_instances "
                + "(id,campaign_id,customer_id,selector,code_salt,code_verifier,code_ciphertext,expires_at) "
                + "VALUES (?,?,?,?,?,?,?,?)",
                id, campaignId, ownerId, selector, salt, verifier(salt, normalize(code)), cipher,
                java.sql.Timestamp.from(campaign.getEndAt()));
        audit.record(ownerId, Role.CUSTOMER.name(), null, "VOUCHER_CLAIMED",
                "VOUCHER_CAMPAIGN", campaignId, Map.of(), Map.of("instanceId", id.toString()));
        return view(jdbc.queryForMap("SELECT * FROM promotion_service.customer_voucher_instances WHERE id=?", id), campaign, false);
    }

    @Transactional(readOnly = true)
    public List<PersonalVoucherView> mine(long ownerId) {
        var result = new ArrayList<PersonalVoucherView>();
        var rows = jdbc.queryForList("SELECT * FROM promotion_service.customer_voucher_instances WHERE customer_id=? ORDER BY issued_at DESC", ownerId);
        for (var row : rows) {
            var campaign = campaigns.findById(number(row, "campaign_id"))
                    .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
            result.add(view(row, campaign, false));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PersonalVoucherView detail(UUID instanceId, long ownerId) {
        var rows = jdbc.queryForList(
                "SELECT * FROM promotion_service.customer_voucher_instances WHERE id=? AND customer_id=?",
                instanceId, ownerId);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        var row = rows.getFirst();
        var campaign = campaigns.findById(number(row, "campaign_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        return view(row, campaign, true);
    }

    @Transactional(readOnly = true)
    public ValidateVoucherResponse validate(ValidateVoucherRequest request) {
        if (request == null || request.getOrderAmount() == null || request.getOrderAmount().signum() < 0
                || request.getOrderAmount().stripTrailingZeros().scale() > 0
                || request.getOrderAmount().precision() > 12 || request.getStoreId() == null || request.getStoreId() <= 0
                || (request.getVoucherInstanceId() == null) == (request.getCode() == null || request.getCode().isBlank()))
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        CurrentActor actor = CurrentActor.require();
        Map<String, Object> row;
        if (request.getVoucherInstanceId() != null) {
            if (actor.role() != Role.CUSTOMER) throw new ApiException(ErrorCode.FORBIDDEN);
            var rows = jdbc.queryForList(
                    "SELECT * FROM promotion_service.customer_voucher_instances WHERE id=? AND customer_id=?",
                    request.getVoucherInstanceId(), actor.userId());
            if (rows.isEmpty()) throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
            row = rows.getFirst();
        } else {
            requireStaff(actor, request.getStoreId());
            limitAttempts(actor.userId());
            row = resolveCode(request.getCode());
        }
        Voucher campaign = campaigns.findById(number(row, "campaign_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        checkInstance(row);
        checkCampaign(campaign, Instant.now());
        checkStore(campaign, request.getStoreId());
        checkQuota(campaign);
        BigDecimal discount = discount(campaign, request.getOrderAmount());
        ValidateVoucherResponse response = ValidateVoucherResponse.valid(
                discount, VoucherResponse.fromPublicEntity(campaign, Instant.now()));
        response.setVoucherInstanceId((UUID) row.get("id"));
        return response;
    }

    /** Internal Order service only. Campaign -> instance -> reservation lock order. */
    @Transactional
    public UUID reserve(long orderId, Long orderCustomerId, long storeId, String rawCode, UUID requestedId,
            BigDecimal amount, BigDecimal expectedDiscount) {
        if (orderId <= 0 || storeId <= 0 || amount == null || amount.signum() < 0
                || expectedDiscount == null || expectedDiscount.signum() < 0
                || amount.stripTrailingZeros().scale() > 0 || expectedDiscount.stripTrailingZeros().scale() > 0
                || expectedDiscount.compareTo(amount) > 0 || (rawCode == null) == (requestedId == null))
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        Map<String,Object> initial = rawCode != null ? resolveCode(rawCode) : findInstance(requestedId);
        if (requestedId != null && (orderCustomerId == null || number(initial, "customer_id") != orderCustomerId))
            throw new ApiException(ErrorCode.FORBIDDEN);
        Voucher campaign = campaigns.findByIdWithLock(number(initial, "campaign_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        UUID id = (UUID) initial.get("id");
        var row = jdbc.queryForMap("SELECT * FROM promotion_service.customer_voucher_instances WHERE id=? FOR UPDATE", id);
        if (rawCode != null && !verifyCode(rawCode, row)) throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
        // Same reservation retried by Order is idempotent only for identical details.
        var existing = jdbc.queryForList("SELECT * FROM promotion_service.voucher_reservations WHERE order_id=? FOR UPDATE", orderId);
        if (!existing.isEmpty()) {
            var reservation = existing.getFirst();
            if (!"RESERVED".equals(reservation.get("state")) && !"COMPLETED".equals(reservation.get("state")))
                throw new ConflictException("Voucher order reservation is closed");
            if (!id.equals(reservation.get("voucher_instance_id")) || number(reservation,"store_id") != storeId
                    || !java.util.Objects.equals(reservation.get("customer_id"), orderCustomerId)
                    || amount.compareTo((BigDecimal)reservation.get("order_amount")) != 0
                    || expectedDiscount.compareTo((BigDecimal)reservation.get("discount_amount")) != 0)
                throw new ConflictException("Voucher reservation mismatch");
            return id;
        }
        checkInstance(row);
        checkCampaign(campaign, Instant.now());
        checkStore(campaign, storeId);
        checkQuota(campaign);
        if (discount(campaign, amount).compareTo(expectedDiscount) != 0)
            throw new ConflictException("Voucher discount has changed");
        jdbc.update("INSERT INTO promotion_service.voucher_reservations"
                + "(order_id,voucher_id,customer_id,store_id,code,order_amount,discount_amount,state,voucher_instance_id,instance_owner_id)"
                + " VALUES (?,?,?,?,NULL,?,?,'RESERVED',?,?)",
                orderId,campaign.getId(),orderCustomerId,storeId,amount,expectedDiscount,id,number(row,"customer_id"));
        jdbc.update("UPDATE promotion_service.customer_voucher_instances "
                + "SET state='RESERVED', reserved_order_id=?,version=version+1 WHERE id=?",orderId,id);
        campaign.setReservedCount(Math.addExact(campaign.getReservedCount(),1));
        campaigns.saveAndFlush(campaign);
        return id;
    }

    /** Called by the existing voucher event settlement, in the same transaction. */
    @Transactional
    public void finish(Map<String,Object> reservation, String target, String eventId) {
        UUID id = (UUID) reservation.get("voucher_instance_id");
        Voucher campaign = campaigns.findByIdWithLock(number(reservation, "voucher_id"))
                .orElseThrow(() -> new ApiException(ErrorCode.VOUCHER_NOT_FOUND));
        var instance = jdbc.queryForMap("SELECT * FROM promotion_service.customer_voucher_instances WHERE id=? FOR UPDATE", id);
        String current = (String) reservation.get("state");
        long orderId = number(reservation, "order_id");
        if ("RESERVED".equals(current)) {
            if (!"RESERVED".equals(instance.get("state")) || number(instance, "reserved_order_id") != orderId)
                throw new ConflictException("Voucher instance/reservation mismatch");
            campaign.setReservedCount(Math.subtractExact(campaign.getReservedCount(), 1));
            boolean success = "COMPLETED".equals(target);
            if (success) {
                campaign.setUsedCount(Math.addExact(campaign.getUsedCount(), 1));
                jdbc.update("INSERT INTO promotion_service.voucher_usages(voucher_id,customer_id,order_id,voucher_instance_id)"
                        + " VALUES (?,?,?,?)",campaign.getId(),number(instance,"customer_id"),orderId,id);
            }
            String next = success ? "REDEEMED" : Instant.now().isAfter(instant(instance,"expires_at")) ? "EXPIRED" : "ISSUED";
            jdbc.update("UPDATE promotion_service.customer_voucher_instances SET state=?,reserved_order_id=NULL,"
                    + "redeemed_order_id=?,version=version+1 WHERE id=?",
                    next, success ? orderId : null, id);
            jdbc.update("UPDATE promotion_service.voucher_reservations SET state=?,event_id=?,updated_at=now() WHERE order_id=?",
                    target,eventId,orderId);
            if (!success) auditCancellation(orderId,id,"RELEASE",eventId);
            campaigns.saveAndFlush(campaign);
        } else if ("COMPLETED".equals(current) && "RELEASED".equals(target)) {
            // Only the Order authoritative CANCELLED state may invoke this. Refund never does.
            if (!"REDEEMED".equals(instance.get("state")) || number(instance,"redeemed_order_id") != orderId)
                throw new ConflictException("Voucher reversal state mismatch");
            String reversalId = eventId == null ? "cancel-" + orderId : eventId;
            int count = jdbc.update("UPDATE promotion_service.voucher_usages SET reversed_at=now(),reversal_id=?"
                    + " WHERE order_id=? AND voucher_instance_id=? AND reversed_at IS NULL",reversalId,orderId,id);
            if (count != 1) throw new ConflictException("Voucher reversal usage mismatch");
            campaign.setUsedCount(Math.subtractExact(campaign.getUsedCount(),1));
            String next = Instant.now().isAfter(instant(instance,"expires_at")) ? "EXPIRED" : "ISSUED";
            jdbc.update("UPDATE promotion_service.customer_voucher_instances SET state=?,redeemed_order_id=NULL,"
                    + "version=version+1 WHERE id=?",next,id);
            jdbc.update("UPDATE promotion_service.voucher_reservations SET state='RELEASED',event_id=?,updated_at=now() WHERE order_id=?",
                    eventId,orderId);
            auditCancellation(orderId,id,"REVERSE",reversalId);
            campaigns.saveAndFlush(campaign);
        }
    }

    /** Refunds are auditable but do not change a redeemed voucher or global usage. */
    @Transactional
    public void recordRefund(long orderId, String refundId) {
        if (orderId <= 0 || refundId == null || !refundId.matches("[A-Za-z0-9._-]{1,160}"))
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        var rows = jdbc.queryForList("SELECT * FROM promotion_service.voucher_reservations WHERE order_id=? FOR UPDATE",orderId);
        if (rows.isEmpty() || rows.getFirst().get("voucher_instance_id") == null) return;
        // Most refunds are for cancelled orders whose voucher was released, so
        // there is nothing to restore or audit as a redeemed voucher.
        if (!"COMPLETED".equals(rows.getFirst().get("state"))) return;
        auditCancellation(orderId, (UUID) rows.getFirst().get("voucher_instance_id"),"REFUND_NO_RESTORE","refund-"+refundId);
    }

    private void auditCancellation(long orderId, UUID id, String action, String eventId) {
        jdbc.update("INSERT INTO promotion_service.voucher_cancellation_audit(order_id,instance_id,action,event_id)"
                + " VALUES (?,?,?,?) ON CONFLICT (event_id) DO NOTHING",orderId,id,action,
                eventId == null ? action.toLowerCase(Locale.ROOT)+"-"+orderId : eventId);
    }

    private PersonalVoucherView view(Map<String,Object> row, Voucher campaign, boolean reveal) {
        String state = (String) row.get("state");
        Instant expiresAt = instant(row,"expires_at");
        if ("ISSUED".equals(state) && Instant.now().isAfter(expiresAt)) state = "EXPIRED";
        String code = reveal && "ISSUED".equals(state) ? decrypt((String)row.get("code_ciphertext")) : null;
        return new PersonalVoucherView((UUID)row.get("id"),campaign.getId(),state,
                instant(row,"issued_at"),expiresAt,code,code == null ? null : "CLUCKY:V1:"+code,
                VoucherResponse.fromPublicEntity(campaign, Instant.now()));
    }

    private Map<String,Object> findInstance(UUID id) {
        var result = jdbc.queryForList("SELECT * FROM promotion_service.customer_voucher_instances WHERE id=?",id);
        if (result.isEmpty()) throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
        return result.getFirst();
    }

    private Map<String,Object> resolveCode(String raw) {
        String normalized = normalize(raw);
        Matcher matcher = CODE.matcher(normalized);
        if (!matcher.matches()) throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
        var result = jdbc.queryForList("SELECT * FROM promotion_service.customer_voucher_instances WHERE selector=?",matcher.group(1));
        if (result.isEmpty() || !verifyCode(raw,result.getFirst())) throw new ApiException(ErrorCode.VOUCHER_NOT_FOUND);
        return result.getFirst();
    }

    private boolean verifyCode(String raw, Map<String,Object> row) {
        String normalized = normalize(raw);
        if (!CODE.matcher(normalized).matches() || ((Number)row.get("code_key_version")).intValue() != 1)
            return false;
        byte[] expected = (byte[])row.get("code_verifier");
        return MessageDigest.isEqual(expected,verifier((byte[])row.get("code_salt"),normalized));
    }

    private void checkInstance(Map<String,Object> row) {
        if (!"ISSUED".equals(row.get("state")))
            throw new ApiException(ErrorCode.VOUCHER_EXHAUSTED);
        if (!Instant.now().isBefore(instant(row,"expires_at")))
            throw new ApiException(ErrorCode.VOUCHER_EXPIRED);
    }

    private void checkCampaign(Voucher campaign, Instant now) {
        if (campaign.getStatus() != VoucherStatus.ACTIVE) throw new ApiException(ErrorCode.VOUCHER_INACTIVE);
        if (now.isBefore(campaign.getStartAt())) throw new ApiException(ErrorCode.VOUCHER_NOT_STARTED);
        if (!now.isBefore(campaign.getEndAt())) throw new ApiException(ErrorCode.VOUCHER_EXPIRED);
    }

    private void checkStore(Voucher campaign, Long storeId) {
        if (storeId == null || storeId <= 0 || (campaign.getCampaignKind() == VoucherCampaignKind.LEGACY
                ? campaign.getScope() == VoucherScope.STORE && !storeId.equals(campaign.getStoreId())
                : campaign.getApplicability() == VoucherApplicability.SELECTED
                    && !campaign.getStoreIds().contains(storeId)))
            throw new ApiException(ErrorCode.VOUCHER_SCOPE_MISMATCH);
    }

    private void checkQuota(Voucher campaign) {
        if (campaign.getTotalLimit() != null && campaign.getTotalLimit() > 0
                && (long) campaign.getUsedCount() + campaign.getReservedCount() >= campaign.getTotalLimit())
            throw new ApiException(ErrorCode.VOUCHER_EXHAUSTED);
    }

    private BigDecimal discount(Voucher campaign, BigDecimal orderAmount) {
        if (orderAmount.compareTo(campaign.getMinOrderAmount()) < 0)
            throw new ApiException(ErrorCode.MIN_ORDER_NOT_MET);
        BigDecimal discount = campaign.getType() == VoucherType.PERCENT
                ? orderAmount.multiply(campaign.getValue()).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP)
                : campaign.getValue();
        if (campaign.getMaxDiscount() != null && campaign.getMaxDiscount().signum() > 0)
            discount = discount.min(campaign.getMaxDiscount());
        return discount.min(orderAmount);
    }

    private void requireStaff(CurrentActor actor, long storeId) {
        if (actor.role() != Role.STAFF && actor.role() != Role.STORE_MANAGER && actor.role() != Role.ADMIN)
            throw new ApiException(ErrorCode.FORBIDDEN);
        if (actor.role() != Role.ADMIN && (actor.storeId() == null || actor.storeId() != storeId))
            throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private void limitAttempts(long actorId) {
        // Fail closed if the shared rate limiter is unavailable; never log the presented code.
        try {
            String key = "clucky:voucher:attempt:"+actorId;
            Long count = redis.opsForValue().increment(key);
            if (count == null) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            if (count == 1) redis.expire(key,Duration.ofMinutes(1));
            if (count > 30) throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        } catch (ApiException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    private byte[] verifier(byte[] salt, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes(hmacKey,false),"HmacSHA256"));
            mac.update(salt);
            return mac.doFinal(code.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception failure) { throw new IllegalStateException("Personal voucher verifier unavailable"); }
    }

    private String encrypt(String code) {
        try {
            byte[] nonce = randomBytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(keyBytes(encryptionKey,true),"AES"),new GCMParameterSpec(128,nonce));
            byte[] encrypted = cipher.doFinal(code.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception failure) { throw new IllegalStateException("Personal voucher encryption unavailable"); }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] packed = Base64.getUrlDecoder().decode(ciphertext);
            if (packed.length < 29) throw new IllegalArgumentException("Invalid voucher ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(keyBytes(encryptionKey,true),"AES"),
                    new GCMParameterSpec(128,packed,0,12));
            return new String(cipher.doFinal(packed,12,packed.length-12),StandardCharsets.US_ASCII);
        } catch (Exception failure) { throw new IllegalStateException("Personal voucher decryption unavailable"); }
    }

    private byte[] keyBytes(String configured, boolean aes) {
        try {
            byte[] key = Base64.getDecoder().decode(configured);
            if ((aes && key.length != 32) || (!aes && key.length < 32))
                throw new IllegalArgumentException("Invalid key length");
            return key;
        } catch (RuntimeException failure) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String normalize(String raw) {
        if (raw == null || raw.length() > 100) return "";
        String candidate = raw.strip().toUpperCase(Locale.ROOT);
        if (candidate.startsWith("CLUCKY:V1:")) candidate = candidate.substring("CLUCKY:V1:".length());
        return candidate.replace("-","").replace(" ","");
    }

    private static byte[] randomBytes(int size) {
        byte[] result = new byte[size]; RANDOM.nextBytes(result); return result;
    }

    private static String randomChars(int length) {
        byte[] bytes = randomBytes(length);
        StringBuilder result = new StringBuilder(length);
        for (byte b: bytes) result.append(ALPHABET[b & 31]);
        return result.toString();
    }

    private static long number(Map<String,Object> row, String field) { return ((Number)row.get(field)).longValue(); }
    private static Instant instant(Map<String,Object> row, String field) {
        Object value = row.get(field);
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant() : (Instant)value;
    }
}
