package com.krickert.yappy.integration.container.consul;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.env.Environment;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that properties from the Consul Test Resource Provider are correctly
 * resolved and injected into the test's Application Context.
 */
@MicronautTest(startApplication = false)
class ConsulTestResourceProviderPropertyInjectionTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestResourceProviderPropertyInjectionTest.class);

    @Inject
    Environment environment;

    @Test
    @DisplayName("Should inject resolved Consul host and port")
    void testConsulClientPropertiesInjected() {
        // In the test JVM, the host should be resolved to 'localhost' or a similar address.
        String resolvedHost = environment.getProperty("consul.client.host", String.class).orElse(null);
        assertNotNull(resolvedHost, "Resolved Consul host should not be null");
        assertNotEquals("consul", resolvedHost, "Host should be resolved, not the alias 'consul'");
        LOG.info("Resolved consul.client.host: {}", resolvedHost);

        // The port should be a dynamic, high-number port, not the internal 8500.
        Integer resolvedPort = environment.getProperty("consul.client.port", Integer.class).orElse(null);
        assertNotNull(resolvedPort, "Resolved Consul port should not be null");
        assertNotEquals(8500, resolvedPort, "Port should be a dynamic mapped port, not 8500");
        LOG.info("Resolved consul.client.port: {}", resolvedPort);
    }

    @Test
    @DisplayName("Should resolve discovery and registration properties consistently")
    void testDiscoveryPropertiesAreConsistent() {
        String discoveryHost = environment.getProperty("consul.client.discovery.host", String.class).orElse(null);
        String registrationHost = environment.getProperty("consul.client.registration.host", String.class).orElse(null);

        assertNotNull(discoveryHost);
        LOG.info("Resolved discovery host: {}", discoveryHost);

        // After resolution, the discovery, registration, and client hosts should all point to the same host.
        assertEquals(discoveryHost, registrationHost, "Discovery and registration hosts should match");

        Integer discoveryPort = environment.getProperty("consul.client.discovery.port", Integer.class).orElse(null);
        Integer registrationPort = environment.getProperty("consul.client.registration.port", Integer.class).orElse(null);

        assertNotNull(discoveryPort);
        LOG.info("Resolved discovery port: {}", discoveryPort);

        // After resolution, ports should also match.
        assertEquals(discoveryPort, registrationPort, "Discovery and registration ports should match");
    }

    @Test
    @DisplayName("Should inject a valid default-zone")
    void testConsulDefaultZoneInjected() {
        String defaultZone = environment.getProperty("consul.client.default-zone", String.class).orElse(null);
        assertNotNull(defaultZone, "Default zone should be injected");

        // The default zone should be a valid host:port string.
        assertTrue(defaultZone.contains(":"), "Default zone should contain a port separator");
        LOG.info("Resolved consul.client.default-zone: {}", defaultZone);
    }
}