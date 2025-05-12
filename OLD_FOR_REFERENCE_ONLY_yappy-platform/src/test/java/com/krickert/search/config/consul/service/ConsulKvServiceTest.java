package com.krickert.search.config.consul.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
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
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(rebuildContext = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsulKvServiceTest {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvServiceTest.class);

    // Inside ConsulKvServiceTest class:
    @Inject KeyValueClient keyValueClient;

    @Inject
    private ConsulKvService consulKvService;

    // Helper method to get ModifyIndex reliably after a write
    private long getModifyIndex(String keyFullPath) {
        // Use consistent read to ensure we get the index after the write
        QueryOptions consistentQueryOptions = ImmutableQueryOptions.builder()
                .consistencyMode(ConsistencyMode.CONSISTENT).build();
        Optional<Value> valueOpt = keyValueClient.getValue(keyFullPath, consistentQueryOptions);
        assertTrue(valueOpt.isPresent(), "Key '" + keyFullPath + "' should exist to get ModifyIndex");
        return valueOpt.get().getModifyIndex(); // Use longValue()
    }

    @Test
    void testSavePipelineUpdateWithCas_Success() {
        String pipelineName = "cas-success-pipeline";
        long initialVersion = 1;
        LocalDateTime initialTimestamp = LocalDateTime.now().minusHours(1);
        long newVersion = 2;
        LocalDateTime newTimestamp = LocalDateTime.now();

        String versionKey = "pipeline.configs." + pipelineName + ".version";
        String lastUpdatedKey = "pipeline.configs." + pipelineName + ".lastUpdated";
        String versionKeyFullPath = consulKvService.getFullPath(versionKey);
        String lastUpdatedKeyFullPath = consulKvService.getFullPath(lastUpdatedKey);

        // --- Setup Initial State ---
        LOG.info("Setting up initial state for CAS success test...");
        // Use raw client for setup to easily get index
        assertTrue(keyValueClient.putValue(versionKeyFullPath, String.valueOf(initialVersion)), "Setup: Failed to put initial version");
        assertTrue(keyValueClient.putValue(lastUpdatedKeyFullPath, initialTimestamp.toString()), "Setup: Failed to put initial timestamp");

        // Get the ModifyIndex AFTER writing the version key
        long expectedIndex = getModifyIndex(versionKeyFullPath);
        assertTrue(expectedIndex > 0, "Expected ModifyIndex should be greater than 0");
        LOG.info("Setup complete. Expected ModifyIndex for '{}': {}", versionKeyFullPath, expectedIndex);

        // --- Execute the CAS Update ---
        LOG.info("Executing savePipelineUpdateWithCas with correct index...");
        StepVerifier.create(consulKvService.savePipelineUpdateWithCas(
                        pipelineName, newVersion, newTimestamp, expectedIndex, null)) // No other keys
                .expectNext(true) // Expect success
                .verifyComplete();

        // --- Verify Final State ---
        LOG.info("Verifying final state after successful CAS update...");
        QueryOptions consistentQuery = ImmutableQueryOptions.builder().consistencyMode(ConsistencyMode.CONSISTENT).build();

        // *** Corrected Verification using getValue + flatMap ***
        Optional<String> finalVersionOpt = keyValueClient.getValue(versionKeyFullPath, consistentQuery)
                .flatMap(Value::getValueAsString); // Use flatMap here
        assertTrue(finalVersionOpt.isPresent(), "Final version should be present");
        assertEquals(String.valueOf(newVersion), finalVersionOpt.get(), "Version should be updated");

        Optional<String> finalTimestampOpt = keyValueClient.getValue(lastUpdatedKeyFullPath, consistentQuery)
                .flatMap(Value::getValueAsString); // Use flatMap here
        assertTrue(finalTimestampOpt.isPresent(), "Final timestamp should be present");
        assertEquals(newTimestamp.toString(), finalTimestampOpt.get(), "Timestamp should be updated");

        LOG.info("CAS Success test passed.");
    }

    @Test
    void testSavePipelineUpdateWithCas_Failure_WrongIndex() {
        // ... setup code remains the same ...

        String pipelineName = "cas-fail-pipeline";
        long initialVersion = 10;
        LocalDateTime initialTimestamp = LocalDateTime.now().minusMinutes(30);
        long newVersion = 11;
        LocalDateTime newTimestamp = LocalDateTime.now();

        String versionKey = "pipeline.configs." + pipelineName + ".version";
        String lastUpdatedKey = "pipeline.configs." + pipelineName + ".lastUpdated";
        String versionKeyFullPath = consulKvService.getFullPath(versionKey);
        String lastUpdatedKeyFullPath = consulKvService.getFullPath(lastUpdatedKey);

        // --- Setup Initial State ---
        LOG.info("Setting up initial state for CAS failure test...");
        assertTrue(keyValueClient.putValue(versionKeyFullPath, String.valueOf(initialVersion)), "Setup: Failed to put initial version");
        assertTrue(keyValueClient.putValue(lastUpdatedKeyFullPath, initialTimestamp.toString()), "Setup: Failed to put initial timestamp");

        long actualIndex = getModifyIndex(versionKeyFullPath);
        assertTrue(actualIndex > 0, "Actual ModifyIndex should be greater than 0");
        long incorrectExpectedIndex = actualIndex + 5; // Simulate providing the wrong index
        LOG.info("Setup complete. Actual ModifyIndex: {}. Incorrect index to be used: {}", actualIndex, incorrectExpectedIndex);


        // --- Execute the CAS Update with wrong index ---
        LOG.info("Executing savePipelineUpdateWithCas with INCORRECT index...");
        StepVerifier.create(consulKvService.savePipelineUpdateWithCas(
                        pipelineName, newVersion, newTimestamp, incorrectExpectedIndex, Collections.emptyMap())) // Empty map for other keys
                .expectNext(false) // Expect failure (CAS check should fail)
                .verifyComplete();

        // --- Verify State Did NOT Change ---
        LOG.info("Verifying state did not change after failed CAS update...");
        QueryOptions consistentQuery = ImmutableQueryOptions.builder().consistencyMode(ConsistencyMode.CONSISTENT).build();

        // *** Corrected Verification using getValue + flatMap ***
        Optional<String> finalVersionOpt = keyValueClient.getValue(versionKeyFullPath, consistentQuery)
                .flatMap(Value::getValueAsString); // Use flatMap here
        assertTrue(finalVersionOpt.isPresent(), "Version should still be present");
        assertEquals(String.valueOf(initialVersion), finalVersionOpt.get(), "Version should NOT be updated");

        Optional<String> finalTimestampOpt = keyValueClient.getValue(lastUpdatedKeyFullPath, consistentQuery)
                .flatMap(Value::getValueAsString); // Use flatMap here
        assertTrue(finalTimestampOpt.isPresent(), "Timestamp should still be present");
        assertEquals(initialTimestamp.toString(), finalTimestampOpt.get(), "Timestamp should NOT be updated");

        LOG.info("CAS Failure test passed.");
    }

    @Test
    void testSavePipelineUpdateWithCas_Success_WithOtherKeys() {
        String pipelineName = "cas-success-other-pipeline";
        long initialVersion = 5;
        LocalDateTime initialTimestamp = LocalDateTime.now().minusDays(1);
        String otherKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".some.other.value");
        String initialOtherValue = "initial";
        long newVersion = 6;
        LocalDateTime newTimestamp = LocalDateTime.now();
        String newOtherValue = "updated";

        String versionKeyFullPath = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
        String lastUpdatedKeyFullPath = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");

        // --- Setup Initial State ---
        LOG.info("Setting up initial state for CAS success with other keys test...");
        assertTrue(keyValueClient.putValue(versionKeyFullPath, String.valueOf(initialVersion)));
        assertTrue(keyValueClient.putValue(lastUpdatedKeyFullPath, initialTimestamp.toString()));
        assertTrue(keyValueClient.putValue(otherKey, initialOtherValue));

        long expectedIndex = getModifyIndex(versionKeyFullPath);
        LOG.info("Setup complete. Expected ModifyIndex: {}", expectedIndex);

        // --- Execute the CAS Update ---
        Map<String, String> otherKeysMap = Collections.singletonMap(otherKey, newOtherValue);
        LOG.info("Executing savePipelineUpdateWithCas with correct index and other keys...");
        StepVerifier.create(consulKvService.savePipelineUpdateWithCas(
                        pipelineName, newVersion, newTimestamp, expectedIndex, otherKeysMap))
                .expectNext(true) // Expect success
                .verifyComplete();

        // --- Verify Final State ---
        LOG.info("Verifying final state after successful CAS update with other keys...");
        QueryOptions consistentQuery = ImmutableQueryOptions.builder().consistencyMode(ConsistencyMode.CONSISTENT).build();

        // *** Corrected Verification using getValue + flatMap ***
        assertEquals(String.valueOf(newVersion),
                keyValueClient.getValue(versionKeyFullPath, consistentQuery).flatMap(Value::getValueAsString).orElse(null), // Use flatMap
                "Version update failed");
        assertEquals(newTimestamp.toString(),
                keyValueClient.getValue(lastUpdatedKeyFullPath, consistentQuery).flatMap(Value::getValueAsString).orElse(null), // Use flatMap
                "Timestamp update failed");
        assertEquals(newOtherValue,
                keyValueClient.getValue(otherKey, consistentQuery).flatMap(Value::getValueAsString).orElse(null), // Use flatMap
                "Other key update failed");

        LOG.info("CAS Success with other keys test passed.");
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
            .expectNextMatches(optional -> optional.filter(value::equals).isPresent())
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
        String configPath = "config/pipeline";
        String key = "test-key";

        // When
        String fullPath = consulKvService.getFullPath(key);

        // Then
        assertEquals(configPath + "/" + key, fullPath, "Full path should be correctly constructed");
    }

    @Test
    public void testGetFullPathWithExistingPrefix() {
        // Given
        String configPath = "config/pipeline/config/test";
        String key = configPath + "/test-key";

        // When
        String fullPath = consulKvService.getFullPath(key);

        // Then
        assertEquals(key, fullPath, "Full path should not duplicate the prefix");
    }

    @Test
    public void testPutAndGetValueWithSpecialCharacters() {
        // Given
        String key = "config/pipeline/test/pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[0]";
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
                return optional.filter(value::equals).isPresent();
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
