package com.ticket2h.mockpay.payment;

import java.math.BigDecimal;
import java.time.Instant;

import com.ticket2h.mockpay.chaos.ChaosConfig;

/** Các response JSON của module thanh toán. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** {@code POST /api/v1/payments} (201 lần đầu, 200 khi gọi lại cùng merchantRef). */
    public record CreatePaymentResponse(String gatewayPaymentId, String paymentUrl, Instant expiresAt) {
    }

    /** {@code GET /api/v1/payments/by-ref/{merchantRef}}. */
    public record PaymentStatusResponse(PaymentStatus status, String gatewayPaymentId, String gatewayTransactionId,
            BigDecimal amount, String currency) {
    }

    /** Toàn bộ thông tin phiên, cho {@code /admin/payments/**}. */
    public record AdminPaymentView(
            String gatewayPaymentId,
            String merchantRef,
            PaymentStatus status,
            BigDecimal amount,
            String currency,
            String description,
            String paymentUrl,
            String returnUrl,
            String notifyUrl,
            Instant createdAt,
            Instant expiresAt,
            Instant completedAt,
            String gatewayTransactionId,
            String webhookEventId,
            BigDecimal refundedAmount,
            ChaosConfig chaos) {
    }
}
