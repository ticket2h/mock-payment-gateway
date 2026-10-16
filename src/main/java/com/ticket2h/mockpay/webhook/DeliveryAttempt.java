package com.ticket2h.mockpay.webhook;

import java.time.Instant;

/**
 * Một dòng nhật ký gửi webhook.
 *
 * @param deliveryNo    lượt gửi thứ mấy (1..duplicateDeliveries); mỗi lượt có lịch thử lại riêng
 * @param attempt       lần thử thứ mấy trong lượt (1..max-attempts)
 * @param httpStatus    mã HTTP merchant trả, {@code null} nếu không có phản hồi
 * @param timestamp     giá trị {@code X-MockPay-Timestamp} đã gửi, {@code null} nếu không gửi request
 * @param nextRetryInMs bao lâu nữa thử lại, {@code null} nếu không thử lại
 * @param gaveUp        lần thử cuối cùng vẫn lỗi, không thử lại nữa
 */
public record DeliveryAttempt(
        String eventId,
        String type,
        String gatewayPaymentId,
        String merchantRef,
        String notifyUrl,
        int deliveryNo,
        int attempt,
        DeliveryOutcome outcome,
        Integer httpStatus,
        String error,
        Long timestamp,
        Instant at,
        Long nextRetryInMs,
        boolean gaveUp) {
}
