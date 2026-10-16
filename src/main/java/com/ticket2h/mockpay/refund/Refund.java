package com.ticket2h.mockpay.refund;

import java.math.BigDecimal;
import java.time.Instant;

/** Một lần hoàn tiền đã thực hiện, lưu theo {@code idempotencyKey}. */
public record Refund(
        String refundId,
        String idempotencyKey,
        String gatewayTransactionId,
        String gatewayPaymentId,
        BigDecimal amount,
        String status,
        Instant createdAt) {

    public static final String SUCCEEDED = "SUCCEEDED";
}
