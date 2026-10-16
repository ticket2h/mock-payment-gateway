package com.ticket2h.mockpay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ticket2h.mockpay.support.MockPayTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefundApiTest extends MockPayTestSupport {

    private record PaidPayment(String gatewayPaymentId, String gatewayTransactionId) {
    }

    private PaidPayment paidPayment(String amount) throws Exception {
        String id = createPayment(newMerchantRef(), amount);
        JsonNode session = completeViaAdmin(id, "SUCCESS");
        return new PaidPayment(id, session.get("gatewayTransactionId").asString());
    }

    private MvcResult refund(String transactionId, String amount, String idempotencyKey) throws Exception {
        String body = """
                { "gatewayTransactionId": "%s", "amount": %s, "idempotencyKey": "%s" }
                """.formatted(transactionId, amount, idempotencyKey);
        return mvc.perform(post("/api/v1/refunds").header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    @Test
    void refundSucceedsAndSameIdempotencyKeyReturnsTheSameResult() throws Exception {
        PaidPayment payment = paidPayment("4600000.00");
        String key = UUID.randomUUID().toString();

        MvcResult first = refund(payment.gatewayTransactionId(), "4600000", key);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode refund = read(first);
        assertThat(refund.get("refundId").asString()).matches("rf_[0-9a-f]{32}");
        assertThat(refund.get("status").asString()).isEqualTo("SUCCEEDED");

        MvcResult second = refund(payment.gatewayTransactionId(), "4600000", key);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(second)).isEqualTo(body(first));

        assertThat(adminPayment(payment.gatewayPaymentId()).get("refundedAmount").decimalValue())
                .isEqualByComparingTo("4600000");

        // Key khác: không còn gì để hoàn
        assertThat(refund(payment.gatewayTransactionId(), "1", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void concurrentRefundsWithSameKeyRefundOnlyOnce() throws Exception {
        PaidPayment payment = paidPayment("4600000");
        String key = UUID.randomUUID().toString();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return refund(payment.gatewayTransactionId(), "4600000", key);
                }));
            }
            start.countDown();
            Set<String> refundIds = new HashSet<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                refundIds.add(read(result).get("refundId").asString());
            }
            assertThat(refundIds).hasSize(1);
        }
        assertThat(adminPayment(payment.gatewayPaymentId()).get("refundedAmount").decimalValue())
                .isEqualByComparingTo("4600000");
    }

    @Test
    void concurrentPartialRefundsNeverExceedThePaidAmount() throws Exception {
        PaidPayment payment = paidPayment("100");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return refund(payment.gatewayTransactionId(), "10", UUID.randomUUID().toString());
                }));
            }
            start.countDown();
            int ok = 0;
            int rejected = 0;
            for (Future<MvcResult> future : futures) {
                int status = future.get().getResponse().getStatus();
                if (status == 200) {
                    ok++;
                }
                else if (status == 400) {
                    rejected++;
                }
            }
            assertThat(ok).isEqualTo(10);
            assertThat(rejected).isEqualTo(6);
        }
        assertThat(adminPayment(payment.gatewayPaymentId()).get("refundedAmount").decimalValue())
                .isEqualByComparingTo("100");
    }

    @Test
    void unknownTransactionIs404() throws Exception {
        MvcResult result = refund("tx_does_not_exist", "1000", UUID.randomUUID().toString());
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(result).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void amountGreaterThanPaidIs400AndDoesNotConsumeTheKey() throws Exception {
        PaidPayment payment = paidPayment("4600000");
        String key = UUID.randomUUID().toString();

        MvcResult tooMuch = refund(payment.gatewayTransactionId(), "4600000.01", key);
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(tooMuch).get("code").asString()).isEqualTo("AMOUNT_EXCEEDS_PAYMENT");

        assertThat(refund(payment.gatewayTransactionId(), "4600000", key).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void invalidRefundRequestsAre400() throws Exception {
        PaidPayment payment = paidPayment("1000");
        assertThat(refund(payment.gatewayTransactionId(), "0", "k-" + UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(refund(payment.gatewayTransactionId(), "-1", "k-" + UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(400);
        mvc.perform(post("/api/v1/refunds").header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gatewayTransactionId\":\"" + payment.gatewayTransactionId() + "\",\"amount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refundFailureRateReturns503AndARetryWithTheSameKeySucceeds() throws Exception {
        PaidPayment payment = paidPayment("4600000");
        String key = UUID.randomUUID().toString();

        // Đặt chaos sau khi phiên đã tạo: refundFailureRate đọc cấu hình hiện tại lúc gọi hoàn tiền
        putChaos("{\"refundFailureRate\": 1.0}");
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/refunds").header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            { "gatewayTransactionId": "%s", "amount": 4600000, "idempotencyKey": "%s" }
                            """.formatted(payment.gatewayTransactionId(), key)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("REFUND_TEMPORARILY_UNAVAILABLE"));
        }
        assertThat(adminPayment(payment.gatewayPaymentId()).get("refundedAmount").decimalValue())
                .isEqualByComparingTo("0");

        putChaos("{\"refundFailureRate\": 0.0}");
        MvcResult retry = refund(payment.gatewayTransactionId(), "4600000", key);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(retry).get("status").asString()).isEqualTo("SUCCEEDED");

        // Kết quả đã lưu được trả lại kể cả khi chaos bật lại
        putChaos("{\"refundFailureRate\": 1.0}");
        MvcResult replay = refund(payment.gatewayTransactionId(), "4600000", key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replay)).isEqualTo(body(retry));
        assertThat(adminPayment(payment.gatewayPaymentId()).get("refundedAmount").decimalValue())
                .isEqualByComparingTo("4600000");
    }
}
