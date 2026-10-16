package com.ticket2h.mockpay.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nền chung cho test tích hợp: một Spring context dùng chung (key/secret riêng cho test, lịch thử lại
 * webhook rút ngắn còn 50ms, 100ms, ...), một merchant giả nhận webhook cho mỗi test, chaos được reset
 * trước và sau mỗi test.
 */
@SpringBootTest(properties = {
        "mockpay.api-key=" + MockPayTestSupport.API_KEY,
        "mockpay.webhook-secret=" + MockPayTestSupport.WEBHOOK_SECRET,
        "mockpay.public-base-url=http://mockpay.test/",
        "mockpay.webhook.retry-base-delay=50ms",
        "mockpay.webhook.timeout=1s",
        "mockpay.webhook.connect-timeout=500ms" })
@AutoConfigureMockMvc
public abstract class MockPayTestSupport {

    public static final String API_KEY = "test-api-key-3f9b";
    public static final String WEBHOOK_SECRET = "test-webhook-secret-81c2";
    public static final String RETURN_URL = "http://localhost:8080/payments/return";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    protected WebhookReceiver receiver;

    @BeforeEach
    void startReceiverAndResetChaos() throws Exception {
        receiver = WebhookReceiver.start(WEBHOOK_SECRET);
        mvc.perform(delete("/admin/chaos")).andExpect(status().isOk());
    }

    @AfterEach
    void stopReceiverAndResetChaos() throws Exception {
        receiver.close();
        mvc.perform(delete("/admin/chaos")).andExpect(status().isOk());
    }

    protected static String newMerchantRef() {
        return UUID.randomUUID().toString();
    }

    protected String paymentJson(String merchantRef, String amount) {
        return paymentJson(merchantRef, amount, 900);
    }

    protected String paymentJson(String merchantRef, String amount, int expiresInSeconds) {
        return """
                {
                  "merchantRef": "%s",
                  "amount": %s,
                  "currency": "VND",
                  "description": "Ticket2h order %s",
                  "returnUrl": "%s",
                  "notifyUrl": "%s",
                  "expiresInSeconds": %d
                }
                """.formatted(merchantRef, amount, merchantRef, RETURN_URL, receiver.url(), expiresInSeconds);
    }

    protected MvcResult postPayment(String body) throws Exception {
        return mvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn();
    }

    /** Tạo phiên mới và trả về {@code gatewayPaymentId}. */
    protected String createPayment(String merchantRef, String amount) throws Exception {
        MvcResult result = postPayment(paymentJson(merchantRef, amount));
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("create payment failed: " + result.getResponse().getStatus() + " "
                    + body(result));
        }
        return read(result).get("gatewayPaymentId").asString();
    }

    protected JsonNode completeViaAdmin(String gatewayPaymentId, String result) throws Exception {
        MvcResult response = mvc.perform(post("/admin/payments/{id}/complete", gatewayPaymentId)
                .param("result", result))
                .andExpect(status().isOk())
                .andReturn();
        return read(response);
    }

    protected void putChaos(String body) throws Exception {
        mvc.perform(put("/admin/chaos").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    protected JsonNode adminPayment(String gatewayPaymentId) throws Exception {
        return read(mvc.perform(get("/admin/payments/{id}", gatewayPaymentId))
                .andExpect(status().isOk())
                .andReturn());
    }

    protected JsonNode deliveries(String gatewayPaymentId) throws Exception {
        return read(mvc.perform(get("/admin/webhook-deliveries").param("gatewayPaymentId", gatewayPaymentId))
                .andExpect(status().isOk())
                .andReturn());
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return json.readTree(body(result));
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
