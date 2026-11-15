package com.clucky.paymentservice.controller;

import com.clucky.paymentservice.dto.CounterCollectionRequest;
import com.clucky.paymentservice.dto.CounterPaymentReceiptResponse;
import com.clucky.paymentservice.dto.ShiftSummaryResponse;
import com.clucky.paymentservice.dto.VoidReceiptRequest;
import com.clucky.paymentservice.service.CounterPaymentService;
import com.clucky.paymentservice.service.PaymentFlow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentFlow service;
    private final CounterPaymentService counterService;

    public PaymentController(PaymentFlow service, CounterPaymentService counterService) {
        this.service = service;
        this.counterService = counterService;
    }

    @PostMapping("/{orderId}/bank-transfer")
    public PaymentFlow.Summary initialize(@PathVariable @Positive long orderId) { return service.initialize(orderId); }

    /** Actual SePay collection at staff POS. The cash leg is fixed at initiation. */
    public record CounterBankTransferRequest(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 0) BigDecimal cashAmount) {}

    @PostMapping("/{orderId}/counter-bank-transfer")
    @PreAuthorize("hasAnyRole('STAFF','STORE_MANAGER','ADMIN')")
    public PaymentFlow.Summary initializeCounter(@PathVariable @Positive long orderId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody CounterBankTransferRequest request) {
        return service.initializeCounter(orderId, request.cashAmount(), requestKey);
    }

    @GetMapping("/{orderId}")
    public PaymentFlow.Summary read(@PathVariable @Positive long orderId) { return service.get(orderId); }

    @PostMapping("/counter-collection")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('STAFF', 'STORE_MANAGER', 'ADMIN')")
    public CounterPaymentReceiptResponse collect(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CounterCollectionRequest request
    ) {
        return counterService.collect(request, idempotencyKey);
    }

    @PostMapping("/receipts/{id}/void")
    @PreAuthorize("hasAnyRole('STAFF', 'STORE_MANAGER', 'ADMIN')")
    public CounterPaymentReceiptResponse voidReceipt(
            @PathVariable("id") @Positive Long id,
            @RequestBody(required = false) VoidReceiptRequest request
    ) {
        return counterService.voidReceipt(id, request);
    }

    @PostMapping("/receipts/{id}/print")
    @PreAuthorize("hasAnyRole('STAFF', 'STORE_MANAGER', 'ADMIN')")
    public CounterPaymentReceiptResponse printReceipt(
            @PathVariable("id") @Positive Long id
    ) {
        return counterService.printReceipt(id);
    }

    @GetMapping("/shift-summary")
    @PreAuthorize("hasAnyRole('STAFF', 'STORE_MANAGER', 'ADMIN')")
    public ShiftSummaryResponse getShiftSummary(
            @RequestParam(value = "storeId", required = false) Long storeId,
            @RequestParam(value = "date", required = false) String date
    ) {
        return counterService.getShiftSummary(storeId, date);
    }
}

