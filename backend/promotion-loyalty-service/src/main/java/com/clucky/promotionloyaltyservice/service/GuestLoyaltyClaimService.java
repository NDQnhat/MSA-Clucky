package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.config.CurrentActor;
import com.clucky.promotionloyaltyservice.exception.BadRequestException;
import com.clucky.promotionloyaltyservice.exception.ConflictException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Optional, explicitly consented transfer of PHONE/EMAIL guest earn-only points.
 * Contact ownership is looked up afresh from Identity after its OTP verification.
 * Browser requests cannot provide raw contact or an arbitrary customer id.
 */
@Service
public class GuestLoyaltyClaimService {
    private final JdbcTemplate jdbc;
    private final LoyaltyService loyalty;
    private final RestClient identity;
    private final String identityUrl;
    private final InternalServiceCredential credential;

    public record VerifiedContacts(String phoneKey, String emailKey, long phoneBindingVersion) {}
    public record Preview(long phonePoints, long emailPoints, long currentPoints,
                          long totalPoints, boolean pending) {}
    public record Result(long phonePoints, long emailPoints, long pointsTransferred,
                         long currentPoints, long totalPoints, boolean pending) {}
    private record Guest(String kind, String key, long walletId, long points) {}

    public GuestLoyaltyClaimService(JdbcTemplate jdbc, LoyaltyService loyalty,
            @LoadBalanced RestClient.Builder builder, InternalServiceCredential credential,
            @Value("${clucky.identity.service-url:http://identity-service}") String identityUrl) {
        this.jdbc = jdbc; this.loyalty = loyalty;
        this.identity = builder.build(); this.credential = credential; this.identityUrl = identityUrl;
    }

    @Transactional(readOnly = true)
    public Preview preview() {
        long customerId = CurrentActor.require().userId();
        VerifiedContacts contacts = verified(customerId);
        long current = walletPoints(customerId);
        long phone = guestPoints("PHONE", contacts.phoneKey());
        long email = guestPoints("EMAIL", contacts.emailKey());
        return new Preview(phone, email, current, Math.addExact(Math.addExact(current, phone), email),
                phone > 0 || email > 0);
    }

