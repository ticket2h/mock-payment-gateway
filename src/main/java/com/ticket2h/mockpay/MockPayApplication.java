package com.ticket2h.mockpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MockPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPayApplication.class, args);
    }
}
