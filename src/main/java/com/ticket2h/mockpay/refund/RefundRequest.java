package com.ticket2h.mockpay.refund;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body của {@code POST /api/v1/refunds}. */
public record RefundRequest(
        @NotBlank String gatewayTransactionId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 128) String idempotencyKey) {
}
