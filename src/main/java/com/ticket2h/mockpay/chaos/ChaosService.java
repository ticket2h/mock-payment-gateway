package com.ticket2h.mockpay.chaos;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

@Service
public class ChaosService {

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);

    private final AtomicReference<ChaosConfig> current = new AtomicReference<>(ChaosConfig.DEFAULT);

    public ChaosConfig current() {
        return current.get();
    }

    public ChaosConfig update(ChaosConfig config) {
        current.set(config);
        log.info("Chaos config updated: {}", config);
        return config;
    }

    public ChaosConfig reset() {
        return update(ChaosConfig.DEFAULT);
    }
}
