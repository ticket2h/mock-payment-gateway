package com.ticket2h.mockpay.payment;

import java.math.BigDecimal;
import java.time.Instant;

import com.ticket2h.mockpay.chaos.ChaosConfig;

/**
 * Phiên thanh toán (bất biến; mọi chuyển trạng thái tạo bản mới qua {@code ConcurrentHashMap.compute}).
 *
 * @param chaos cấu hình chaos chụp lúc tạo phiên
 */
public record PaymentSession(
        String gatewayPaymentId,
        String merchantRef,
        BigDecimal amount,
        String currency,
        String description,
        String returnUrl,
        String notifyUrl,
        Instant createdAt,
        Instant expiresAt,
        ChaosConfig chaos,
        PaymentStatus status,
        Instant completedAt,
        String gatewayTransactionId,
        String webhookEventId,
        BigDecimal refundedAmount) {

    /** Trạng thái nhìn từ bên ngoài: phiên PENDING đã quá hạn được báo là EXPIRED. */
    public PaymentStatus effectiveStatus(Instant now) {
        return status == PaymentStatus.PENDING && isExpiredAt(now) ? PaymentStatus.EXPIRED : status;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    PaymentSession complete(PaymentResult result, Instant now, String transactionId, String eventId) {
        return new PaymentSession(gatewayPaymentId, merchantRef, amount, currency, description, returnUrl,
                notifyUrl, createdAt, expiresAt, chaos, result.status(), now, transactionId, eventId,
                refundedAmount);
    }

    PaymentSession withRefunded(BigDecimal additional) {
        return new PaymentSession(gatewayPaymentId, merchantRef, amount, currency, description, returnUrl,
                notifyUrl, createdAt, expiresAt, chaos, status, completedAt, gatewayTransactionId, webhookEventId,
                refundedAmount.add(additional));
    }
}
