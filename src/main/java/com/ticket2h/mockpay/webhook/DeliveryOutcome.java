package com.ticket2h.mockpay.webhook;

public enum DeliveryOutcome {

    /** Merchant trả 2xx. */
    DELIVERED,
    /** Merchant trả mã khác 2xx. */
    HTTP_ERROR,
    /** Merchant không trả lời trong {@code mockpay.webhook.timeout}. */
    TIMEOUT,
    /** Không kết nối được / lỗi I/O. */
    NETWORK_ERROR,
    /** Chaos {@code failFirstDeliveries}: giả vờ lỗi mạng, không gửi request nào. */
    SIMULATED_NETWORK_ERROR,
    /** Chaos {@code dropWebhook}: không bao giờ gửi. */
    DROPPED;

    public boolean isSuccess() {
        return this == DELIVERED;
    }
}
