package com.ticket2h.mockpay.chaos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Body của {@code PUT /admin/chaos}: trường nào thiếu (null) nhận giá trị mặc định. */
public record ChaosUpdateRequest(
        @Min(0) @Max(600_000) Long webhookDelayMs,
        @Min(1) @Max(20) Integer duplicateDeliveries,
        Boolean dropWebhook,
        Boolean webhookBeforeRedirect,
        @Min(0) @Max(100) Integer failFirstDeliveries,
        @DecimalMin("0.0") @DecimalMax("1.0") Double refundFailureRate) {

    public ChaosConfig toConfig() {
        ChaosConfig d = ChaosConfig.DEFAULT;
        return new ChaosConfig(
                webhookDelayMs != null ? webhookDelayMs : d.webhookDelayMs(),
                duplicateDeliveries != null ? duplicateDeliveries : d.duplicateDeliveries(),
                dropWebhook != null ? dropWebhook : d.dropWebhook(),
                webhookBeforeRedirect != null ? webhookBeforeRedirect : d.webhookBeforeRedirect(),
                failFirstDeliveries != null ? failFirstDeliveries : d.failFirstDeliveries(),
                refundFailureRate != null ? refundFailureRate : d.refundFailureRate());
    }
}
