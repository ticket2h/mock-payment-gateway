package com.ticket2h.mockpay.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ký webhook: {@code X-MockPay-Signature = hex(HMAC_SHA256(webhookSecret, timestamp + "." + rawBody))}.
 * <p>
 * Chữ ký được tính trực tiếp trên mảng byte của body sẽ được gửi đi (không serialize lại),
 * nối sau chuỗi ASCII {@code "<timestamp>."}. Kết quả là hex chữ thường.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public WebhookSigner(String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            throw new IllegalArgumentException("webhook secret must not be empty");
        }
        this.key = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(long timestampEpochSeconds, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((timestampEpochSeconds + ".").getBytes(StandardCharsets.US_ASCII));
            mac.update(rawBody);
            return HexFormat.of().formatHex(mac.doFinal());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }

    @Override
    public String toString() {
        return "WebhookSigner[" + ALGORITHM + "]";
    }
}
