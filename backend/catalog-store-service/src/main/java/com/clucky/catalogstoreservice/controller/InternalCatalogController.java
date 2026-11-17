package com.clucky.catalogstoreservice.controller;

import com.clucky.catalogstoreservice.dto.ValidationDto;
import com.clucky.catalogstoreservice.service.OrderStockService;
import com.clucky.catalogstoreservice.service.ValidationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/internal")
@PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
public class InternalCatalogController {

    private final ValidationService validationService;
    private final OrderStockService orderStockService;

    public InternalCatalogController(ValidationService validationService, OrderStockService orderStockService) {
        this.validationService = validationService;
        this.orderStockService = orderStockService;
    }

    @PostMapping("/validate-items")
    public ResponseEntity<ValidationDto.Response> validateItems(@RequestBody ValidationDto.Request request) {
        return ResponseEntity.ok(validationService.validateOrderItems(request));
    }

    @PostMapping("/reserve-stock")
    public ResponseEntity<Void> reserveStock(
            @RequestBody ValidationDto.ReserveStockRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        orderStockService.reserveOrderStock(request.orderId(), request.storeId(), request.stockQuantities(), request.expectedVersions(), correlationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/release-stock")
    public ResponseEntity<Void> releaseStock(
            @RequestBody ValidationDto.ReleaseStockRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        orderStockService.releaseOrderStock(request.orderId(), request.storeId(), correlationId);
        return ResponseEntity.ok().build();
    }
}
