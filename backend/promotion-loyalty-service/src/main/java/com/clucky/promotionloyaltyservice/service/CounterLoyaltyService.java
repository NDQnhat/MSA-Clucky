package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.config.AccessGuard;
import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.config.JwtSettings;
import com.clucky.promotionloyaltyservice.dto.LoyaltyData.*;
import com.clucky.promotionloyaltyservice.exception.BadRequestException;
import com.clucky.promotionloyaltyservice.exception.ConflictException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** Server-side POS quote, wallet ownership and one-time loyalty redemption. */
@Service
public class CounterLoyaltyService {
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;
    private final LoyaltyService loyalty;
    private final LiveLoyaltySessionService sessions;
    private final AccessGuard access;
    private final JwtSettings security;
    private final VoucherAuditOutbox audit;
    private final InternalServiceCredential credential;
    private final RestClient identity;
    private final String identityUrl;

    public record CounterQuote(long policyVersion, long availablePoints, int pointsUsed, BigDecimal pointsAmount,
            long pointsToEarn, String tier, BigDecimal tierDiscount, boolean redeemAllowed, Long walletId) {}
    public record Lookup(String loyaltySubject, boolean redeemAllowed, String maskedAccount) {}
    public record ClaimStatus(String status, long claimedPoints, int verifiedPhones,
                              int claimedWallets) {}
    private record Wallet(long id, Long customerId, long total, long reserved, long lifetime,
                          long clawback, String state, Long mergedInto) {}
    public record CounterRequest(String loyaltySubject, String scanProof, int pointsUsed,
            BigDecimal amount, long staffId, long storeId, String draftHash) {}
    public record CounterReserve(long orderId, String loyaltySubject, String scanProof, int pointsUsed,
            BigDecimal amount, long policyVersion, BigDecimal pointsAmount, BigDecimal tierDiscount, long pointsToEarn,
            long staffId, long storeId, String draftHash) {}

    public CounterLoyaltyService(JdbcTemplate jdbc, LoyaltyService loyalty, LiveLoyaltySessionService sessions,
            AccessGuard access, JwtSettings security, InternalServiceCredential credential,
            @LoadBalanced RestClient.Builder builder,
            @Value("${clucky.identity.service-url:http://identity-service}") String identityUrl,
            VoucherAuditOutbox audit) {
        this.jdbc = jdbc; this.loyalty = loyalty; this.sessions = sessions;
        this.access = access; this.security = security;
        this.credential = credential; this.identity = builder.build(); this.identityUrl = identityUrl;
        this.audit = audit;
    }

