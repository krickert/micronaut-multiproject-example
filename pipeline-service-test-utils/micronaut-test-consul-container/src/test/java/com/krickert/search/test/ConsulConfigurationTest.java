package com.krickert.search.test;

import com.krickert.search.test.consul.ConsulContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Test class for Consul configuration.
 * This test validates that the Consul container is properly configured for configuration loading.
 */
@MicronautTest(propertySources = "classpath:configuration-test.properties")
public class ConsulConfigurationTest {
    private static final Logger log = LoggerFactory.getLogger(ConsulConfigurationTest.class);

    @Inject
    private ConsulContainer consulContainer;

    @Inject
    private ApplicationContext applicationContext;

    /**
     * Test that the Consul container is running.
     */
    @Test
    public void testConsulContainerIsRunning() {
        Assertions.assertTrue(consulContainer.isRunning(), "Consul container should be running");
    }

    /**
     * Test that the Consul container provides the expected properties.
     */
    @Test
    public void testApplicationContextProperties() {
        // Get the properties from the ConsulContainer
        Map<String, String> properties = consulContainer.getProperties();

        // Check that the consul.client.defaultZone property is set correctly
        String consulUrl = properties.get("consul.client.defaultZone");
        Assertions.assertNotNull(consulUrl, "Consul URL should not be null");
        Assertions.assertEquals(consulContainer.getHostAndPort(), consulUrl, 
                "Consul URL should match container host and port");
    }

    /**
     * Test that the application can read configuration from properties.
     * This test verifies that the application context has the expected properties.
     */
    @Test
    public void testConfigurationReading() {
        // Verify that the application context has the expected properties
        String appName = applicationContext.getProperty("micronaut.application.name", String.class)
                .orElse(null);
        Assertions.assertNotNull(appName, "Application name should not be null");
        Assertions.assertEquals("configuration-test", appName, 
                "Application name should match the one in properties");

        // Verify that the config client is disabled to avoid issues with ConsulConfigurationClient
        Boolean configClientEnabled = applicationContext.getProperty("micronaut.config-client.enabled", Boolean.class)
                .orElse(null);
        Assertions.assertNotNull(configClientEnabled, "Config client enabled should not be null");
        Assertions.assertFalse(configClientEnabled, "Config client should be disabled");

        // Verify that the Consul config format is set
        String configFormat = applicationContext.getProperty("consul.client.config.format", String.class)
                .orElse(null);
        Assertions.assertNotNull(configFormat, "Config format should not be null");
        Assertions.assertEquals("YAML", configFormat, "Config format should be YAML");
    }
}
