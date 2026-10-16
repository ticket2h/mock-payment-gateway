package com.ticket2h.mockpay.config;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

import com.ticket2h.mockpay.chaos.ChaosRandom;
import com.ticket2h.mockpay.webhook.WebhookSigner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MockPayConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ChaosRandom chaosRandom() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }

    @Bean
    WebhookSigner webhookSigner(MockPayProperties properties) {
        return new WebhookSigner(properties.webhookSecret());
    }
}
