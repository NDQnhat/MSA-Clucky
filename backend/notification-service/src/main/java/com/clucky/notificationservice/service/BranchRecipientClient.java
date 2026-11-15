package com.clucky.notificationservice.service;

import com.clucky.notificationservice.config.JwtSettings;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Resolve current branch account IDs from Identity; do not infer ownership from event payloads. */
@Component
public class BranchRecipientClient {
    private final RestClient client;
    private final JwtSettings settings;

    public BranchRecipientClient(@LoadBalanced RestClient.Builder builder, JwtSettings settings) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.clone().baseUrl("http://identity-service").requestFactory(factory).build();
        this.settings = settings;
    }

    public record Account(long id, Long storeId, String role, String login, String status,
                          boolean activated, boolean current) {}
    public record Pair(long storeId, String readiness, String recoveryEmail, boolean verified,
                       long version, List<Account> accounts) {}

    public Long currentActiveUserId(long storeId, String role) {
        if (storeId <= 0 || !Set.of("STAFF", "STORE_MANAGER").contains(role)) {
            throw new IllegalArgumentException("Invalid branch recipient scope");
        }
        try {
            byte[] secret = settings.secret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) throw new IllegalStateException("Missing branch lookup secret");
            long timestamp = Instant.now().getEpochSecond();
            String message = "branch-provision-v1:" + storeId + ":" + timestamp + ":";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            Pair pair = client.get().uri("/api/auth/internal/branch-accounts/{storeId}", storeId)
                    .header("X-Branch-Time", Long.toString(timestamp))
                    .header("X-Branch-Signature", signature).retrieve().body(Pair.class);
            if (pair == null || pair.storeId() != storeId || pair.accounts() == null || pair.accounts().size() != 2) {
                throw new IllegalStateException("Invalid branch account lookup");
            }
            Account target = null;
            for (Account account : pair.accounts()) {
                if (account.id() <= 0 || !Objects.equals(account.storeId(), storeId)
                        || !account.current() || !Set.of("STAFF", "STORE_MANAGER").contains(account.role())) {
                    throw new IllegalStateException("Invalid branch account membership");
                }
                if (role.equals(account.role())) {
                    if (target != null) throw new IllegalStateException("Duplicate branch role");
                    target = account;
                }
            }
            if (target == null) throw new IllegalStateException("Missing branch role");
            return target.activated() && "ACTIVE".equals(target.status()) ? target.id() : null;
        } catch (Exception failure) {
            throw new IllegalStateException("Branch recipient lookup unavailable", failure);
        }
    }
}
