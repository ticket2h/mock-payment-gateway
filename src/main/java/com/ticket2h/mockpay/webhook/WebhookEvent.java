package com.ticket2h.mockpay.webhook;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Body JSON của webhook gửi tới {@code notifyUrl}.
 *
 * @param type                 {@code payment.succeeded} hoặc {@code payment.failed}
 * @param gatewayTransactionId {@code null} với {@code payment.failed}
 * @param amount               đúng giá trị đã nhận khi tạo phiên
 * @param occurredAt           ISO-8601 UTC, ví dụ {@code 2026-11-01T12:03:00Z}
 */
@JsonPropertyOrder({ "eventId", "type", "gatewayPaymentId", "gatewayTransactionId", "merchantRef", "amount",
        "currency", "occurredAt" })
public record WebhookEvent(
        String eventId,
        String type,
        String gatewayPaymentId,
        String gatewayTransactionId,
        String merchantRef,
        BigDecimal amount,
        String currency,
        String occurredAt) {

    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
}
