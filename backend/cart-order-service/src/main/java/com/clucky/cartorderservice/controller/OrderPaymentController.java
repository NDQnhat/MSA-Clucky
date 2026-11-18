package com.clucky.cartorderservice.controller;

import com.clucky.cartorderservice.service.OrderPaymentService;
import com.clucky.cartorderservice.dto.CounterPaymentClaimRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderPaymentController {
    private final OrderPaymentService service;
    public OrderPaymentController(OrderPaymentService service) { this.service = service; }

    @PostMapping("/api/orders/{id}/payment-session")
    public OrderPaymentService.Session open(@PathVariable @Positive long id) { return service.open(id); }

    @GetMapping("/api/orders/{id}/payment-session")
    public OrderPaymentService.Session preview(@PathVariable @Positive long id) { return service.preview(id); }

    @PostMapping("/api/orders/{id}/counter-payment-session")
    public OrderPaymentService.Session openCounter(@PathVariable @Positive long id) { return service.openCounter(id); }

    @PostMapping("/api/orders/{id}/counter-payment-claim")
    public void claimCounterPayment(@PathVariable @Positive long id,
            @Valid @RequestBody CounterPaymentClaimRequest request) {
        service.claimCounterPayment(id, request);
    }

    @GetMapping("/api/orders/{id}/payment-context")
    public OrderPaymentService.Context context(@PathVariable @Positive long id) { return service.context(id); }

    @PutMapping("/api/orders/{id}/payment-status")
    public org.springframework.http.ResponseEntity<Void> updatePaymentStatus(
            @PathVariable @Positive long id,
            @RequestBody java.util.Map<String, String> body) {
        service.updatePaymentStatus(id, body != null ? body.get("paymentStatus") : null);
        return org.springframework.http.ResponseEntity.ok().build();
    }
}
