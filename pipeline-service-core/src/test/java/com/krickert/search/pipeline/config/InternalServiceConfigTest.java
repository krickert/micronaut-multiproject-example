package com.krickert.search.pipeline.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(
    propertySources = "classpath:config-test.properties",
    environments = "test",
    transactional = false,
    rebuildContext = true
)
public class InternalServiceConfigTest {

    @Inject
    private InternalServiceConfig internalServiceConfig;

    @Test
    void testInternalServiceConfigInjection() {
        // Verify that the internal service config is injected and properties are loaded correctly
        assertNotNull(internalServiceConfig);
        assertEquals("test-service", internalServiceConfig.getPipelineServiceName());
    }

    @Test
    void testLoadConfigFromContext() {
        // Create a new application context with the test properties
        Map<String, Object> properties = new HashMap<>();
        properties.put("micronaut.config.files", "classpath:config-test.properties");
        properties.put("kafka.enabled", "false");
        properties.put("pipeline.name", "test-pipeline");
        properties.put("pipeline.pipelineServiceName", "test-service");

        ApplicationContext context = ApplicationContext.run(properties);

        try {
            // Get the InternalServiceConfig bean from the context
            InternalServiceConfig config = context.getBean(InternalServiceConfig.class);

            // Verify that the properties are loaded correctly
            assertNotNull(config);
            assertEquals("test-service", config.getPipelineServiceName());
        } finally {
            // Close the context to release resources
            context.close();
        }
    }
}
