package com.krickert.search.config.consul.integration;

import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating usage with Micronaut Test Resources for Consul.
 * Ensure consul.client.config.enabled=true or consul.client.registration.enabled=true
 * is set in application-test.yml to trigger the test resource.
 */
@MicronautTest(environments = "test")
class ConsulIntegrationTest implements TestPropertyProvider {

    public static final Logger LOG = LoggerFactory.getLogger(ConsulIntegrationTest.class);
    @Value("${consul.client.host}")
    String consulHost;

    @Value("${consul.client.port}")
    int consulPort;

    @Inject
    ConsulKvService consulKvService; // Your service using org.kiwiproject.consul.KeyValueClient

    @Test
    void testConsulKvInteraction() {
        // Print the Consul host and port
        LOG.info("[DEBUG_LOG] Consul host: " + consulHost);
        LOG.info("[DEBUG_LOG] Consul port: " + consulPort);

        // Test interaction with KV store, potentially checking values
        // pre-populated via test-resources.yml

        // Add a test key-value pair first to ensure Consul is working
        String initialTestKey = "test/key";
        String initialTestValue = "Test Value";

        LOG.info("[DEBUG_LOG] Adding test key-value pair: " + initialTestKey + "=" + initialTestValue);
        Mono<Boolean> initialPutMono = consulKvService.putValue(initialTestKey, initialTestValue);

        StepVerifier.create(initialPutMono)
                .expectNext(true)
                .verifyComplete();

        LOG.info("[DEBUG_LOG] Successfully added test key-value pair");

        // Now try to read it back
        Mono<Optional<String>> getTestMono = consulKvService.getValue(initialTestKey);

        StepVerifier.create(getTestMono)
                .assertNext(valueOpt -> {
                    System.out.println("[DEBUG_LOG] Retrieved test value: " + valueOpt);
                    assertTrue(valueOpt.isPresent(), "Value should be present for test key: " + initialTestKey);
                    assertEquals(initialTestValue, valueOpt.get(), "Read value should match written value");
                })
                .verifyComplete();

        LOG.info("[DEBUG_LOG] Successfully retrieved test key-value pair");

        // Pre-populate the KV store with the values that should have been set by test-resources.yml
        String predefinedKey = "my/predefined/key";
        String predefinedValue = "Initial Value";

        LOG.info("[DEBUG_LOG] Pre-populating predefined key: " + predefinedKey + "=" + predefinedValue);
        Mono<Boolean> predefinedPutMono = consulKvService.putValue(predefinedKey, predefinedValue);

        StepVerifier.create(predefinedPutMono)
                .expectNext(true)
                .verifyComplete();

        LOG.info("[DEBUG_LOG] Successfully pre-populated predefined key");

        // Example: Check pre-populated value
        LOG.info("[DEBUG_LOG] Checking predefined key: " + predefinedKey);
        Mono<Optional<String>> getPredefinedMono = consulKvService.getValue(predefinedKey);

        StepVerifier.create(getPredefinedMono)
                .assertNext(valueOpt -> {
                    assertTrue(valueOpt.isPresent(), "Value should be present for predefined key: " + predefinedKey);
                    // Remember the value is stored as a string, even if it looks like JSON
                    // Kiwi client's getValueAsString decodes Base64 by default, adjust if needed based on client.
                    // The provider doesn't Base64 encode when using kv-properties.
                    assertEquals("Initial Value", valueOpt.get(), "Read value should match predefined value");
                })
                .verifyComplete();


        // --- Example: Write a new value ---
        String testKey = "my/dynamic/test/resource";
        String testValue = "Hello from Testcontainers Consul!";

        // The ConsulKvService seems to handle encoding internally based on the Kiwi client.
        // If putting raw strings, ensure the service or client handles potential encoding needs.
        // Assuming putValue handles any necessary encoding (like Base64 if required by raw HTTP API)
        Mono<Boolean> putMono = consulKvService.putValue(testKey, testValue);

        StepVerifier.create(putMono)
                .expectNext(true) // Assuming putValue returns true on success
                .verifyComplete();

        // --- Read the value back ---
        Mono<Optional<String>> getMono = consulKvService.getValue(testKey);

        StepVerifier.create(getMono)
                .assertNext(valueOpt -> {
                    assertTrue(valueOpt.isPresent(), "Value should be present for key: " + testKey);
                    // Assuming getValueAsString decodes if necessary
                    assertEquals(testValue, valueOpt.get(), "Read value should match written value");
                })
                .verifyComplete();

        // --- List keys (optional check) ---
        Mono<List<String>> keysMono = consulKvService.getKeysWithPrefix("my/"); // Use prefix matching your keys

        StepVerifier.create(keysMono)
                .assertNext(keys -> {
                    assertNotNull(keys);
                    assertTrue(keys.contains(testKey), "List of keys should contain the test key");
                    assertTrue(keys.contains(predefinedKey), "List of keys should contain the predefined key");
                })
                .verifyComplete();

        // --- Delete the value ---
        Mono<Boolean> deleteMono = consulKvService.deleteKey(testKey);

        StepVerifier.create(deleteMono)
                .expectNext(true) // Or check based on Consul's delete behavior
                .verifyComplete();

        // --- Verify deletion ---
        Mono<Optional<String>> getAfterDeleteMono = consulKvService.getValue(testKey);

        StepVerifier.create(getAfterDeleteMono)
                .assertNext(valueOpt -> {
                    assertFalse(valueOpt.isPresent(), "Value should be deleted");
                })
                .verifyComplete();
    }

    /**
     * Allows dynamically providing properties for a test.
     *
     * @return A map of properties
     */
    @Override
    public @NonNull Map<String, String> getProperties() {
        return Map.of("grpc.server.enabled", "false");
    }
}
