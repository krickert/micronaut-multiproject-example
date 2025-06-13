package com.krickert.search.orchestrator.kafka.admin.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
class KafkaAdminServiceConfigTest {

    @Inject
    KafkaAdminServiceConfig config;

    @Inject
    ApplicationContext applicationContext;

    @Test
    @DisplayName("Should load default configuration values")
    void testDefaultConfigValues() {
        assertNotNull(config, "KafkaAdminServiceConfig should be injected");
        assertEquals(Duration.ofSeconds(60), config.getRequestTimeout(), "Default request timeout should be 60 seconds");
        assertEquals(Duration.ofSeconds(120), config.getRecreatePollTimeout(), "Default recreate poll timeout should be 120 seconds");
        assertEquals(Duration.ofSeconds(5), config.getRecreatePollInterval(), "Default recreate poll interval should be 5 seconds");
    }

    @Test
    @DisplayName("Should override configuration values from properties")
    void testOverrideConfigValues() {
        // Create a new application context with custom properties
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.admin.service.request-timeout", "30s");
        properties.put("kafka.admin.service.recreate-poll-timeout", "60s");
        properties.put("kafka.admin.service.recreate-poll-interval", "2s");

        try (ApplicationContext customContext = ApplicationContext.run(properties)) {
            KafkaAdminServiceConfig customConfig = customContext.getBean(KafkaAdminServiceConfig.class);
            
            assertNotNull(customConfig, "Custom KafkaAdminServiceConfig should be created");
            assertEquals(Duration.ofSeconds(30), customConfig.getRequestTimeout(), "Custom request timeout should be 30 seconds");
            assertEquals(Duration.ofSeconds(60), customConfig.getRecreatePollTimeout(), "Custom recreate poll timeout should be 60 seconds");
            assertEquals(Duration.ofSeconds(2), customConfig.getRecreatePollInterval(), "Custom recreate poll interval should be 2 seconds");
        }
    }

    @Test
    @DisplayName("Should be able to set configuration values programmatically")
    void testSetConfigValues() {
        KafkaAdminServiceConfig customConfig = new KafkaAdminServiceConfig();
        
        // Set custom values
        customConfig.setRequestTimeout(Duration.ofSeconds(45));
        customConfig.setRecreatePollTimeout(Duration.ofSeconds(90));
        customConfig.setRecreatePollInterval(Duration.ofSeconds(3));
        
        // Verify values were set correctly
        assertEquals(Duration.ofSeconds(45), customConfig.getRequestTimeout(), "Custom request timeout should be 45 seconds");
        assertEquals(Duration.ofSeconds(90), customConfig.getRecreatePollTimeout(), "Custom recreate poll timeout should be 90 seconds");
        assertEquals(Duration.ofSeconds(3), customConfig.getRecreatePollInterval(), "Custom recreate poll interval should be 3 seconds");
    }
}