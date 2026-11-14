package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.exception.BadRequestException;
import com.clucky.promotionloyaltyservice.exception.ConflictException;
import com.clucky.promotionloyaltyservice.exception.ApiException;
import com.clucky.promotionloyaltyservice.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Temporary account presence. The QR secret and the shorter earn-only code are
 * different credentials and are never persisted as plaintext.
 */
@Service
public class LiveLoyaltySessionService {
    // Cover a full 30-second QR cycle plus its 30-second staff-bound proof.
    // Hidden browser tabs can throttle heartbeats, but leaving the Loyalty
    // route still explicitly revokes its session and tokens stay short-lived.
    private static final Duration LEASE = Duration.ofSeconds(70);
    private static final Duration CYCLE = Duration.ofSeconds(30);
    private static final String QR_PREFIX = "CLUCKY:LOYALTY:V1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final RedisScript<Long> OPEN_SCRIPT = script("""
            local prior=redis.call('GET',KEYS[1])
            redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2])
            if prior and prior~=ARGV[1] then redis.call('DEL','loyalty:session:'..prior) end
            return 1
            """);
    private static final RedisScript<Long> CLOSE_SCRIPT = script("""
            if redis.call('GET',KEYS[1])==ARGV[1] then
              redis.call('DEL',KEYS[1],KEYS[2])
              return 1
            end
            return 0
            """);
    private static final RedisScript<Long> HEARTBEAT_SCRIPT = script("""
            if redis.call('GET',KEYS[1])==ARGV[1] and redis.call('EXISTS',KEYS[2])==1 then
              redis.call('PEXPIRE',KEYS[1],ARGV[2])
              redis.call('PEXPIRE',KEYS[2],ARGV[2])
              return 1
            end
            return 0
            """);
    private static final RedisScript<Long> ROTATE_SCRIPT = script("""
            if redis.call('HGET',KEYS[1],'generation')~=ARGV[1]
              or redis.call('HGET',KEYS[1],'expiresAt')~=ARGV[2] then return 0 end
            redis.call('HSET',KEYS[1],'generation',ARGV[3],
              'issuedAt',ARGV[4],'expiresAt',ARGV[5],
              'textHash',ARGV[6],'qrHash',ARGV[7])
            redis.call('PEXPIRE',KEYS[1],ARGV[8])
            return 1
            """);
    private static RedisScript<Long> script(String script) {
        DefaultRedisScript<Long> result = new DefaultRedisScript<>();
        result.setScriptText(script);
        result.setResultType(Long.class);
        return result;
    }
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final VoucherAuditOutbox audit;

    public LiveLoyaltySessionService(StringRedisTemplate redis, JdbcTemplate jdbc, VoucherAuditOutbox audit) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CurrentCode(String sessionId, String loyaltyTextCode, String loyaltyQrToken,
                              Instant issuedAt, Instant expiresAt, Instant serverTime) {}
    public record ScanResult(String scanProof, Instant expiresAt, String loyaltySubject, boolean redeemAllowed) {}
    public record LookupResult(String loyaltySubject, boolean redeemAllowed, String maskedAccount) {}
    public record Subject(String kind, Long customerId, String phoneKey) {}
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static String random(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
    private String activeKey(long customerId) { return "loyalty:active:" + customerId; }
    private String sessionKey(String id) { return "loyalty:session:" + id; }
    private String credentialKey(String prefix, String value) { return "loyalty:" + prefix + ":" + hash(value); }
    public void limitStaffAttempts(String operation, long staffId, long storeId) {
        if (!java.util.Set.of("scan","lookup").contains(operation) || staffId <= 0 || storeId <= 0)
            throw new BadRequestException("Invalid operator");
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "loyalty:throttle:" + operation + ":" + staffId + ":" + storeId + ":" + minute;
        Long count = redis.opsForValue().increment(key);
        if (Long.valueOf(1).equals(count)) redis.expire(key, Duration.ofSeconds(90));
        if (count == null || count > 30) throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
    }
    public String subject(String kind, Long customerId, String phoneKey, String sessionId, Integer generation, Duration ttl) {
        if (!java.util.Set.of("PHONE","CUSTOMER","TEXT","QR").contains(kind) || ttl.isNegative() || ttl.isZero())
            throw new BadRequestException("Invalid loyalty lookup");
        String ref = random(24);
        String data = kind + "|" + (customerId == null ? "" : customerId) + "|" + (phoneKey == null ? "" : phoneKey)
                + "|" + (sessionId == null ? "" : sessionId) + "|" + (generation == null ? "" : generation);
        redis.opsForValue().set("loyalty:subject:" + ref, data, ttl);
        return ref;
    }
    public Subject resolveSubject(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9_-]{32}")) throw new BadRequestException("Missing loyalty lookup");
        String data = redis.opsForValue().get("loyalty:subject:" + ref);
        if (data == null) throw new ConflictException("Loyalty lookup expired");
        String[] parts = data.split("\\|", -1);
        if (parts.length != 5) throw new ConflictException("Loyalty lookup expired");
        if ("TEXT".equals(parts[0])) {
            Map<Object,Object> active = session(parts[3]);
            if (!parts[4].equals(active.get("generation")) ||
                    !Instant.now().isBefore(Instant.parse(active.get("expiresAt").toString())))
                throw new ConflictException("Loyalty code expired");
        }
        return new Subject(parts[0], parts[1].isEmpty() ? null : Long.valueOf(parts[1]),
                parts[2].isEmpty() ? null : parts[2]);
    }
    private Map<Object, Object> session(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new ConflictException("Loyalty session expired");
        Map<Object, Object> data = redis.opsForHash().entries(sessionKey(id));
        if (data.isEmpty()) throw new ConflictException("Loyalty session expired");
        Long owner = Long.valueOf(data.get("customerId").toString());
        if (!id.equals(redis.opsForValue().get(activeKey(owner)))) throw new ConflictException("Loyalty session revoked");
        return data;
    }

    public CurrentCode open(long customerId) {
        String id = UUID.randomUUID().toString();
        redis.opsForHash().put(sessionKey(id), "customerId", String.valueOf(customerId));
        redis.opsForHash().put(sessionKey(id), "generation", "0");
        redis.expire(sessionKey(id), LEASE);
        redis.execute(OPEN_SCRIPT, List.of(activeKey(customerId)), id, String.valueOf(LEASE.toMillis()));
        return issue(id, customerId, 1);
    }

    private CurrentCode issue(String id, long customerId, int generation) {
        Instant now = Instant.now();
        Instant expires = now.plus(CYCLE);
        // More than 80 bits of entropy for the temporary typed code; 256 for QR.
        String text = "LY-" + random(12).toUpperCase(java.util.Locale.ROOT);
        String qr = QR_PREFIX + random(32);
        redis.opsForHash().putAll(sessionKey(id), Map.of(
                "generation", String.valueOf(generation), "issuedAt", now.toString(),
                "expiresAt", expires.toString(), "textHash", hash(text), "qrHash", hash(qr)));
        redis.expire(sessionKey(id), LEASE);
        redis.opsForValue().set(credentialKey("text", text), id + ":" + generation, CYCLE);
        redis.opsForValue().set(credentialKey("qr", qr), id + ":" + generation, CYCLE);
        return new CurrentCode(id, text, qr, now, expires, now);
    }

    public Instant heartbeat(long customerId, String id) {
        Map<Object, Object> data = session(id);
        if (!String.valueOf(customerId).equals(data.get("customerId"))) throw new ConflictException("Loyalty session owner mismatch");
        Long result = redis.execute(HEARTBEAT_SCRIPT, List.of(activeKey(customerId), sessionKey(id)),
                id, String.valueOf(LEASE.toMillis()));
        if (!Long.valueOf(1).equals(result)) throw new ConflictException("Loyalty session revoked");
        return Instant.now();
    }

    public CurrentCode rotate(long customerId, String id) {
        Map<Object, Object> data = session(id);
        if (!String.valueOf(customerId).equals(data.get("customerId"))) throw new ConflictException("Loyalty session owner mismatch");
        if (Instant.now().isBefore(Instant.parse(data.get("expiresAt").toString())))
            throw new ConflictException("Current loyalty code is still active");
        int next = Integer.parseInt(data.get("generation").toString()) + 1;
        Instant now = Instant.now();
        Instant expires = now.plus(CYCLE);
        String text = "LY-" + random(12).toUpperCase(java.util.Locale.ROOT);
        String qr = QR_PREFIX + random(32);
        Long rotated = redis.execute(ROTATE_SCRIPT, List.of(sessionKey(id)),
                data.get("generation").toString(), data.get("expiresAt").toString(),
                String.valueOf(next), now.toString(), expires.toString(),
                hash(text), hash(qr), String.valueOf(LEASE.toMillis()));
        if (!Long.valueOf(1).equals(rotated) || !id.equals(redis.opsForValue().get(activeKey(customerId))))
            throw new ConflictException("Loyalty code changed in another tab");
        redis.opsForValue().set(credentialKey("text", text), id + ":" + next, CYCLE);
        redis.opsForValue().set(credentialKey("qr", qr), id + ":" + next, CYCLE);
        return new CurrentCode(id, text, qr, now, expires, now);
    }

    public void close(long customerId, String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) return;
        // Compare-and-delete prevents an old tab's close from erasing a new
        // tab's active session after a concurrent open.
        redis.execute(CLOSE_SCRIPT, List.of(activeKey(customerId), sessionKey(id)), id);
    }

    public long lookupTyped(String typed) {
        if (typed == null || !typed.matches("(?i)LY-[A-Za-z0-9_-]{14,28}")) throw new BadRequestException("Invalid loyalty text code");
        String entry = redis.opsForValue().get(credentialKey("text", typed.toUpperCase(java.util.Locale.ROOT)));
        if (entry == null) throw new ConflictException("Loyalty code expired");
        String[] parts = entry.split(":", 2);
        Map<Object, Object> data = session(parts[0]);
        if (!parts[1].equals(data.get("generation")) || !hash(typed.toUpperCase(java.util.Locale.ROOT)).equals(data.get("textHash"))
                || !Instant.now().isBefore(Instant.parse(data.get("expiresAt").toString())))
            throw new ConflictException("Loyalty code expired");
        return Long.parseLong(data.get("customerId").toString());
    }

    @Transactional
    public ScanResult scan(String qrToken, long staffId, String actorRole, long storeId, String draftHash) {
        limitStaffAttempts("scan", staffId, storeId);
        if (qrToken == null || !qrToken.startsWith(QR_PREFIX) || qrToken.length() > 160
                || !qrToken.substring(QR_PREFIX.length()).matches("[A-Za-z0-9_-]{40,60}")
                || draftHash == null || !draftHash.matches("[a-f0-9]{64}") || storeId <= 0)
            throw new BadRequestException("Invalid loyalty QR or order draft");
        // GETDEL means one QR cannot mint two independent redemption proofs.
        String entry = redis.opsForValue().getAndDelete(credentialKey("qr", qrToken));
        if (entry == null) throw new ConflictException("Loyalty QR expired or already scanned");
        String[] parts = entry.split(":", 2);
        Map<Object, Object> data = session(parts[0]);
        if (!parts[1].equals(data.get("generation")) || !hash(qrToken).equals(data.get("qrHash"))
                || !Instant.now().isBefore(Instant.parse(data.get("expiresAt").toString())))
            throw new ConflictException("Loyalty QR expired");
        long customerId = Long.parseLong(data.get("customerId").toString());
        String proof = random(32);
        Instant expires = Instant.now().plus(CYCLE);
        jdbc.update("""
                INSERT INTO promotion_service.loyalty_scan_proofs
                  (proof_hash,customer_id,session_id,staff_id,store_id,draft_hash,expires_at)
                VALUES (?,?,?,?,?,?,?)
                """, hash(proof), customerId, parts[0], staffId, storeId, draftHash, java.sql.Timestamp.from(expires));
        audit.record(staffId, actorRole, storeId, "LOYALTY_QR_SCANNED", "STORE", storeId,
                Map.of(), Map.of("method", "QR_SCAN", "redeemAllowed", true));
        return new ScanResult(proof, expires, subject("QR", customerId, null, parts[0],
                Integer.valueOf(parts[1]), CYCLE), true);
    }

    @Transactional
    public long checkProof(String proof, long orderId, long staffId, long storeId, String draftHash, boolean consume) {
        if (proof == null || !proof.matches("[A-Za-z0-9_-]{40,60}") || draftHash == null
                || !draftHash.matches("[a-f0-9]{64}")) throw new BadRequestException("Missing loyalty QR scan");
        var rows = jdbc.queryForList("SELECT * FROM promotion_service.loyalty_scan_proofs WHERE proof_hash=?"
                + (consume ? " FOR UPDATE" : ""), hash(proof));
        if (rows.isEmpty()) throw new ConflictException("Loyalty scan expired");
        var row = rows.getFirst();
        if (((Number) row.get("staff_id")).longValue() != staffId || ((Number) row.get("store_id")).longValue() != storeId
                || !draftHash.equals(row.get("draft_hash"))
                || !Instant.now().isBefore(((java.sql.Timestamp) row.get("expires_at")).toInstant()))
            throw new ConflictException("Loyalty scan does not match this order");
        Object used = row.get("consumed_order_id");
        if (used != null) {
            if (consume && ((Number) used).longValue() == orderId) return ((Number) row.get("customer_id")).longValue();
            throw new ConflictException("Loyalty scan already used");
        }
        long customerId = ((Number) row.get("customer_id")).longValue();
        session((String) row.get("session_id"));
        if (consume) jdbc.update("UPDATE promotion_service.loyalty_scan_proofs SET consumed_order_id=? WHERE id=?",
                orderId, row.get("id"));
        return customerId;
    }
    public LookupResult lookupText(String typed) {
        long customerId = lookupTyped(typed);
        String entry = redis.opsForValue().get(credentialKey("text", typed.toUpperCase(java.util.Locale.ROOT)));
        String[] parts = entry.split(":", 2);
        Map<Object,Object> data = session(parts[0]);
        Duration remaining = Duration.between(Instant.now(), Instant.parse(data.get("expiresAt").toString()));
        if (remaining.isNegative() || remaining.isZero()) throw new ConflictException("Loyalty code expired");
        return new LookupResult(subject("TEXT", customerId, null, parts[0], Integer.parseInt(parts[1]), remaining),
                false, "••••" + Math.floorMod(customerId, 10000));
    }
    @Scheduled(fixedDelayString="${clucky.loyalty.proof-cleanup-ms:3600000}")
    public void removeExpiredProofs() {
        // A short-lived secret's hash does not need permanent storage. Batch
        // deletes bound lock time while keeping recent scan audit references.
        jdbc.update("""
                DELETE FROM promotion_service.loyalty_scan_proofs WHERE id IN (
                  SELECT id FROM promotion_service.loyalty_scan_proofs
                  WHERE expires_at < now() - interval '24 hours'
                  ORDER BY expires_at LIMIT 1000
                )
                """);
    }
}
