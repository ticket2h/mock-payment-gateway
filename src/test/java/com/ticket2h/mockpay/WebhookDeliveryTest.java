package com.ticket2h.mockpay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.ticket2h.mockpay.support.MockPayTestSupport;
import com.ticket2h.mockpay.support.WebhookReceiver.Received;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook thật gửi qua HTTP tới merchant giả. Context test rút lịch thử lại còn 50, 100, 200, 400, 800 ms
 * và timeout còn 1 giây.
 */
class WebhookDeliveryTest extends MockPayTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private List<Received> awaitReceived(int count) {
        await().atMost(WAIT).until(() -> receiver.received().size() >= count);
        return receiver.received();
    }

    private JsonNode awaitDeliveries(String gatewayPaymentId, int count) throws Exception {
        await().atMost(WAIT).until(() -> deliveries(gatewayPaymentId).size() >= count);
        return deliveries(gatewayPaymentId);
    }

    @Test
    void successWebhookIsSignedOverTheExactBytesAndCarriesTheContractFields() throws Exception {
        String ref = newMerchantRef();
        String id = createPayment(ref, "4600000.00");
        long before = Instant.now().getEpochSecond();
        JsonNode session = completeViaAdmin(id, "SUCCESS");

        Received webhook = awaitReceived(1).getFirst();
        assertThat(webhook.signatureValid()).isTrue();
        assertThat(webhook.header("Content-Type")).isEqualTo("application/json");
        assertThat(webhook.header("X-MockPay-Signature")).matches("[0-9a-f]{64}");
        assertThat(Long.parseLong(webhook.header("X-MockPay-Timestamp")))
                .isBetween(before - 1, Instant.now().getEpochSecond() + 1);

        String raw = webhook.bodyString();
        assertThat(raw).contains("\"amount\":4600000.00").contains("\"currency\":\"VND\"");
        JsonNode body = json.readTree(raw);
        assertThat(body.propertyNames()).containsExactly("eventId", "type", "gatewayPaymentId",
                "gatewayTransactionId", "merchantRef", "amount", "currency", "occurredAt");
        assertThat(body.get("eventId").asString()).isEqualTo(webhook.header("X-MockPay-Event-Id"))
                .isEqualTo(session.get("webhookEventId").asString()).matches("evt_[0-9a-f]{32}");
        assertThat(body.get("type").asString()).isEqualTo("payment.succeeded");
        assertThat(body.get("gatewayPaymentId").asString()).isEqualTo(id);
        assertThat(body.get("gatewayTransactionId").asString())
                .isEqualTo(session.get("gatewayTransactionId").asString());
        assertThat(body.get("merchantRef").asString()).isEqualTo(ref);
        assertThat(body.get("occurredAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

        JsonNode log = awaitDeliveries(id, 1);
        assertThat(log.get(0).get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(log.get(0).get("httpStatus").asInt()).isEqualTo(200);
    }

    @Test
    void integerAmountIsEchoedAsReceived() throws Exception {
        String id = createPayment(newMerchantRef(), "4600000");
        completeViaAdmin(id, "SUCCESS");
        assertThat(awaitReceived(1).getFirst().bodyString()).contains("\"amount\":4600000,");
    }

    @Test
    void failedAndCancelledPaymentsSendPaymentFailedWithNullTransaction() throws Exception {
        String failed = createPayment(newMerchantRef(), "1000");
        completeViaAdmin(failed, "FAILED");
        String cancelledRef = newMerchantRef();
        String cancelled = createPayment(cancelledRef, "2000");
        mvc.perform(post("/pay/{id}/complete", cancelled).param("result", "CANCELLED"))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", RETURN_URL + "?merchantRef=" + cancelledRef + "&status=CANCELLED"));

        List<Received> received = awaitReceived(2);
        for (Received webhook : received) {
            assertThat(webhook.signatureValid()).isTrue();
            assertThat(webhook.bodyString()).contains("\"type\":\"payment.failed\"")
                    .contains("\"gatewayTransactionId\":null");
        }
        assertThat(received).extracting(r -> json.readTree(r.bodyString()).get("gatewayPaymentId").asString())
                .containsExactlyInAnyOrder(failed, cancelled);
        assertThat(adminPayment(cancelled).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void non2xxResponsesAreRetriedWithTheSameEventIdAndBody() throws Exception {
        receiver.respondWith(requestNo -> requestNo <= 2 ? 500 : 200);
        String id = createPayment(newMerchantRef(), "4600000");
        completeViaAdmin(id, "SUCCESS");

        List<Received> received = awaitReceived(3);
        assertThat(received).hasSize(3).allMatch(Received::signatureValid);
        assertThat(received).extracting(r -> r.header("X-MockPay-Event-Id")).containsOnly(
                received.getFirst().header("X-MockPay-Event-Id"));
        assertThat(received).extracting(Received::bodyString).containsOnly(received.getFirst().bodyString());

        JsonNode log = awaitDeliveries(id, 3);
        assertThat(log.get(0).get("outcome").asString()).isEqualTo("HTTP_ERROR");
        assertThat(log.get(0).get("httpStatus").asInt()).isEqualTo(500);
        assertThat(log.get(0).get("nextRetryInMs").asLong()).isEqualTo(50);
        assertThat(log.get(1).get("nextRetryInMs").asLong()).isEqualTo(100);
        assertThat(log.get(2).get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(log.get(2).get("attempt").asInt()).isEqualTo(3);

        await().during(Duration.ofMillis(300)).atMost(WAIT).until(() -> receiver.received().size() == 3);
    }

    @Test
    void givesUpAfterSixAttemptsWithExponentialBackoff() throws Exception {
        receiver.respondWith(requestNo -> 503);
        String id = createPayment(newMerchantRef(), "1000");
        completeViaAdmin(id, "SUCCESS");

        JsonNode log = awaitDeliveries(id, 6);
        assertThat(log.size()).isEqualTo(6);
        long[] expectedDelays = { 50, 100, 200, 400, 800 };
        for (int i = 0; i < 5; i++) {
            assertThat(log.get(i).get("attempt").asInt()).isEqualTo(i + 1);
            assertThat(log.get(i).get("nextRetryInMs").asLong()).isEqualTo(expectedDelays[i]);
            assertThat(log.get(i).get("gaveUp").asBoolean()).isFalse();
        }
        assertThat(log.get(5).get("nextRetryInMs").isNull()).isTrue();
        assertThat(log.get(5).get("gaveUp").asBoolean()).isTrue();

        await().during(Duration.ofMillis(500)).atMost(WAIT).until(() -> receiver.received().size() == 6);
    }

    @Test
    void slowResponsesTimeOutAndAreRetried() throws Exception {
        receiver.respondWith(requestNo -> {
            if (requestNo == 1) {
                Thread.sleep(1_500); // > mockpay.webhook.timeout (1s trong test)
            }
            return 200;
        });
        String id = createPayment(newMerchantRef(), "1000");
        completeViaAdmin(id, "SUCCESS");

        JsonNode log = awaitDeliveries(id, 2);
        assertThat(log.get(0).get("outcome").asString()).isEqualTo("TIMEOUT");
        assertThat(log.get(1).get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(receiver.received()).extracting(r -> r.header("X-MockPay-Event-Id")).hasSize(2)
                .containsOnly(receiver.received().getFirst().header("X-MockPay-Event-Id"));
    }

    @Test
    void chaosWebhookDelayMsPostponesTheFirstDelivery() throws Exception {
        putChaos("{\"webhookDelayMs\": 600}");
        String id = createPayment(newMerchantRef(), "1000");
        Instant completedAt = Instant.now();
        completeViaAdmin(id, "SUCCESS");

        Received webhook = awaitReceived(1).getFirst();
        assertThat(Duration.between(completedAt, webhook.receivedAt())).isGreaterThanOrEqualTo(Duration.ofMillis(600));
        assertThat(webhook.signatureValid()).isTrue();
    }

    @Test
    void chaosDuplicateDeliveriesSendsTheSameEventInParallel() throws Exception {
        int copies = 3;
        CountDownLatch allArrived = new CountDownLatch(copies);
        receiver.respondWith(requestNo -> {
            allArrived.countDown();
            // giữ request cho tới khi đủ 3 bản cùng đang mở: chỉ xảy ra nếu gửi song song
            allArrived.await(800, TimeUnit.MILLISECONDS);
            return 200;
        });
        putChaos("{\"duplicateDeliveries\": 3}");
        String id = createPayment(newMerchantRef(), "1000");
        completeViaAdmin(id, "SUCCESS");

        List<Received> received = awaitReceived(copies);
        assertThat(received).hasSize(copies).allMatch(Received::signatureValid);
        assertThat(received).extracting(r -> r.header("X-MockPay-Event-Id")).containsOnly(
                received.getFirst().header("X-MockPay-Event-Id"));
        assertThat(received).extracting(Received::bodyString).containsOnly(received.getFirst().bodyString());
        assertThat(receiver.maxInFlight()).isEqualTo(copies);

        JsonNode log = awaitDeliveries(id, copies);
        assertThat(log).extracting(n -> n.get("deliveryNo").asInt()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(log).extracting(n -> n.get("outcome").asString()).containsOnly("DELIVERED");
        await().during(Duration.ofMillis(300)).atMost(WAIT).until(() -> receiver.received().size() == copies);
    }

    @Test
    void chaosDropWebhookNeverSends() throws Exception {
        putChaos("{\"dropWebhook\": true}");
        String id = createPayment(newMerchantRef(), "1000");
        completeViaAdmin(id, "SUCCESS");

        JsonNode log = deliveries(id);
        assertThat(log.size()).isEqualTo(1);
        assertThat(log.get(0).get("outcome").asString()).isEqualTo("DROPPED");
        await().during(Duration.ofMillis(500)).atMost(WAIT).until(() -> receiver.received().isEmpty());
    }

    @Test
    void chaosWebhookBeforeRedirectDeliversBeforeTheRedirectIsReturned() throws Exception {
        receiver.respondWith(requestNo -> {
            Thread.sleep(300);
            return 200;
        });
        putChaos("{\"webhookBeforeRedirect\": true}");
        String id = createPayment(newMerchantRef(), "1000");

        long start = System.nanoTime();
        mvc.perform(post("/pay/{id}/complete", id).param("result", "SUCCESS")).andExpect(status().isSeeOther());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Ngay khi redirect trả về, merchant đã nhận và đã trả lời webhook
        assertThat(receiver.received()).hasSize(1);
        assertThat(receiver.received().getFirst().signatureValid()).isTrue();
        JsonNode log = deliveries(id);
        assertThat(log.size()).isEqualTo(1);
        assertThat(log.get(0).get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void withoutWebhookBeforeRedirectTheRedirectDoesNotWaitForTheMerchant() throws Exception {
        receiver.respondWith(requestNo -> {
            Thread.sleep(700);
            return 200;
        });
        String id = createPayment(newMerchantRef(), "1000");

        mvc.perform(post("/pay/{id}/complete", id).param("result", "SUCCESS")).andExpect(status().isSeeOther());

        assertThat(deliveries(id).size()).isZero();
        assertThat(awaitDeliveries(id, 1).get(0).get("outcome").asString()).isEqualTo("DELIVERED");
    }

    @Test
    void chaosFailFirstDeliveriesSimulatesNetworkErrorsThenFollowsTheRetrySchedule() throws Exception {
        putChaos("{\"failFirstDeliveries\": 2}");
        String id = createPayment(newMerchantRef(), "1000");
        completeViaAdmin(id, "SUCCESS");

        JsonNode log = awaitDeliveries(id, 3);
        assertThat(log.get(0).get("outcome").asString()).isEqualTo("SIMULATED_NETWORK_ERROR");
        assertThat(log.get(0).get("nextRetryInMs").asLong()).isEqualTo(50);
        assertThat(log.get(1).get("outcome").asString()).isEqualTo("SIMULATED_NETWORK_ERROR");
        assertThat(log.get(1).get("nextRetryInMs").asLong()).isEqualTo(100);
        assertThat(log.get(2).get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(log.get(2).get("attempt").asInt()).isEqualTo(3);

        // Hai lần đầu không có request nào thật sự tới merchant
        List<Received> received = awaitReceived(1);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().signatureValid()).isTrue();
    }

    @Test
    void chaosFailFirstDeliveriesWithWebhookBeforeRedirectRetriesInTheBackground() throws Exception {
        putChaos("{\"failFirstDeliveries\": 1, \"webhookBeforeRedirect\": true}");
        String id = createPayment(newMerchantRef(), "1000");
        completeViaAdmin(id, "SUCCESS");

        assertThat(deliveries(id).get(0).get("outcome").asString()).isEqualTo("SIMULATED_NETWORK_ERROR");
        assertThat(awaitDeliveries(id, 2).get(1).get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(awaitReceived(1)).hasSize(1);
    }
}
