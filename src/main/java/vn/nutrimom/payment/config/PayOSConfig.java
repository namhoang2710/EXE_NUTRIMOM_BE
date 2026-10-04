package vn.nutrimom.payment.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.payos.PayOS;

@Configuration
@EnableConfigurationProperties(PayOSProperties.class)
public class PayOSConfig {

    private static final Logger log = LoggerFactory.getLogger(PayOSConfig.class);

    @Bean
    public PayOS payOS(PayOSProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("PayOS credentials are not fully configured. Payment link generation will run in mock/test mode. " +
                    "Set PAYOS_CLIENT_ID, PAYOS_API_KEY, and PAYOS_CHECKSUM_KEY in .env to enable real PayOS gateway.");
            return new PayOS("mock-client-id", "mock-api-key", "mock-checksum-key");
        }
        log.info("PayOS client initialized with client-id: {}", properties.getClientId());
        return new PayOS(properties.getClientId(), properties.getApiKey(), properties.getChecksumKey());
    }
}
