package com.ticket2h.mockpay.refund;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import com.ticket2h.mockpay.chaos.ChaosConfig;
import com.ticket2h.mockpay.chaos.ChaosRandom;
import com.ticket2h.mockpay.chaos.ChaosService;
import com.ticket2h.mockpay.common.ApiException;
import com.ticket2h.mockpay.config.MockPayProperties;
import com.ticket2h.mockpay.payment.CreatePaymentRequest;
import com.ticket2h.mockpay.payment.PaymentResult;
import com.ticket2h.mockpay.payment.PaymentService;
import com.ticket2h.mockpay.webhook.WebhookDeliveryLog;
import com.ticket2h.mockpay.webhook.WebhookDispatcher;
import com.ticket2h.mockpay.webhook.WebhookSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kiểm tra {@code refundFailureRate} với nguồn ngẫu nhiên có kịch bản (không cần Spring). */
class RefundServiceTest {

    private final MockPayProperties properties = new MockPayProperties("key", "secret", "http://mockpay.test",
            new MockPayProperties.Webhook(Duration.ofMillis(10), 1, Duration.ofMillis(200), Duration.ofMillis(200)));
    private final ChaosService chaos = new ChaosService();
    private final WebhookDispatcher dispatcher = new WebhookDispatcher(new WebhookSigner("secret"),
            new WebhookDeliveryLog(), Clock.systemUTC(), properties);
    private final PaymentService payments = new PaymentService(chaos, dispatcher, Clock.systemUTC(), properties);
    private final Deque<Double> randomValues = new ArrayDeque<>();
    private final ChaosRandom scriptedRandom = () -> {
        Double next = randomValues.poll();
        if (next == null) {
            throw new AssertionError("random source consulted more often than expected");
        }
        return next;
    };
    private final RefundService refunds = new RefundService(payments, chaos, scriptedRandom, Clock.systemUTC());

    @AfterEach
    void shutdown() {
        dispatcher.destroy();
    }

    private String paidTransaction(String amount) {
        chaos.update(new ChaosConfig(0, 1, true, false, 0, 0.0)); // không gửi webhook thật
        String id = payments.create(new CreatePaymentRequest(UUID.randomUUID().toString(), new BigDecimal(amount),
                "VND", "test", "http://localhost/return", "http://localhost/notify", 60)).session().gatewayPaymentId();
        return payments.complete(id, PaymentResult.SUCCESS).gatewayTransactionId();
    }

    @Test
    void failureRateIsComparedAgainstTheInjectedRandomSource() {
        String tx = paidTransaction("1000");
        chaos.update(new ChaosConfig(0, 1, false, false, 0, 0.5));
        randomValues.addAll(List.of(0.49, 0.10, 0.50));
        String key = "order-1";
        RefundRequest request = new RefundRequest(tx, new BigDecimal("1000"), key);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> refunds.refund(request))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
        Refund refund = refunds.refund(request);
        assertThat(refund.status()).isEqualTo(Refund.SUCCEEDED);

        // Replay trả kết quả đã lưu và không hỏi nguồn ngẫu nhiên nữa
        assertThat(randomValues).isEmpty();
        assertThat(refunds.refund(request)).isEqualTo(refund);
        assertThat(payments.findByTransactionId(tx).orElseThrow().refundedAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void zeroFailureRateNeverConsultsTheRandomSource() {
        String tx = paidTransaction("500");
        Refund refund = refunds.refund(new RefundRequest(tx, new BigDecimal("200"), "a"));
        Refund second = refunds.refund(new RefundRequest(tx, new BigDecimal("300"), "b"));

        assertThat(refund.refundId()).isNotEqualTo(second.refundId());
        assertThatThrownBy(() -> refunds.refund(new RefundRequest(tx, new BigDecimal("0.01"), "c")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("AMOUNT_EXCEEDS_REFUNDABLE"));
    }
}
