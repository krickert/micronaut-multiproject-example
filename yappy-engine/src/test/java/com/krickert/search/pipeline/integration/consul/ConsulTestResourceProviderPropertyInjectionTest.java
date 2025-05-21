package com.krickert.search.pipeline.integration.consul;

import com.krickert.testcontainers.consul.ConsulTestResourceProvider;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.env.Environment;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that properties resolved by ConsulTestResourceProvider
 * are correctly injected into the Micronaut application context when Testcontainers
 * for Consul is active.
 */
@MicronautTest(startApplication = false, environments = {"test"}) // We don't need the full app, just property resolution
// You can also use your application.properties or a test-specific one
// @Property(name = "micronaut.config-client.enabled", value = "false") // Good to keep this off
class ConsulTestResourceProviderPropertyInjectionTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestResourceProviderPropertyInjectionTest.class);

    // Inject Micronaut's Environment to access all properties
    @Inject
    Environment environment;

    // You can also try to inject specific properties if you want to test their direct injection
    // For example, if these are used by some bean:
    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_CLIENT_HOST)
    String consulClientHost;

    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_CLIENT_PORT)
    String consulClientPort;

    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_CLIENT_DEFAULT_ZONE)
    String consulDefaultZone;

    // Add more for discovery and registration if needed for your test scope
    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_DISCOVERY_HOST)
    String consulDiscoveryHost;

    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_DISCOVERY_PORT)
    String consulDiscoveryPort;


    @Test
    @DisplayName("Should inject Consul client host resolved by TestResources")
    void testConsulClientHostInjected() {
        assertNotNull(consulClientHost, "Consul client host should be injected");
        assertFalse(consulClientHost.isBlank(), "Consul client host should not be blank");
        LOG.info("Injected consul.client.host: {}", consulClientHost);
        // You can't easily assert the *exact value* without knowing the dynamic Testcontainers IP,
        // but not null/blank is a good start.
    }

    @Test
    @DisplayName("Should inject Consul client port resolved by TestResources")
    void testConsulClientPortInjected() {
        assertNotNull(consulClientPort, "Consul client port should be injected");
        assertFalse(consulClientPort.isBlank(), "Consul client port should not be blank");
        try {
            Integer.parseInt(consulClientPort); // Check if it's a number
        } catch (NumberFormatException e) {
            fail("Consul client port is not a valid number: " + consulClientPort);
        }
        LOG.info("Injected consul.client.port: {}", consulClientPort);
    }

    @Test
    @DisplayName("Should inject Consul client default-zone resolved by TestResources")
    void testConsulDefaultZoneInjected() {
        assertNotNull(consulDefaultZone, "Consul default zone should be injected");
        assertFalse(consulDefaultZone.isBlank(), "Consul default zone should not be blank");
        assertTrue(consulDefaultZone.contains(consulClientHost), "Default zone should contain host");
        assertTrue(consulDefaultZone.contains(consulClientPort), "Default zone should contain port");
        LOG.info("Injected consul.client.default-zone: {}", consulDefaultZone);
    }

    @Test
    @DisplayName("Should inject Consul discovery host resolved by TestResources")
    void testConsulDiscoveryHostInjected() {
        assertNotNull(consulDiscoveryHost, "Consul discovery host should be injected");
        assertEquals(consulClientHost, consulDiscoveryHost, "Discovery host should match client host");
        LOG.info("Injected consul.client.discovery.host: {}", consulDiscoveryHost);
    }

    @Test
    @DisplayName("Should inject Consul discovery port resolved by TestResources")
    void testConsulDiscoveryPortInjected() {
        assertNotNull(consulDiscoveryPort, "Consul discovery port should be injected");
        assertEquals(consulClientPort, consulDiscoveryPort, "Discovery port should match client port");
        LOG.info("Injected consul.client.discovery.port: {}", consulDiscoveryPort);
    }


    @Test
    @DisplayName("Verify all resolvable properties are present in Micronaut Environment")
    void testAllResolvablePropertiesInEnvironment() {
        for (String propertyName : ConsulTestResourceProvider.RESOLVABLE_PROPERTIES_LIST) {
            assertTrue(environment.containsProperty(propertyName), "Micronaut environment should contain property: " + propertyName);
            Optional<String> propertyValue = environment.getProperty(propertyName, String.class);
            assertTrue(propertyValue.isPresent(), "Property value should be present for: " + propertyName);
            assertFalse(propertyValue.get().isBlank(), "Property value should not be blank for: " + propertyName);
            LOG.info("Environment property {} = {}", propertyName, propertyValue.get());
        }
    }

    // You could add a test for HASHICORP_CONSUL_KV_PROPERTIES_KEY if you have
    // KV pairs defined in your test properties (e.g., micronaut-test.properties)
    // and want to verify they were set up in the Testcontainer Consul by your provider.
    // This would require using a direct Consul client in the test to read back those KVs.
    // For example:
    // @Inject
    // Consul directConsulClientForTestSetup; // Injected via Test Resources
    //
    // @Test
    // void testKvPropertiesApplied() {
    //     KeyValueClient kv = directConsulClientForTestSetup.keyValueClient();
    //     Optional<String> val = kv.getValueAsString("my/test/key/from/properties");
    //     assertTrue(val.isPresent());
    //     assertEquals("myvalue", val.get());
    // }
}
