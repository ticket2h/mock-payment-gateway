package com.ticket2h.mockpay.webhook;

import java.nio.charset.StandardCharsets;

import com.ticket2h.mockpay.config.MockPayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giá trị mong đợi được tính độc lập bằng openssl:
 * <pre>
 * printf '%s' "$TS.$BODY" | openssl dgst -sha256 -hmac "$SECRET"
 * </pre>
 */
class WebhookSignerTest {

    private static final String SECRET = "whsec_test_7f3c9a";
    private static final long TIMESTAMP = 1793534400L;

    private static final String BODY = "{\"eventId\":\"evt_51c2\",\"type\":\"payment.succeeded\","
            + "\"gatewayPaymentId\":\"mp_8f3a\",\"gatewayTransactionId\":\"tx_77d1\",\"merchantRef\":\"2b1f\","
            + "\"amount\":4600000.00,\"currency\":\"VND\",\"occurredAt\":\"2026-11-01T12:03:00Z\"}";

    private final WebhookSigner signer = new WebhookSigner(SECRET);

    @Test
    void signsTimestampDotRawBodyAsLowercaseHexHmacSha256() {
        assertThat(BODY.getBytes(StandardCharsets.UTF_8)).hasSize(205);
        assertThat(signer.sign(TIMESTAMP, BODY.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("26ff194c03922704fbc89db02d1c769d4c50ba9c5ff7e93d3b20ccf9ab9ea432");
    }

    @Test
    void signsTheExactUtf8BytesOfTheBody() {
        String body = "{\"note\":\"Vé TicketRush – đã thanh toán\"}";
        assertThat(signer.sign(TIMESTAMP, body.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("09bd16bef30b2c8c899da22de4e7e1862f109cb070152c318750d55e6039d3ea");
    }

    @Test
    void anyChangeToTimestampBodyOrSecretChangesTheSignature() {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        String original = signer.sign(TIMESTAMP, body);

        assertThat(signer.sign(TIMESTAMP + 1, body)).isNotEqualTo(original);
        assertThat(signer.sign(TIMESTAMP, BODY.replace("4600000.00", "4600000").getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(original);
        assertThat(new WebhookSigner(SECRET + "x").sign(TIMESTAMP, body)).isNotEqualTo(original);
    }

    @Test
    void secretsDoNotLeakThroughToString() {
        MockPayProperties properties = new MockPayProperties("api-key-value", SECRET, "http://localhost:9090",
                new MockPayProperties.Webhook(java.time.Duration.ofSeconds(2), 6, java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofSeconds(2)));

        assertThat(signer.toString()).doesNotContain(SECRET);
        assertThat(properties.toString()).doesNotContain(SECRET).doesNotContain("api-key-value");
    }
}
