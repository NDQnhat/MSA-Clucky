package com.clucky.catalogstoreservice.controller;

import com.clucky.catalogstoreservice.config.AccessGuard;
import com.clucky.catalogstoreservice.config.LocaleResolverHelper;
import com.clucky.catalogstoreservice.dto.InventoryDto;
import com.clucky.catalogstoreservice.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/inventory")
public class StoreInventoryAlertController {

    private final InventoryService inventoryService;
    private final AccessGuard accessGuard;

    public StoreInventoryAlertController(InventoryService inventoryService, AccessGuard accessGuard) {
        this.inventoryService = inventoryService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN', 'STORE_MANAGER', 'STAFF')")
    public ResponseEntity<InventoryDto.InventoryAlertSummaryResponse> getAlerts(
            @RequestParam(required = false) Long storeId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        Long effectiveStoreId = accessGuard.effectiveStore(storeId);
        return ResponseEntity.ok(inventoryService.getInventoryAlerts(
                effectiveStoreId, LocaleResolverHelper.resolve(lang, acceptLanguage)));
    }
}
