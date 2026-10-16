package com.ticket2h.mockpay;

import com.ticket2h.mockpay.support.MockPayTestSupport;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyAuthTest extends MockPayTestSupport {

    @Test
    void createPaymentWithoutApiKeyIs401Json() throws Exception {
        mvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson(newMerchantRef(), "4600000")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Missing X-Api-Key header"));
    }

    @Test
    void wrongApiKeyIs401() throws Exception {
        mvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY + "-wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson(newMerchantRef(), "4600000")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid X-Api-Key"));

        mvc.perform(get("/api/v1/payments/by-ref/{ref}", newMerchantRef()).header("X-Api-Key", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyApiEndpointRequiresTheKey() throws Exception {
        mvc.perform(get("/api/v1/payments/by-ref/{ref}", newMerchantRef()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/refunds").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctApiKeyIsAccepted() throws Exception {
        mvc.perform(get("/api/v1/payments/by-ref/{ref}", newMerchantRef()).header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson(newMerchantRef(), "4600000")))
                .andExpect(status().isCreated());
    }

    @Test
    void adminAndPayPagesDoNotNeedTheKey() throws Exception {
        mvc.perform(get("/admin/chaos")).andExpect(status().isOk());
        mvc.perform(get("/admin/webhook-deliveries")).andExpect(status().isOk());
        mvc.perform(get("/pay/{id}", "mp_unknown")).andExpect(status().isNotFound());
    }
}
