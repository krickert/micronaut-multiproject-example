package com.krickert.search.test;

import com.krickert.search.test.consul.ConsulContainer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Test class for Consul service discovery.
 * This test validates that the Consul container is properly configured.
 */
@MicronautTest(propertySources = "classpath:service-discovery-test.properties")
public class ConsulServiceDiscoveryTest {

    @Inject
    private ConsulContainer consulContainer;

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
     * Test that the Consul container provides the expected properties.
     */
    @Test
    public void testConsulContainerProperties() {
        Map<String, String> properties = consulContainer.getProperties();

        // Check application name
        Assertions.assertEquals("my-app", properties.get("micronaut.application.name"), 
                "Application name should be 'my-app'");

        // Check config client enabled
        Assertions.assertEquals("true", properties.get("micronaut.config-client.enabled"), 
                "Config client should be enabled");

        // Check Consul client default zone
        Assertions.assertEquals(consulContainer.getHostAndPort(), properties.get("consul.client.defaultZone"), 
                "Consul client default zone should match host and port");
    }
}
