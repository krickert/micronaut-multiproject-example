package com.krickert.search.test;

import com.krickert.search.test.consul.ConsulContainer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Test class for {@link ConsulContainer}.
 * This test validates that the Consul container starts correctly and provides the expected properties.
 */
@MicronautTest
public class ConsulContainerTest {

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
     * Test that the endpoint is not null or empty.
     */
    @Test
    public void testEndpointIsNotEmpty() {
        String endpoint = consulContainer.getEndpoint();
        Assertions.assertNotNull(endpoint, "Endpoint should not be null");
        Assertions.assertFalse(endpoint.isEmpty(), "Endpoint should not be empty");
        Assertions.assertTrue(endpoint.startsWith("http://"), "Endpoint should start with http://");
    }

    /**
     * Test that the host and port are not null or empty.
     */
    @Test
    public void testHostAndPortIsNotEmpty() {
        String hostAndPort = consulContainer.getHostAndPort();
        Assertions.assertNotNull(hostAndPort, "Host and port should not be null");
        Assertions.assertFalse(hostAndPort.isEmpty(), "Host and port should not be empty");
        Assertions.assertTrue(hostAndPort.contains(":"), "Host and port should contain a colon");
    }

    /**
     * Test that the properties returned by the TestPropertyProvider are correct.
     */
    @Test
    public void testProperties() {
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
        
        // Check Consul config format
        Assertions.assertEquals("YAML", properties.get("consul.client.config.format"), 
                "Consul config format should be YAML");
        
        // Check Consul config path
        Assertions.assertEquals("/config", properties.get("consul.client.config.path"), 
                "Consul config path should be '/config'");
        
        // Check Consul watch enabled
        Assertions.assertEquals("true", properties.get("consul.client.watch.enabled"), 
                "Consul watch should be enabled");
    }
}