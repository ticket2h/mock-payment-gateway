package com.ticket2h.mockpay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.ticket2h.mockpay.support.MockPayTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentApiTest extends MockPayTestSupport {

    @Test
    void createReturns201WithIdPaymentUrlAndExpiry() throws Exception {
        Instant before = Instant.now();
        MvcResult result = postPayment(paymentJson(newMerchantRef(), "4600000", 900));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = read(result);
        String id = body.get("gatewayPaymentId").asString();
        assertThat(id).matches("mp_[0-9a-f]{32}");
        assertThat(body.get("paymentUrl").asString()).isEqualTo("http://mockpay.test/pay/" + id);
        assertThat(body.get("expiresAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        Instant expiresAt = Instant.parse(body.get("expiresAt").asString());
        assertThat(expiresAt).isBetween(before.plusSeconds(899), Instant.now().plusSeconds(900));
        assertThat(body.properties()).hasSize(3);
    }

    @Test
    void repeatedCreateWithSameMerchantRefReturns200WithTheSameBody() throws Exception {
        String ref = newMerchantRef();
        MvcResult first = postPayment(paymentJson(ref, "4600000.00"));
        MvcResult second = postPayment(paymentJson(ref, "4600000.00"));
        MvcResult third = postPayment(paymentJson(ref, "999"));

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(third.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(second)).isEqualTo(body(first));
        assertThat(body(third)).isEqualTo(body(first));
        assertThat(adminPayment(read(first).get("gatewayPaymentId").asString()).get("amount").decimalValue())
                .isEqualByComparingTo("4600000");
    }

    @Test
    void concurrentCreatesWithSameMerchantRefYieldExactlyOneSession() throws Exception {
        String ref = newMerchantRef();
        String requestBody = paymentJson(ref, "4600000");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<MvcResult> call = () -> {
                    start.await();
                    return postPayment(requestBody);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            Set<String> ids = new java.util.HashSet<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                statuses.add(result.getResponse().getStatus());
                ids.add(read(result).get("gatewayPaymentId").asString());
            }
            assertThat(statuses).containsOnly(201, 200);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(ids).hasSize(1);
        }
        mvc.perform(get("/admin/payments").param("merchantRef", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidCreateRequestsAre400() throws Exception {
        String ref = newMerchantRef();
        String valid = paymentJson(ref, "4600000");
        List<String> invalid = List.of(
                valid.replace("\"merchantRef\": \"" + ref + "\"", "\"merchantRef\": \"\""),
                valid.replace("\"amount\": 4600000", "\"amount\": -5"),
                valid.replace("\"amount\": 4600000", "\"amount\": 0"),
                valid.replace("\"amount\": 4600000,", ""),
                valid.replace("\"VND\"", "\"vnd\""),
                valid.replace(receiver.url(), "not a url"),
                valid.replace(RETURN_URL, "ftp://example.com/return"),
                valid.replace("\"expiresInSeconds\": 900", "\"expiresInSeconds\": 0"));
        for (String body : invalid) {
            mvc.perform(post("/api/v1/payments").header("X-Api-Key", API_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(post("/api/v1/payments").header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payments/by-ref/{ref}", ref).header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiresInSecondsDefaultsTo900() throws Exception {
        String body = paymentJson(newMerchantRef(), "1000").replace(",\n  \"expiresInSeconds\": 900", "");
        assertThat(body).doesNotContain("expiresInSeconds");
        MvcResult result = postPayment(body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Instant expiresAt = Instant.parse(read(result).get("expiresAt").asString());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofSeconds(890),
                Duration.ofSeconds(900));
    }

    @Test
    void byRefReportsStatusTransactionAndAmountExactlyAsReceived() throws Exception {
        String ref = newMerchantRef();
        String id = createPayment(ref, "4600000.00");

        MvcResult pending = mvc.perform(get("/api/v1/payments/by-ref/{ref}", ref).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.gatewayPaymentId").value(id))
                .andExpect(jsonPath("$.gatewayTransactionId").doesNotExist())
                .andExpect(jsonPath("$.currency").value("VND"))
                .andReturn();
        assertThat(body(pending)).contains("\"amount\":4600000.00");

        JsonNode completed = completeViaAdmin(id, "SUCCESS");
        String tx = completed.get("gatewayTransactionId").asString();
        assertThat(tx).matches("tx_[0-9a-f]{32}");

        mvc.perform(get("/api/v1/payments/by-ref/{ref}", ref).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.gatewayTransactionId").value(tx));
    }

    @Test
    void payPageShowsAmountAndThreeButtons() throws Exception {
        String id = createPayment(newMerchantRef(), "4600000");

        mvc.perform(get("/pay/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("4.600.000")))
                .andExpect(content().string(containsString("action=\"/pay/" + id + "/complete\"")))
                .andExpect(content().string(containsString("value=\"SUCCESS\">Thành công</button>")))
                .andExpect(content().string(containsString("value=\"FAILED\">Thất bại</button>")))
                .andExpect(content().string(containsString("value=\"CANCELLED\">Huỷ</button>")));

        mvc.perform(get("/pay/{id}", "mp_does_not_exist")).andExpect(status().isNotFound());
    }

    @Test
    void completingFromThePayPageRedirectsToReturnUrlWithUnsignedStatus() throws Exception {
        String ref = newMerchantRef();
        String id = createPayment(ref, "4600000");

        mvc.perform(post("/pay/{id}/complete", id).param("result", "SUCCESS"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RETURN_URL + "?merchantRef=" + ref + "&status=SUCCESS"));

        JsonNode session = adminPayment(id);
        assertThat(session.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(session.get("gatewayTransactionId").asString()).startsWith("tx_");

        mvc.perform(get("/pay/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SUCCEEDED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<form"))));
    }

    @Test
    void completingTwiceIs409() throws Exception {
        String id = createPayment(newMerchantRef(), "4600000");
        mvc.perform(post("/pay/{id}/complete", id).param("result", "FAILED"))
                .andExpect(status().isSeeOther());

        mvc.perform(post("/pay/{id}/complete", id).param("result", "SUCCESS"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/payments/{id}/complete", id).param("result", "SUCCESS"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));
        assertThat(adminPayment(id).get("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void completingAnExpiredSessionIs409() throws Exception {
        String ref = newMerchantRef();
        MvcResult created = postPayment(paymentJson(ref, "4600000", 1));
        String id = read(created).get("gatewayPaymentId").asString();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> mvc
                .perform(get("/api/v1/payments/by-ref/{ref}", ref).header("X-Api-Key", API_KEY))
                .andExpect(jsonPath("$.status").value("EXPIRED")));

        mvc.perform(post("/pay/{id}/complete", id).param("result", "SUCCESS"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/payments/{id}/complete", id).param("result", "SUCCESS"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPIRED"));
        mvc.perform(get("/pay/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hết hạn")));
    }

    @Test
    void adminCompleteReturnsTheSessionAsJson() throws Exception {
        String ref = newMerchantRef();
        String id = createPayment(ref, "250000");

        JsonNode session = completeViaAdmin(id, "FAILED");
        assertThat(session.get("gatewayPaymentId").asString()).isEqualTo(id);
        assertThat(session.get("merchantRef").asString()).isEqualTo(ref);
        assertThat(session.get("status").asString()).isEqualTo("FAILED");
        assertThat(session.get("gatewayTransactionId").isNull()).isTrue();
        assertThat(session.get("webhookEventId").asString()).matches("evt_[0-9a-f]{32}");

        mvc.perform(post("/admin/payments/{id}/complete", createPayment(newMerchantRef(), "1")).param("result", "MAYBE"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/payments/{id}/complete", "mp_unknown").param("result", "SUCCESS"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/pay/{id}/complete", createPayment(newMerchantRef(), "1")).param("result", "MAYBE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminListFiltersByMerchantRef() throws Exception {
        String ref = newMerchantRef();
        String id = createPayment(ref, "1000");
        MvcResult result = mvc.perform(get("/admin/payments").param("merchantRef", ref))
                .andExpect(status().isOk())
                .andReturn();
        Set<String> ids = read(result).valueStream().map(n -> n.get("gatewayPaymentId").asString())
                .collect(Collectors.toSet());
        assertThat(ids).containsExactly(id);
    }
}
