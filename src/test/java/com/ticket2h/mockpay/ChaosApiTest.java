package com.ticket2h.mockpay;

import java.time.Duration;

import com.ticket2h.mockpay.support.MockPayTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChaosApiTest extends MockPayTestSupport {

    @Test
    void defaultsAreReturnedByGet() throws Exception {
        mvc.perform(get("/admin/chaos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookDelayMs").value(0))
                .andExpect(jsonPath("$.duplicateDeliveries").value(1))
                .andExpect(jsonPath("$.dropWebhook").value(false))
                .andExpect(jsonPath("$.webhookBeforeRedirect").value(false))
                .andExpect(jsonPath("$.failFirstDeliveries").value(0))
                .andExpect(jsonPath("$.refundFailureRate").value(0.0));
    }

    @Test
    void putAcceptsAPartialBodyAndMissingFieldsTakeDefaults() throws Exception {
        mvc.perform(put("/admin/chaos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"duplicateDeliveries\": 3, \"webhookDelayMs\": 30000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateDeliveries").value(3))
                .andExpect(jsonPath("$.webhookDelayMs").value(30000))
                .andExpect(jsonPath("$.dropWebhook").value(false));
        mvc.perform(get("/admin/chaos"))
                .andExpect(jsonPath("$.duplicateDeliveries").value(3))
                .andExpect(jsonPath("$.webhookDelayMs").value(30000));

        // PUT thay toàn bộ cấu hình: trường thiếu quay về mặc định, không gộp với lần trước
        mvc.perform(put("/admin/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"dropWebhook\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dropWebhook").value(true))
                .andExpect(jsonPath("$.duplicateDeliveries").value(1))
                .andExpect(jsonPath("$.webhookDelayMs").value(0));

        mvc.perform(put("/admin/chaos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dropWebhook").value(false));
    }

    @Test
    void deleteResetsToDefaults() throws Exception {
        putChaos("""
                {"webhookDelayMs": 5, "duplicateDeliveries": 2, "dropWebhook": true, "webhookBeforeRedirect": true,
                 "failFirstDeliveries": 4, "refundFailureRate": 0.25}
                """);
        mvc.perform(get("/admin/chaos"))
                .andExpect(jsonPath("$.failFirstDeliveries").value(4))
                .andExpect(jsonPath("$.refundFailureRate").value(0.25))
                .andExpect(jsonPath("$.webhookBeforeRedirect").value(true));

        mvc.perform(delete("/admin/chaos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateDeliveries").value(1));
        mvc.perform(get("/admin/chaos"))
                .andExpect(jsonPath("$.webhookDelayMs").value(0))
                .andExpect(jsonPath("$.duplicateDeliveries").value(1))
                .andExpect(jsonPath("$.dropWebhook").value(false))
                .andExpect(jsonPath("$.webhookBeforeRedirect").value(false))
                .andExpect(jsonPath("$.failFirstDeliveries").value(0))
                .andExpect(jsonPath("$.refundFailureRate").value(0.0));
    }

    @Test
    void invalidValuesAre400() throws Exception {
        for (String body : new String[] { "{\"duplicateDeliveries\": 0}", "{\"refundFailureRate\": 1.5}",
                "{\"webhookDelayMs\": -1}", "{\"failFirstDeliveries\": -2}", "{\"dropWebhook\": \"maybe\"}" }) {
            mvc.perform(put("/admin/chaos").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/admin/chaos")).andExpect(jsonPath("$.duplicateDeliveries").value(1));
    }

    @Test
    void chaosAppliesOnlyToSessionsCreatedAfterTheChange() throws Exception {
        String before = createPayment(newMerchantRef(), "1000");
        putChaos("{\"dropWebhook\": true}");
        String after = createPayment(newMerchantRef(), "1000");
        putChaos("{}");

        assertThat(adminPayment(before).get("chaos").get("dropWebhook").asBoolean()).isFalse();
        assertThat(adminPayment(after).get("chaos").get("dropWebhook").asBoolean()).isTrue();

        completeViaAdmin(after, "SUCCESS");
        completeViaAdmin(before, "SUCCESS");

        await().atMost(Duration.ofSeconds(5)).until(() -> receiver.received().size() == 1);
        JsonNode delivered = json.readTree(receiver.received().getFirst().bodyString());
        assertThat(delivered.get("gatewayPaymentId").asString()).isEqualTo(before);
        assertThat(deliveries(after).get(0).get("outcome").asString()).isEqualTo("DROPPED");
    }
}
