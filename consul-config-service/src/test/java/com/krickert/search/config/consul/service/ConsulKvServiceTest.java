package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.model.kv.Value;
import org.kiwiproject.consul.option.ConsistencyMode;
import org.kiwiproject.consul.option.ImmutableQueryOptions;
import org.kiwiproject.consul.option.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(rebuildContext = true)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsulKvServiceTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvServiceTest.class);

    // Inside ConsulKvServiceTest class:
    @Inject KeyValueClient keyValueClient;

    @Inject
    private ConsulKvService consulKvService;

    @Override
    public Map<String, String> getProperties() {
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        LOG.info("Using shared Consul container");

        // Use centralized property management
        return container.getPropertiesWithTestConfigPath();
    }

    @Test
    public void testPutAndGetValue() {
        // Given
        String key = "test-key";
        String value = "test-value";
        String fullPath = consulKvService.getFullPath(key);

        // When & Then
        // First put the value
        StepVerifier.create(consulKvService.putValue(fullPath, value))
            .expectNext(true)
            .verifyComplete();

        // Then get the value and verify it
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(optional -> {
                if (optional.isPresent()) {
                    return value.equals(optional.get());
                }
                return false;
            })
            .verifyComplete();
    }

    @Test
    public void testDeleteKey() {
        // Given
        String key = "test-delete-key";
        String value = "test-value";
        String fullPath = consulKvService.getFullPath(key);

        // First put the value
        StepVerifier.create(consulKvService.putValue(fullPath, value))
            .expectNext(true)
            .verifyComplete();

        // When & Then
        // Delete the key
        StepVerifier.create(consulKvService.deleteKey(fullPath))
            .expectNext(true)
            .verifyComplete();

        // Verify the key is deleted
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(Optional::isEmpty)
            .verifyComplete();
    }

    @Test
    public void testGetNonExistentKey() {
        // Given
        String key = "non-existent-key";
        String fullPath = consulKvService.getFullPath(key);

        // When & Then
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(Optional::isEmpty)
            .verifyComplete();
    }

    @Test
    public void testGetFullPath() {
        // Given
        String configPath = "config/test";
        String key = "test-key";

        // When
        String fullPath = consulKvService.getFullPath(key);

        // Then
        assertEquals(configPath + "/" + key, fullPath, "Full path should be correctly constructed");
    }

    @Test
    public void testGetFullPathWithExistingPrefix() {
        // Given
        String configPath = "config/test";
        String key = configPath + "/test-key";

        // When
        String fullPath = consulKvService.getFullPath(key);

        // Then
        assertEquals(key, fullPath, "Full path should not duplicate the prefix");
    }

    @Test
    public void testPutAndGetValueWithSpecialCharacters() {
        // Given
        String key = "test/pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[0]";
        String value = "test-topic";
        String fullPath = consulKvService.getFullPath(key);

        // When & Then
        // First put the value
        StepVerifier.create(consulKvService.putValue(fullPath, value))
            .expectNext(true)
            .verifyComplete();

        // Then get the value and verify it
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(optional -> {
                if (optional.isPresent()) {
                    return value.equals(optional.get());
                }
                return false;
            })
            .verifyComplete();
    }

    @Test
    void testImmediateReadAfterWriteConsistency() {
        String key = "consistency-test-key";
        String value = "consistent-value";
        String fullPath = consulKvService.getFullPath(key);
        LOG.info("Testing immediate read-after-write for key: {}", fullPath);

        // 1. Put the value using the service
        LOG.info("Putting value...");
        StepVerifier.create(consulKvService.putValue(fullPath, value))
                .expectNext(true)
                .verifyComplete();
        LOG.info("Put complete.");

        // --- Verification ---
        // 2a. Try reading IMMEDIATELY using the service (default consistency)
        LOG.info("Reading immediately via service (default consistency)...");
        StepVerifier.create(consulKvService.getValue(fullPath))
                .expectNextMatches(opt -> {
                    LOG.info("Service getValue returned: {}", opt.orElse("empty"));
                    // This might still fail here if the issue is timing/eventual consistency
                    return opt.isPresent() && value.equals(opt.get());
                })
                .as("Immediate read via service")
                .expectComplete()
                // Use verify() with timeout for potentially failing async checks in tests
                .verify(Duration.ofSeconds(2));

        // 2b. Try reading IMMEDIATELY using the raw client with STRONG consistency
        LOG.info("Reading immediately via raw client (CONSISTENT)...");
        // *** Use ImmutableQueryOptions builder ***
        QueryOptions consistentQueryOptions = ImmutableQueryOptions.builder()
                .consistencyMode(ConsistencyMode.CONSISTENT)
                .build();
        // *** Call getValue which returns Optional<Value> and accepts QueryOptions ***
        Optional<Value> consistentReadRaw = keyValueClient.getValue(fullPath, consistentQueryOptions);
        // *** Extract the String value ***
        Optional<String> consistentReadOpt = consistentReadRaw.flatMap(Value::getValueAsString);

        LOG.info("Raw client CONSISTENT read returned: {}", consistentReadOpt.orElse("empty"));
        assertTrue(consistentReadOpt.isPresent() && value.equals(consistentReadOpt.get()),
                "Immediate CONSISTENT read via raw client should succeed. Got: " + consistentReadOpt.orElse("empty"));

        // 3. Optional: Add a small delay and read again via service
        try {
            LOG.info("Waiting 200ms...");
            Thread.sleep(200);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        LOG.info("Reading via service after delay...");
        StepVerifier.create(consulKvService.getValue(fullPath))
                .expectNextMatches(opt -> opt.isPresent() && value.equals(opt.get()))
                .as("Read via service after delay")
                .verifyComplete(); // verifyComplete() is fine if you expect it to pass reliably after delay
        LOG.info("Read via service after delay succeeded.");
    }
}
