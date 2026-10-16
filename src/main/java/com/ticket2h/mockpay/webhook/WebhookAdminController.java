package com.ticket2h.mockpay.webhook;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/webhook-deliveries")
class WebhookAdminController {

    private final WebhookDeliveryLog deliveryLog;

    WebhookAdminController(WebhookDeliveryLog deliveryLog) {
        this.deliveryLog = deliveryLog;
    }

    @GetMapping
    List<DeliveryAttempt> list(@RequestParam(required = false) String gatewayPaymentId,
            @RequestParam(required = false) String eventId) {
        return deliveryLog.find(gatewayPaymentId, eventId);
    }
}
