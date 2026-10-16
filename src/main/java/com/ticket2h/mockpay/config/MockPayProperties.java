package com.ticket2h.mockpay.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình {@code mockpay.*}.
 *
 * @param apiKey        giá trị header {@code X-Api-Key} mà merchant phải gửi cho mọi API {@code /api/**}
 * @param webhookSecret khoá HMAC dùng ký webhook; không bao giờ được ghi log
 * @param publicBaseUrl gốc URL dùng để dựng {@code paymentUrl}
 * @param webhook       lịch gửi lại webhook và timeout
 */
@ConfigurationProperties(prefix = "mockpay")
public record MockPayProperties(
        String apiKey,
        String webhookSecret,
        @DefaultValue("http://localhost:9090") String publicBaseUrl,
        @DefaultValue Webhook webhook) {

    public MockPayProperties {
        requireConfigured("mockpay.api-key", "MOCKPAY_API_KEY", apiKey);
        requireConfigured("mockpay.webhook-secret", "MOCKPAY_WEBHOOK_SECRET", webhookSecret);
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            publicBaseUrl = "http://localhost:9090";
        }
        while (publicBaseUrl.endsWith("/")) {
            publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
        }
    }

    /**
     * @param retryBaseDelay lần thử lại thứ k (k = 1..) chờ {@code retryBaseDelay * 2^(k-1)}
     * @param maxAttempts    tổng số lần gửi tối đa (kể cả lần đầu)
     * @param timeout        thời gian chờ phản hồi tối đa của mỗi lần gửi
     * @param connectTimeout thời gian chờ mở kết nối tối đa
     */
    public record Webhook(
            @DefaultValue("2s") Duration retryBaseDelay,
            @DefaultValue("6") int maxAttempts,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("2s") Duration connectTimeout) {

        public Webhook {
            requirePositive("mockpay.webhook.retry-base-delay", retryBaseDelay);
            requirePositive("mockpay.webhook.timeout", timeout);
            requirePositive("mockpay.webhook.connect-timeout", connectTimeout);
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("mockpay.webhook.max-attempts must be >= 1");
            }
        }

        private static void requirePositive(String name, Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be a positive duration");
            }
        }
    }

    private static void requireConfigured(String property, String envVar, String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException(property + " is not configured: set " + envVar
                    + " in the environment or in the .env file at the repository root");
        }
    }

    /** Không để lộ api-key / webhook-secret nếu record bị in ra log. */
    @Override
    public String toString() {
        return "MockPayProperties[apiKey=****, webhookSecret=****, publicBaseUrl=" + publicBaseUrl
                + ", webhook=" + webhook + "]";
    }
}