    public String phoneKey(String input) {
        String phone = normalizePhone(input);
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(security.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(
                    ("clucky:loyalty:phone-key:v1:" + phone).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Phone key derivation unavailable");
        }
    }
    private String normalizePhone(String value) {
        if (value == null) throw new BadRequestException("Phone number required");
        String trimmed = value.replaceAll("[\\s.()-]", "");
        if (trimmed.startsWith("0")) trimmed = "+84" + trimmed.substring(1);
        if (!trimmed.matches("\\+84[35789][0-9]{8}")) throw new BadRequestException("Invalid Vietnamese phone");
        return trimmed;
    }
    @Transactional
    public Lookup lookup(String kind, String value, long storeId) {
        access.requireStore(storeId);
        sessions.limitStaffAttempts("lookup", CurrentActor.require().userId(), storeId);
        audit.record(CurrentActor.require().userId(), CurrentActor.require().role().name(), storeId,
                "LOYALTY_EARN_ONLY_LOOKUP", "STORE", storeId, Map.of(), Map.of("lookupKind", kind));
        if ("LOYALTY_TEXT_CODE".equals(kind)) {
            var code = sessions.lookupText(value);
            return new Lookup(code.loyaltySubject(), false, code.maskedAccount());
        }
        if ("PHONE".equals(kind)) {
            String key = phoneKey(value);
            List<Long> users = jdbc.queryForList(
                    "SELECT customer_id FROM promotion_service.loyalty_verified_phones WHERE phone_key=?",
                    Long.class, key);
            String ref = users.isEmpty()
                    ? sessions.subject("PHONE", null, key, null, null, Duration.ofMinutes(5))
                    : sessions.subject("CUSTOMER", users.getFirst(), null, null, null, Duration.ofMinutes(5));
            return new Lookup(ref, false, "••••" + normalizePhone(value).substring(8));
        }
        if ("EMAIL".equals(kind)) {
            if (value == null || !value.strip().matches("(?i)^[a-z0-9._%+\\-]{1,64}@[a-z0-9.\\-]{1,190}\\.[a-z]{2,}$"))
                throw new BadRequestException("Invalid email");
            record VerifiedEmail(long customerId) {}
            VerifiedEmail email = identity.post()
                    .uri(identityUrl + "/internal/identity/verified-email/resolve")
                    .headers(headers -> headers.setBearerAuth(credential.token()))
                    .body(Map.of("email", value.strip().toLowerCase(java.util.Locale.ROOT)))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request,response) -> {
                        throw new BadRequestException("Verified account not found");
                    })
                    .body(VerifiedEmail.class);
            if (email == null || email.customerId() <= 0) throw new BadRequestException("Verified account not found");
            return new Lookup(sessions.subject("CUSTOMER", email.customerId(), null, null, null,
                    Duration.ofMinutes(5)), false, "••••" + Math.floorMod(email.customerId(),10000));
        }
        throw new BadRequestException("Invalid lookup type");
    }

    private Wallet wallet(LiveLoyaltySessionService.Subject subject, boolean create, boolean lock) {
        if (subject == null) return null;
        if (subject.phoneKey() != null) {
            phoneClaimLock(subject.phoneKey());
            // A PHONE subject may have been issued seconds before the first
            // verified-phone event committed. Re-resolve on every quote/reserve
            // so that a stale earn-only reference cannot recreate a guest
            // wallet after that phone was merged into its verified owner.
            List<Long> verifiedOwners = jdbc.queryForList("""
                    SELECT customer_id FROM promotion_service.loyalty_verified_phones
                    WHERE phone_key=?
                    """, Long.class, subject.phoneKey());
            if (!verifiedOwners.isEmpty()) {
                return customerWallet(verifiedOwners.getFirst(), create, lock);
            }
            return guestWallet(subject.phoneKey(), create, lock);
        }
        if (subject.customerId() != null) {
            return customerWallet(subject.customerId(), create, lock);
        }
        return null;
    }
    private void phoneClaimLock(String key) {
        // Serialize first guest creation, stale PHONE reference resolution and
        // verified-phone claim, including the race where no guest row exists.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                "loyalty:phone:" + key);
    }
    private Wallet customerWallet(long customerId, boolean create, boolean lock) {
        if (create) jdbc.update("""
                INSERT INTO promotion_service.member_points(customer_id) VALUES (?)
                ON CONFLICT(customer_id) DO NOTHING
                """, customerId);
        var rows = jdbc.query("SELECT * FROM promotion_service.member_points WHERE customer_id=?"
                + (lock ? " FOR UPDATE" : ""), (r,i) -> walletRow(r), customerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Wallet guestWallet(String phoneKey, boolean create, boolean lock) {
        if (create) jdbc.update("""
                INSERT INTO promotion_service.member_points(customer_id,owner_type,phone_key,phone_key_version)
                VALUES (NULL,'PHONE',?,1) ON CONFLICT (phone_key_version,phone_key)
                WHERE owner_type='PHONE' DO NOTHING
                """, phoneKey);
        var rows = jdbc.query("SELECT * FROM promotion_service.member_points WHERE owner_type='PHONE' AND phone_key=?"
                + (lock ? " FOR UPDATE" : ""), (r,i) -> walletRow(r), phoneKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Wallet walletRow(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Wallet(row.getLong("id"), row.getObject("customer_id", Long.class), row.getLong("total_points"),
                row.getLong("reserved_points"), row.getLong("lifetime_points"),
                row.getLong("pending_clawback_points"), row.getString("wallet_state"),
                row.getObject("merged_into_wallet_id", Long.class));
    }
    private Wallet destination(Wallet wallet, boolean lock) {
        if (wallet == null || wallet.mergedInto() == null) return wallet;
        var rows = jdbc.query("SELECT * FROM promotion_service.member_points WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (r,i) -> walletRow(r), wallet.mergedInto());
        if (rows.isEmpty()) throw new ConflictException("Merged loyalty wallet missing");
        return rows.getFirst();
    }
    private Wallet walletById(long id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM promotion_service.member_points WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (r,i) -> walletRow(r), id);
        return rows.isEmpty() ? null : destination(rows.getFirst(), lock);
    }
    public ClaimStatus claimStatus(long customerId) {
        if (customerId <= 0) throw new BadRequestException("Invalid customer");
        Long verified = jdbc.queryForObject("""
                SELECT count(*) FROM promotion_service.loyalty_verified_phones
                WHERE customer_id=?
                """, Long.class, customerId);
        Long claimed = jdbc.queryForObject("""
                SELECT count(*) FROM promotion_service.loyalty_phone_claims
                WHERE verified_customer_id=?
                """, Long.class, customerId);
        Long transferred = jdbc.queryForObject("""
                SELECT COALESCE(sum(amount),0) FROM promotion_service.loyalty_phone_claims
                WHERE verified_customer_id=?
                """, Long.class, customerId);
        return new ClaimStatus(verified != null && verified > 0 ? "COMPLETED" : "PENDING",
                transferred == null ? 0 : transferred, verified == null ? 0 : Math.toIntExact(verified),
                claimed == null ? 0 : Math.toIntExact(claimed));
    }
    private long dailyAvailable(long walletId, Policy policy) {
        if (policy.maxEarnPerDay() == 0) return Long.MAX_VALUE;
        var rows = jdbc.queryForList("SELECT earned,held FROM promotion_service.loyalty_daily_earn_usage WHERE wallet_id=? AND local_date=?",
                walletId, LocalDate.now(LOCAL_ZONE));
        long spent = rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("earned")).longValue()
                + ((Number) rows.getFirst().get("held")).longValue();
        return Math.max(0L, policy.maxEarnPerDay() - spent);
    }
    private CounterQuote calculate(Wallet wallet, int requested, BigDecimal merchandise, Policy policy,
            boolean redeemAllowed) {
        long available = wallet == null ? 0 : wallet.total() - wallet.reserved();
        // A phone-only guest may earn points, but membership tier benefits
        // start after the phone has actually been verified and claimed.
        long lifetime = wallet == null || wallet.customerId() == null ? 0 : wallet.lifetime();
        if (requested < 0 || (!redeemAllowed && requested > 0))
            throw new BadRequestException("Loyalty QR scan required to use points");
        try {
            Quote raw = LoyaltyMath.quote(available, lifetime, requested, merchandise, policy);
            if (raw.pointsUsed() != requested) throw new ConflictException("Points or eligible amount changed");
            long earned = raw.pointsToEarn();
            if (policy.maxEarnPerOrder() > 0) earned = Math.min(earned, policy.maxEarnPerOrder());
            if (wallet != null) {
                earned = Math.min(earned, dailyAvailable(wallet.id(), policy));
                if (policy.maxPointBalance() > 0) {
                    Long pending = jdbc.queryForObject("""
                            SELECT COALESCE(sum(earned_points),0)
                            FROM promotion_service.loyalty_orders WHERE state IN ('RESERVED','CONFIRMED')
                              AND (wallet_id=? OR wallet_id IN
                                (SELECT id FROM promotion_service.member_points WHERE merged_into_wallet_id=?))
                            """, Long.class, wallet.id(), wallet.id());
                    earned = Math.min(earned, Math.max(0,
                            policy.maxPointBalance() - wallet.total() -
                            (pending == null ? 0 : pending) + requested));
                }
            }
            return new CounterQuote(policy.version(), redeemAllowed ? raw.availablePoints() : 0, requested,
                    raw.pointsAmount(), earned, raw.tier(), raw.tierDiscount(), redeemAllowed,
                    wallet == null ? null : wallet.id());
        } catch (ArithmeticException | IllegalArgumentException failure) {
            throw new BadRequestException("Invalid loyalty quote");
        }
    }

    @Transactional
    public CounterQuote preview(CounterRequest request) {
        if (request.amount() == null || request.amount().signum() < 0 || request.storeId() <= 0
                || request.staffId() <= 0) throw new BadRequestException("Invalid counter quote");
        if (request.loyaltySubject() == null && request.pointsUsed() == 0)
            return new CounterQuote(loyalty.policy().version(), 0, 0, BigDecimal.ZERO, 0, "MEMBER", BigDecimal.ZERO, false, null);
        var subject = sessions.resolveSubject(request.loyaltySubject());
        boolean isQr = "QR".equals(subject.kind());
        if (isQr) {
            long owner = sessions.checkProof(request.scanProof(), 0, request.staffId(), request.storeId(), request.draftHash(), false);
            if (subject.customerId() == null || owner != subject.customerId()) throw new ConflictException("QR owner mismatch");
        }
        if (!isQr && request.pointsUsed() > 0) throw new BadRequestException("Loyalty QR scan required to use points");
        return calculate(destination(wallet(subject, false, false), false), request.pointsUsed(), request.amount(), loyalty.policy(), isQr);
    }

    @Transactional
    public CounterQuote reserve(CounterReserve request) {
        if (request.orderId() <= 0 || request.staffId() <= 0 || request.storeId() <= 0)
            throw new BadRequestException("Invalid counter order");
        var subject = sessions.resolveSubject(request.loyaltySubject());
        boolean isQr = "QR".equals(subject.kind());
        if (!isQr && request.pointsUsed() > 0) throw new BadRequestException("Loyalty QR scan required to use points");
        if (isQr) {
            long owner = sessions.checkProof(request.scanProof(), request.orderId(), request.staffId(),
                    request.storeId(), request.draftHash(), true);
            if (subject.customerId() == null || owner != subject.customerId()) throw new ConflictException("QR owner mismatch");
        }
        var previous = jdbc.queryForList("SELECT wallet_id,points,state FROM promotion_service.loyalty_orders WHERE order_id=? FOR UPDATE",
                request.orderId());
        // All order mutations lock the order reservation before its wallet.
        // Settlement follows this order too, preventing reserve/settle deadlocks.
        Wallet wallet = destination(wallet(subject, true, true), true);
        if (wallet == null) throw new ConflictException("Loyalty wallet missing");
        if (!previous.isEmpty()) {
            var record = previous.getFirst();
            if (((Number) record.get("wallet_id")).longValue() != wallet.id() ||
                    ((Number) record.get("points")).intValue() != request.pointsUsed()
                    || "CANCELLED".equals(record.get("state"))) throw new ConflictException("Reservation mismatch");
            return calculate(wallet, request.pointsUsed(), request.amount(), loyalty.policy(), isQr);
        }
        Policy policy = loyalty.policy();
        if (policy.version() != request.policyVersion()) throw new ConflictException("Loyalty policy changed");
        // Lock a daily quota row before reserving earning to serialize concurrent counter orders.
        jdbc.update("INSERT INTO promotion_service.loyalty_daily_earn_usage(wallet_id,local_date) VALUES (?,?) ON CONFLICT DO NOTHING",
                wallet.id(), LocalDate.now(LOCAL_ZONE));
        jdbc.queryForList("SELECT wallet_id FROM promotion_service.loyalty_daily_earn_usage WHERE wallet_id=? AND local_date=? FOR UPDATE",
                wallet.id(), LocalDate.now(LOCAL_ZONE));
        CounterQuote quote = calculate(wallet, request.pointsUsed(), request.amount(), policy, isQr);
        if (quote.pointsAmount().compareTo(request.pointsAmount()) != 0
                || quote.tierDiscount().compareTo(request.tierDiscount()) != 0)
            throw new ConflictException("Loyalty quote changed; preview again");
        if (quote.pointsToEarn() != request.pointsToEarn())
            throw new ConflictException("Loyalty earn quota changed; preview again");
        jdbc.update("""
                INSERT INTO promotion_service.loyalty_orders
                  (order_id,customer_id,wallet_id,original_wallet_id,points,points_amount,tier_discount,
                   eligible_amount,earned_points,pending_earn,policy_version,state,store_id,staff_id,authentication_method)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?,?)
                """, request.orderId(), wallet.customerId(), wallet.id(), wallet.id(), quote.pointsUsed(),
                quote.pointsAmount(), quote.tierDiscount(), request.amount(), quote.pointsToEarn(),
                quote.pointsToEarn(), quote.policyVersion(), request.storeId(), request.staffId(), isQr ? "QR_SCAN" : "CONTACT");
        jdbc.update("UPDATE promotion_service.member_points SET reserved_points=reserved_points+?,updated_at=now() WHERE id=?",
                quote.pointsUsed(), wallet.id());
        jdbc.update("UPDATE promotion_service.loyalty_daily_earn_usage SET held=held+? WHERE wallet_id=? AND local_date=?",
                quote.pointsToEarn(), wallet.id(), LocalDate.now(LOCAL_ZONE));
        return quote;
    }

    private void entry(Wallet wallet, long originWalletId, long orderId, long change, long after,
            String type, String action, long version, String eventId, String correlation) {
        jdbc.update("""
                INSERT INTO promotion_service.point_transactions
                (customer_id,wallet_id,origin_wallet_id,order_id,change,balance_after,type,operation_key,reason,
                 policy_version,event_id,correlation_id,source_type,source_ref)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (operation_key) DO NOTHING
                """, wallet.customerId(), wallet.id(), originWalletId, orderId, change, after, type,
                orderId + ":" + action, action, version, eventId, correlation,
                wallet.customerId() == null ? "PHONE" : originWalletId == wallet.id() ? "CUSTOMER" : "PHONE_CLAIMED",
                String.valueOf(originWalletId));
    }
    @Transactional
    public void settle(long orderId, String state, String eventId, String correlation) {
        var rows = jdbc.queryForList("SELECT * FROM promotion_service.loyalty_orders WHERE order_id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        if (!"RESERVED".equals(row.get("state")) && !"CONFIRMED".equals(row.get("state"))) return;
        if (!"COMPLETED".equals(state) && !"CANCELLED".equals(state) && !"MISSING".equals(state)) return;
        Wallet wallet = walletById(((Number) row.get("wallet_id")).longValue(), true);
        long spent = ((Number) row.get("points")).longValue();
        long earned = ((Number) row.get("earned_points")).longValue();
        long originWalletId = row.get("original_wallet_id") == null
                ? wallet.id() : ((Number) row.get("original_wallet_id")).longValue();
        long version = ((Number) row.get("policy_version")).longValue();
        if ("COMPLETED".equals(state)) {
            long afterSpent = Math.subtractExact(wallet.total(), spent);
            long grossEarn = Math.addExact(afterSpent, earned);
            long withheldForRefund = Math.min(earned, wallet.clawback());
            long afterEarn = Math.subtractExact(grossEarn, withheldForRefund);
            if (spent > 0) entry(wallet, originWalletId, orderId, -spent, afterSpent, "REDEEM", "REDEEMED", version, eventId, correlation);
            if (earned > 0) entry(wallet, originWalletId, orderId, earned, grossEarn, "EARN", "EARNED", version, eventId, correlation);
            if (withheldForRefund > 0) entry(wallet, originWalletId, orderId, -withheldForRefund,
                    afterEarn, "ADJUST", "REFUND_CLAWBACK_SETTLED", version, eventId, correlation);
            long lifetime = Math.addExact(wallet.lifetime(), earned);
            jdbc.update("""
                    UPDATE promotion_service.member_points
                    SET total_points=?,reserved_points=reserved_points-?,
                        lifetime_points=?,pending_clawback_points=pending_clawback_points-?,
                        tier=?,updated_at=now()
                    WHERE id=?
                    """, afterEarn, spent, lifetime, withheldForRefund,
                    LoyaltyMath.tier(lifetime, loyalty.policy()), wallet.id());
            jdbc.update("UPDATE promotion_service.loyalty_daily_earn_usage SET held=held-?,earned=earned+? WHERE wallet_id=? AND local_date=?",
                    earned, earned, wallet.id(), localDateOf(row));
        } else {
            jdbc.update("UPDATE promotion_service.member_points SET reserved_points=reserved_points-?,updated_at=now() WHERE id=?",
                    spent, wallet.id());
            jdbc.update("UPDATE promotion_service.loyalty_daily_earn_usage SET held=held-? WHERE wallet_id=? AND local_date=?",
                    earned, wallet.id(), localDateOf(row));
        }
        jdbc.update("UPDATE promotion_service.loyalty_orders SET state=?,updated_at=now() WHERE order_id=?",
                "COMPLETED".equals(state) ? "COMPLETED" : "CANCELLED", orderId);
    }
    private LocalDate localDateOf(Map<String,Object> row) {
        return ((java.sql.Timestamp) row.get("created_at")).toInstant().atZone(LOCAL_ZONE).toLocalDate();
    }

    @Transactional
    public void verifiedPhone(long customerId, String key, int keyVersion, long bindingVersion, String eventId) {
        if (customerId <= 0 || keyVersion != 1 || bindingVersion < 1 || eventId == null
                || !key.matches("[a-f0-9]{64}")) throw new BadRequestException("Invalid phone verification event");
        phoneClaimLock(key);
        jdbc.update("""
                INSERT INTO promotion_service.loyalty_verified_phones
                    (phone_key,customer_id,binding_version,verification_event_id)
                VALUES (?,?,?,?) ON CONFLICT(phone_key) DO NOTHING
                """, key, customerId, bindingVersion, eventId);
        var mapping = jdbc.queryForList("SELECT customer_id FROM promotion_service.loyalty_verified_phones WHERE phone_key=? FOR UPDATE", key);
        if (((Number) mapping.getFirst().get("customer_id")).longValue() != customerId)
            throw new ConflictException("Phone already claimed by another account");
        Wallet sourceSnapshot = guestWallet(key, false, false);
        if (sourceSnapshot == null || sourceSnapshot.mergedInto() != null) return;
        Wallet targetSnapshot = customerWallet(customerId, true, false);
        // Lock both member rows in monotonically increasing primary key order,
        // independent of which wallet was created first.
        var locked = jdbc.query("""
                SELECT * FROM promotion_service.member_points WHERE id IN (?,?)
                ORDER BY id FOR UPDATE
                """, (row, index) -> walletRow(row), sourceSnapshot.id(), targetSnapshot.id());
        Wallet source = locked.stream().filter(wallet -> wallet.id() == sourceSnapshot.id()).findFirst()
                .orElseThrow(() -> new ConflictException("Phone wallet disappeared"));
        Wallet target = locked.stream().filter(wallet -> wallet.id() == targetSnapshot.id()).findFirst()
                .orElseThrow(() -> new ConflictException("Customer wallet disappeared"));
        if (source.mergedInto() != null) return;
        if (source.id() == target.id()) return;
        int inserted = jdbc.update("""
                INSERT INTO promotion_service.loyalty_phone_claims
                  (source_wallet_id,target_wallet_id,verified_customer_id,verification_event_id,amount)
                VALUES (?,?,?,?,?) ON CONFLICT (source_wallet_id) DO NOTHING
                """, source.id(), target.id(), customerId, eventId, source.total());
        if (inserted == 0) throw new ConflictException("Guest phone points were already claimed");
        long combined = Math.addExact(source.total(), target.total());
        long held = Math.addExact(source.reserved(), target.reserved());
        long lifetime = Math.addExact(source.lifetime(), target.lifetime());
        long debt = Math.addExact(source.clawback(), target.clawback());
        jdbc.update("""
                UPDATE promotion_service.member_points
                SET total_points=?,reserved_points=?,lifetime_points=?,pending_clawback_points=?,
                    tier=?,updated_at=now() WHERE id=?
                """, combined, held, lifetime, debt,
                LoyaltyMath.tier(lifetime, loyalty.policy()), target.id());
        jdbc.update("""
                UPDATE promotion_service.member_points SET
                    total_points=0,reserved_points=0,lifetime_points=0,pending_clawback_points=0,
                    wallet_state='MERGED',merged_into_wallet_id=?,updated_at=now()
                WHERE id=?
                """,
                target.id(), source.id());
        jdbc.update("""
                INSERT INTO promotion_service.loyalty_daily_earn_usage(wallet_id,local_date,earned,held)
                SELECT ?,local_date,earned,held FROM promotion_service.loyalty_daily_earn_usage WHERE wallet_id=?
                ON CONFLICT(wallet_id,local_date) DO UPDATE
                    SET earned=promotion_service.loyalty_daily_earn_usage.earned+EXCLUDED.earned,
                        held=promotion_service.loyalty_daily_earn_usage.held+EXCLUDED.held
                """, target.id(), source.id());
        jdbc.update("DELETE FROM promotion_service.loyalty_daily_earn_usage WHERE wallet_id=?", source.id());
        jdbc.update("INSERT INTO promotion_service.point_transactions(customer_id,wallet_id,origin_wallet_id,change,balance_after,type,operation_key,reason) VALUES (NULL,?,?,?,0,'ADJUST',?,'TRANSFER_OUT')",
                source.id(), source.id(), -source.total(), "claim-out:" + source.id());
        jdbc.update("INSERT INTO promotion_service.point_transactions(customer_id,wallet_id,origin_wallet_id,change,balance_after,type,operation_key,reason) VALUES (?,?,?,?,?,'ADJUST',?,'TRANSFER_IN')",
                customerId, target.id(), source.id(), source.total(), combined, "claim-in:" + source.id());
    }
}
