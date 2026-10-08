package dev.seatsync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;
import java.security.Principal;

record PaymentRequest(@NotNull UUID reservationId, @NotNull PaymentStatus outcome) {}
record PaymentDto(UUID id, UUID reservationId, PaymentStatus status, int amountCents, Instant createdAt) {}

@RestController @RequestMapping("/api/v1/payments")
class PaymentController {
    private final PaymentService payments;
    PaymentController(PaymentService payments) { this.payments=payments; }
    @PostMapping ResponseEntity<PaymentDto> pay(
            @RequestHeader("Idempotency-Key") @Size(min=8,max=128) String key,
            @Valid @RequestBody PaymentRequest request,Principal user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payments.pay(key,request,user.getName()));
    }
    @GetMapping("/{id}") PaymentDto get(@PathVariable UUID id,Principal user) { return payments.get(id,user.getName()); }
}