    @Transactional
    public Result confirm(boolean confirmed) {
        if (!confirmed) throw new BadRequestException("Explicit loyalty transfer confirmation is required");
        long customerId = CurrentActor.require().userId();
        VerifiedContacts contacts = verified(customerId);
        // Lock the contact keys in a single stable order. POS reserves/earns
        // acquire the same advisory locks before accessing a guest wallet.
        List<String> keys = new ArrayList<>();
        if (contacts.phoneKey() != null) keys.add("loyalty:phone:" + contacts.phoneKey());
        if (contacts.emailKey() != null) keys.add("loyalty:email:" + contacts.emailKey());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys)
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, key);

        if (contacts.phoneKey() != null) {
            jdbc.update("""
                    INSERT INTO promotion_service.loyalty_verified_phones
                      (phone_key,customer_id,binding_version,verification_event_id)
                    VALUES (?,?,?,?) ON CONFLICT(phone_key) DO NOTHING
                    """, contacts.phoneKey(), customerId, contacts.phoneBindingVersion(),
                    claimVerificationId("PHONE", customerId, contacts.phoneKey()));
            ensureOwner("loyalty_verified_phones", "phone_key", contacts.phoneKey(), customerId);
        }
        if (contacts.emailKey() != null) {
            jdbc.update("""
                    INSERT INTO promotion_service.loyalty_verified_emails(email_key,customer_id)
                    VALUES (?,?) ON CONFLICT(email_key) DO NOTHING
                    """, contacts.emailKey(), customerId);
            ensureOwner("loyalty_verified_emails", "email_key", contacts.emailKey(), customerId);
        }
        List<Guest> sources = new ArrayList<>();
        Guest phone = guest("PHONE", contacts.phoneKey());
        Guest email = guest("EMAIL", contacts.emailKey());
        if (phone != null) sources.add(phone);
        if (email != null) sources.add(email);
        // No guest wallet means the command is a harmless idempotent replay.
        if (sources.isEmpty()) {
            long current = walletPoints(customerId);
            return new Result(0, 0, 0, current, current, false);
        }

        jdbc.update("""
                INSERT INTO promotion_service.member_points(customer_id)
                VALUES (?) ON CONFLICT(customer_id) DO NOTHING
                """, customerId);
        Long targetId = jdbc.queryForObject(
                "SELECT id FROM promotion_service.member_points WHERE customer_id=?", Long.class, customerId);
        if (targetId == null) throw new ConflictException("Customer loyalty wallet unavailable");
        List<Long> ids = new ArrayList<>();
        ids.add(targetId);
        sources.forEach(source -> ids.add(source.walletId()));
        ids.sort(Comparator.naturalOrder());
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Map<String,Object>> locked = jdbc.queryForList(
                "SELECT * FROM promotion_service.member_points WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                ids.toArray());
        Map<String,Object> target = locked.stream().filter(row -> number(row,"id") == targetId)
                .findFirst().orElseThrow(() -> new ConflictException("Customer wallet disappeared"));
        if (!"ACTIVE".equals(target.get("wallet_state")) || target.get("merged_into_wallet_id") != null)
            throw new ConflictException("Customer loyalty wallet inactive");

        long targetTotal = number(target, "total_points");
        long targetLifetime = number(target, "lifetime_points");
        long targetDebt = number(target, "pending_clawback_points");
        long phoneTransferred = 0;
        long emailTransferred = 0;
        for (Guest guest : sources) {
            Map<String,Object> source = locked.stream()
                    .filter(row -> number(row,"id") == guest.walletId())
                    .findFirst().orElseThrow(() -> new ConflictException("Guest wallet disappeared"));
            if (!"ACTIVE".equals(source.get("wallet_state")) || source.get("merged_into_wallet_id") != null)
                continue; // Already claimed by an earlier idempotent confirmation.
            if (number(source,"reserved_points") != 0 || unsettled(source))
                throw new ConflictException("Guest loyalty order is awaiting settlement");
            long points = number(source, "total_points");
            if (points < 0) throw new ConflictException("Guest loyalty wallet invalid");
            targetTotal = Math.addExact(targetTotal, points);
            targetLifetime = Math.addExact(targetLifetime, number(source,"lifetime_points"));
            targetDebt = Math.addExact(targetDebt, number(source,"pending_clawback_points"));
            if ("PHONE".equals(guest.kind())) phoneTransferred += points;
            else emailTransferred += points;
        }
        long cap = loyalty.policy().maxPointBalance();
        if (cap > 0 && targetTotal > cap)
            throw new ConflictException("Combined loyalty points exceed current balance limit");

        long runningBalance = number(target,"total_points");
        for (Guest guest : sources) {
            Map<String,Object> source = locked.stream()
                    .filter(row -> number(row,"id") == guest.walletId())
                    .findFirst().orElseThrow();
            if (!"ACTIVE".equals(source.get("wallet_state")) || source.get("merged_into_wallet_id") != null)
                continue;
            long points = number(source,"total_points");
            runningBalance = Math.addExact(runningBalance, points);
            String kind = guest.kind();
            int applied = "PHONE".equals(kind) ? jdbc.update("""
                    INSERT INTO promotion_service.loyalty_phone_claims
                      (source_wallet_id,target_wallet_id,verified_customer_id,verification_event_id,amount)
                    VALUES (?,?,?,?,?) ON CONFLICT (source_wallet_id) DO NOTHING
                    """, guest.walletId(), targetId, customerId,
                    claimVerificationId(kind, customerId, guest.key()), points)
                    : jdbc.update("""
                    INSERT INTO promotion_service.loyalty_email_claims
                      (source_wallet_id,target_wallet_id,verified_customer_id,amount)
                    VALUES (?,?,?,?) ON CONFLICT (source_wallet_id) DO NOTHING
                    """, guest.walletId(), targetId, customerId, points);
            if (applied != 1) throw new ConflictException("Guest points have already been claimed");

            jdbc.update("""
                    UPDATE promotion_service.member_points SET total_points=0,reserved_points=0,
                    lifetime_points=0,pending_clawback_points=0,wallet_state='MERGED',
                    merged_into_wallet_id=?,updated_at=now() WHERE id=?
                    """, targetId, guest.walletId());
            jdbc.update("""
                    INSERT INTO promotion_service.loyalty_daily_earn_usage(wallet_id,local_date,earned,held)
                    SELECT ?,local_date,earned,held
                    FROM promotion_service.loyalty_daily_earn_usage WHERE wallet_id=?
                    ON CONFLICT(wallet_id,local_date) DO UPDATE
                        SET earned=promotion_service.loyalty_daily_earn_usage.earned+EXCLUDED.earned,
                            held=promotion_service.loyalty_daily_earn_usage.held+EXCLUDED.held
                    """, targetId, guest.walletId());
            jdbc.update("DELETE FROM promotion_service.loyalty_daily_earn_usage WHERE wallet_id=?", guest.walletId());
            jdbc.update("""
                    INSERT INTO promotion_service.point_transactions
                    (customer_id,wallet_id,origin_wallet_id,change,balance_after,type,operation_key,reason)
                    VALUES (NULL,?,?,?,0,'ADJUST',?,?)
                    """, guest.walletId(), guest.walletId(), -points, "claim-out:" + guest.walletId(),
                    kind + "_TRANSFER_OUT");
            jdbc.update("""
                    INSERT INTO promotion_service.point_transactions
                    (customer_id,wallet_id,origin_wallet_id,change,balance_after,type,operation_key,reason)
                    VALUES (?,?,?,?,?,'ADJUST',?,?)
                    """, customerId, targetId, guest.walletId(), points, runningBalance,
                    "claim-in:" + guest.walletId(), kind + "_TRANSFER_IN");
        }
        jdbc.update("""
                UPDATE promotion_service.member_points
                SET total_points=?,lifetime_points=?,pending_clawback_points=?,tier=?,updated_at=now()
                WHERE id=?
                """, targetTotal, targetLifetime, targetDebt,
                LoyaltyMath.tier(targetLifetime, loyalty.policy()), targetId);
        long transferred = Math.addExact(phoneTransferred, emailTransferred);
        return new Result(phoneTransferred, emailTransferred, transferred, targetTotal, targetTotal, false);
    }

    private boolean unsettled(Map<String,Object> guest) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM promotion_service.loyalty_orders
                WHERE wallet_id=? AND state IN ('RESERVED','CONFIRMED')
                """, Long.class, number(guest,"id"));
        Long held = jdbc.queryForObject("""
                SELECT coalesce(sum(held),0) FROM promotion_service.loyalty_daily_earn_usage
                WHERE wallet_id=?
                """, Long.class, number(guest,"id"));
        return (count != null && count > 0) || (held != null && held > 0);
    }

    private void ensureOwner(String table, String column, String key, long customerId) {
        // Table/column are fixed literal values in this service, not request data.
        Long owner = jdbc.queryForObject("SELECT customer_id FROM promotion_service." + table
                        + " WHERE " + column + "=? FOR UPDATE", Long.class, key);
        if (owner == null || owner != customerId)
            throw new ConflictException("Verified contact belongs to another account");
    }

    private long walletPoints(long customerId) {
        Long value = jdbc.query("""
                SELECT total_points FROM promotion_service.member_points WHERE customer_id=?
                """, rs -> rs.next() ? rs.getLong(1) : null, customerId);
        return value == null ? 0 : value;
    }

    private long guestPoints(String kind, String key) {
        Guest guest = guest(kind, key);
        return guest == null ? 0 : guest.points();
    }

    private Guest guest(String kind, String key) {
        if (key == null) return null;
        if (!key.matches("[a-f0-9]{64}")) throw new ConflictException("Invalid verified contact proof");
        String column = "PHONE".equals(kind) ? "phone_key" : "email_key";
        var rows = jdbc.queryForList("SELECT id,total_points,wallet_state,merged_into_wallet_id"
                + " FROM promotion_service.member_points WHERE owner_type=? AND " + column + "=?", kind, key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!"ACTIVE".equals(row.get("wallet_state")) || row.get("merged_into_wallet_id") != null)
            return null;
        return new Guest(kind, key, number(row,"id"), number(row,"total_points"));
    }

    private VerifiedContacts verified(long customerId) {
        try {
            VerifiedContacts proof = identity.post()
                    .uri(identityUrl + "/internal/identity/loyalty-contacts/resolve")
                    .headers(headers -> headers.setBearerAuth(credential.token()))
                    .body(Map.of("customerId", customerId))
                    .retrieve().body(VerifiedContacts.class);
            if (proof == null || (proof.phoneKey() == null && proof.emailKey() == null))
                throw new ConflictException("No verified customer contact");
            return proof;
        } catch (RestClientException failure) {
            throw new ConflictException("Verified contact lookup is temporarily unavailable");
        }
    }

    private static String claimVerificationId(String kind, long customerId, String key) {
        return UUID.nameUUIDFromBytes(("guest-claim:" + kind + ":" + customerId + ":" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static long number(Map<String,Object> row, String key) {
        return ((Number)row.get(key)).longValue();
    }
}
