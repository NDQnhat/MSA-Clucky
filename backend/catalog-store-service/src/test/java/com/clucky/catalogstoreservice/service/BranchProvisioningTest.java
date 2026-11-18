package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.config.JwtSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchProvisioningTest {
    @Test
    void missingUnprovisionedAccountsAreRetriedAfterCooldownAndLegacyPairsStillGetProbed() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");

        assertTrue(BranchProvisioning.shouldSynchronize("UNPROVISIONED", false, null, now));
        assertFalse(BranchProvisioning.shouldSynchronize("UNPROVISIONED", false, now.minusSeconds(60), now));
        assertTrue(BranchProvisioning.shouldSynchronize("UNPROVISIONED", false, now.minusSeconds(16 * 60L), now));
        assertTrue(BranchProvisioning.shouldSynchronize("UNPROVISIONED", true, now, now));
        assertFalse(BranchProvisioning.shouldSynchronize("PENDING", false, now.minusSeconds(60), now));
        assertTrue(BranchProvisioning.shouldSynchronize("PENDING", false, null, now));
        assertTrue(BranchProvisioning.shouldSynchronize("PROVISIONED", false, now, now));
        assertTrue(BranchProvisioning.shouldSynchronize("READY", false, now, now));
    }

    @Test
    void unchangedProvisionedStoreDoesNotEvictItsMenuWhenIdentityIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StoreService stores = mock(StoreService.class);
        CatalogCacheService cache = mock(CatalogCacheService.class);
        DiscoveryClient discovery = mock(DiscoveryClient.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        CatalogEvents events = mock(CatalogEvents.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        Map<String, Object> store = new HashMap<>();
        store.put("account_readiness", "PROVISIONED");
        store.put("notification_manager_id", null);
        when(jdbc.queryForList(startsWith("SELECT account_readiness"), any(Object[].class))).thenReturn(List.of(store));
        when(jdbc.queryForList(startsWith("SELECT * FROM catalog_service.account_provisioning"), any(Object[].class)))
                .thenReturn(List.of());
        when(discovery.getInstances("identity-service")).thenReturn(List.of());

        var service = new BranchProvisioning(jdbc, stores, cache, discovery, new ObjectMapper(),
                new JwtSettings("test-secret", "issuer", "audience"), transactions, events);
        service.synchronize(42L);

        verify(cache, never()).evictStoreMenuCache(42L);
        verify(jdbc, never()).update(contains("version=version+?"), any(), any(), any());
    }

    @Test
    void readinessTransitionEvictsTheStoreMenuExactlyOnce() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CatalogCacheService cache = mock(CatalogCacheService.class);
        DiscoveryClient discovery = mock(DiscoveryClient.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        Map<String, Object> store = new HashMap<>();
        store.put("account_readiness", "READY");
        store.put("notification_manager_id", null);
        when(jdbc.queryForList(startsWith("SELECT account_readiness"), any(Object[].class))).thenReturn(List.of(store));
        when(jdbc.queryForList(startsWith("SELECT * FROM catalog_service.account_provisioning"), any(Object[].class)))
                .thenReturn(List.of());
        when(discovery.getInstances("identity-service")).thenReturn(List.of());

        var service = new BranchProvisioning(jdbc, mock(StoreService.class), cache, discovery, new ObjectMapper(),
                new JwtSettings("test-secret", "issuer", "audience"), transactions, mock(CatalogEvents.class));
        service.synchronize(42L);

        verify(cache, times(1)).evictStoreMenuCache(42L);
        verify(jdbc).update(contains("version=version+?"), eq("PROVISIONED"), eq(1), eq(42L));
    }
}
