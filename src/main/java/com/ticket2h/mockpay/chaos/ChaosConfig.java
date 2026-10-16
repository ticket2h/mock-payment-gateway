package com.ticket2h.mockpay.chaos;

/**
 * Cấu hình chaos. Mỗi phiên thanh toán chụp lại cấu hình tại thời điểm tạo, nên thay đổi chỉ
 * áp dụng cho các phiên tạo sau đó. Riêng {@code refundFailureRate} đọc cấu hình hiện tại lúc
 * gọi API hoàn tiền.
 *
 * @param webhookDelayMs        chờ bao lâu (ms) rồi mới gửi webhook
 * @param duplicateDeliveries   gửi mỗi webhook bao nhiêu lần, song song (cùng {@code eventId})
 * @param dropWebhook           không bao giờ gửi webhook
 * @param webhookBeforeRedirect gửi (ít nhất lần thử đầu) webhook xong rồi mới trả redirect / response
 * @param failFirstDeliveries   giả vờ N lần gửi đầu (của mỗi lượt gửi) bị lỗi mạng
 * @param refundFailureRate     tỉ lệ (0..1) API hoàn tiền trả 503
 */
public record ChaosConfig(
        long webhookDelayMs,
        int duplicateDeliveries,
        boolean dropWebhook,
        boolean webhookBeforeRedirect,
        int failFirstDeliveries,
        double refundFailureRate) {

    public static final ChaosConfig DEFAULT = new ChaosConfig(0, 1, false, false, 0, 0.0);
}
