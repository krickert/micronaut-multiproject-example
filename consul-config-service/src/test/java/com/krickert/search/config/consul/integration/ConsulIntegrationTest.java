package com.krickert.search.config.consul.integration;

import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Consul configuration.
 * This test verifies that the application starts correctly with Consul enabled
 * and loads properties from Consul.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsulIntegrationTest implements TestPropertyProvider {

    @Inject
    private ConsulKvService consulKvService;

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private EmbeddedServer embeddedServer;

    @Inject
    private Environment environment;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("grpc.server.enabled", "false");
        // Enable Consul client and config client
        properties.put("consul.client.enabled", "true");
        properties.put("micronaut.config-client.enabled", "true");

        // Configure Consul for configuration
        properties.put("consul.client.config.enabled", "true");
        properties.put("consul.client.config.format", "YAML");
        properties.put("consul.client.config.path", "config/test");

        // Enable data seeding
        properties.put("consul.data.seeding.enabled", "true");
        properties.put("consul.data.seeding.file", "seed-data.yaml");

        return properties;
    }

    @BeforeAll
    void setUp() {
        // Seed some test data into Consul
        String testKey = "test-integration-key";
        String testValue = "test-integration-value";

        // Put the test value into Consul
        StepVerifier.create(consulKvService.putValue(consulKvService.getFullPath(testKey), testValue))
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void testApplicationStartsWithConsulEnabled() {
        // Verify that the application context is running
        assertTrue(applicationContext.isRunning(), "AdminApplication context should be running");

        // Verify that the embedded server is running
        assertTrue(embeddedServer.isRunning(), "Embedded server should be running");

        // Verify that Consul client is enabled
        assertTrue(environment.getProperty("consul.client.enabled", Boolean.class).orElse(false), 
                "Consul client should be enabled");

        // Verify that Consul config client is enabled
        assertTrue(environment.getProperty("consul.client.config.enabled", Boolean.class).orElse(false), 
                "Consul config client should be enabled");
    }

    @Test
    void testConsulPropertyLoading() {
        // Test key and value
        String testKey = "test-integration-key";
        String expectedValue = "test-integration-value";

        // Get the value from Consul
        StepVerifier.create(consulKvService.getValue(consulKvService.getFullPath(testKey)))
            .expectNextMatches(optional -> {
                if (optional.isPresent()) {
                    return expectedValue.equals(optional.get());
                }
                return false;
            })
            .verifyComplete();
    }

    @Test
    void testConsulPropertyWriting() {
        // Test key and value
        String testKey = "test-integration-write-key";
        String testValue = "test-integration-write-value";

        // Put the value into Consul
        StepVerifier.create(consulKvService.putValue(consulKvService.getFullPath(testKey), testValue))
            .expectNext(true)
            .verifyComplete();

        // Get the value from Consul to verify it was written
        StepVerifier.create(consulKvService.getValue(consulKvService.getFullPath(testKey)))
            .expectNextMatches(optional -> {
                if (optional.isPresent()) {
                    return testValue.equals(optional.get());
                }
                return false;
            })
            .verifyComplete();
    }

    @Test
    void testConsulPropertyUpdating() {
        // Test key and values
        String testKey = "test-integration-update-key";
        String initialValue = "initial-value";
        String updatedValue = "updated-value";

        // Put the initial value into Consul
        StepVerifier.create(consulKvService.putValue(consulKvService.getFullPath(testKey), initialValue))
            .expectNext(true)
            .verifyComplete();

        // Update the value in Consul
        StepVerifier.create(consulKvService.putValue(consulKvService.getFullPath(testKey), updatedValue))
            .expectNext(true)
            .verifyComplete();

        // Get the value from Consul to verify it was updated
        StepVerifier.create(consulKvService.getValue(consulKvService.getFullPath(testKey)))
            .expectNextMatches(optional -> {
                if (optional.isPresent()) {
                    return updatedValue.equals(optional.get());
                }
                return false;
            })
            .verifyComplete();
    }
}
