package com.clucky.identityservice.service;

import com.clucky.identityservice.config.RedisConfig;
import com.clucky.identityservice.logging.SafeLog;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class SessionRevocationService {
    private static final Logger LOG = LoggerFactory.getLogger(SessionRevocationService.class);

    private final StringRedisTemplate redisTemplate;

    public SessionRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revokeSession(String sid, Duration ttl) {
        if (sid == null || sid.isBlank() || redisTemplate == null) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            String key = RedisConfig.key("auth:revoked:sid", sid);
            redisTemplate.opsForValue().set(key, "revoked", ttl);
        } catch (Exception e) {
            LOG.warn("Failed to write revoked session to Redis: {}", SafeLog.trace(e));
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public boolean isSessionRevoked(String sid) {
        if (sid == null || sid.isBlank() || redisTemplate == null) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            String key = RedisConfig.key("auth:revoked:sid", sid);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            LOG.warn("Failed to check revoked session in Redis: {}", SafeLog.trace(e));
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public void revokeAllUserSessions(Long userId, Instant revokedAt, Duration ttl) {
        if (userId == null || userId <= 0 || redisTemplate == null) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            String key = RedisConfig.key("auth:revoked:user", String.valueOf(userId));
            String preciseKey = RedisConfig.key("auth:revoked:user-ns", String.valueOf(userId));
            Instant cutoff = revokedAt != null ? revokedAt : Instant.now();
            long timestamp = Math.addExact(Math.multiplyExact(cutoff.getEpochSecond(), 1_000_000_000L), cutoff.getNano());
            // Other business services still consume this millisecond watermark.
            redisTemplate.opsForValue().set(key, String.valueOf(cutoff.toEpochMilli()), ttl);
            redisTemplate.opsForValue().set(preciseKey, String.valueOf(timestamp), ttl);
        } catch (Exception e) {
            LOG.warn("Failed to write revoked user to Redis: {}", SafeLog.trace(e));
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public boolean isUserRevoked(Long userId, Instant tokenIssuedAt) {
        if (userId == null || userId <= 0 || tokenIssuedAt == null || redisTemplate == null) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            String key = RedisConfig.key("auth:revoked:user", String.valueOf(userId));
            String preciseKey = RedisConfig.key("auth:revoked:user-ns", String.valueOf(userId));
            String precise = redisTemplate.opsForValue().get(preciseKey);
            if (precise != null && !precise.isBlank()) {
                long issuedNanos = Math.addExact(Math.multiplyExact(tokenIssuedAt.getEpochSecond(), 1_000_000_000L), tokenIssuedAt.getNano());
                return issuedNanos <= Long.parseLong(precise);
            }
            String value = redisTemplate.opsForValue().get(key);
            if (value != null && !value.isBlank()) {
                // Conservative fallback for revocations created by older deployments.
                return tokenIssuedAt.toEpochMilli() <= Long.parseLong(value);
            }
            return false;
        } catch (Exception e) {
            LOG.warn("Failed to check revoked user in Redis: {}", SafeLog.trace(e));
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}

