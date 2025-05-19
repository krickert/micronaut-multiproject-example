package com.krickert.search.config.consul;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryCachedConfigHolderTest {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryCachedConfigHolderTest.class);

    private InMemoryCachedConfigHolder cachedConfigHolder;

    // Helper to create a minimal valid PipelineClusterConfig
    private PipelineClusterConfig createMinimalClusterConfig(String clusterName) {
        return new PipelineClusterConfig(
                clusterName,
                new PipelineGraphConfig(Collections.emptyMap()), // Minimal graph
                new PipelineModuleMap(Collections.emptyMap()),   // Minimal modules
                null, // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet()  // allowedGrpcServices
        );
    }

    @BeforeEach
    void setUp() {
        cachedConfigHolder = new InMemoryCachedConfigHolder();
    }

    @Test
    void initialState_isEmpty() {
        assertTrue(cachedConfigHolder.getCurrentConfig().isEmpty(), "Initial config should be empty.");
        assertTrue(cachedConfigHolder.getSchemaContent(new SchemaReference("any-subject", 1)).isEmpty(), "Initial schema content should be empty.");
    }

    @Test
    void updateConfiguration_withValidConfig_storesAndReturnsConfigAndSchemas() {
        PipelineClusterConfig config1 = createMinimalClusterConfig("cluster1");
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        String schemaContent1 = "{\"type\":\"string\"}";
        Map<SchemaReference, String> schemas1 = Collections.singletonMap(ref1, schemaContent1);

        cachedConfigHolder.updateConfiguration(config1, schemas1);

        Optional<PipelineClusterConfig> currentConfigOpt = cachedConfigHolder.getCurrentConfig();
        assertTrue(currentConfigOpt.isPresent(), "Config should be present after update.");
        assertEquals(config1, currentConfigOpt.get(), "Stored config should match the updated one.");
        assertEquals(config1.clusterName(), currentConfigOpt.get().clusterName());


        Optional<String> schemaOpt = cachedConfigHolder.getSchemaContent(ref1);
        assertTrue(schemaOpt.isPresent(), "Schema content should be present after update.");
        assertEquals(schemaContent1, schemaOpt.get(), "Stored schema content should match.");

        // Test updating with a new config
        PipelineClusterConfig config2 = createMinimalClusterConfig("cluster2");
        SchemaReference ref2 = new SchemaReference("subject2", 2);
        String schemaContent2 = "{\"type\":\"integer\"}";
        Map<SchemaReference, String> schemas2 = new HashMap<>();
        schemas2.put(ref1, schemaContent1); // Keep old schema
        schemas2.put(ref2, schemaContent2); // Add new schema


        cachedConfigHolder.updateConfiguration(config2, schemas2);

        Optional<PipelineClusterConfig> currentConfigOpt2 = cachedConfigHolder.getCurrentConfig();
        assertTrue(currentConfigOpt2.isPresent());
        assertEquals(config2, currentConfigOpt2.get());
        assertEquals(config2.clusterName(), currentConfigOpt2.get().clusterName());


        // Check old schema still there
        Optional<String> schemaOpt1_afterUpdate = cachedConfigHolder.getSchemaContent(ref1);
        assertTrue(schemaOpt1_afterUpdate.isPresent());
        assertEquals(schemaContent1, schemaOpt1_afterUpdate.get());

        // Check new schema
        Optional<String> schemaOpt2_afterUpdate = cachedConfigHolder.getSchemaContent(ref2);
        assertTrue(schemaOpt2_afterUpdate.isPresent());
        assertEquals(schemaContent2, schemaOpt2_afterUpdate.get());
    }

    @Test
    void updateConfiguration_withNullConfig_clearsExistingConfig() {
        PipelineClusterConfig config1 = createMinimalClusterConfig("cluster1");
        cachedConfigHolder.updateConfiguration(config1, Collections.emptyMap());
        assertTrue(cachedConfigHolder.getCurrentConfig().isPresent(), "Config should be present initially.");

        cachedConfigHolder.updateConfiguration(null, Collections.emptyMap()); // Clear the config
        assertTrue(cachedConfigHolder.getCurrentConfig().isEmpty(), "Config should be empty after updating with null.");
    }

    @Test
    void updateConfiguration_withNullSchemas_clearsExistingSchemas() {
        PipelineClusterConfig config1 = createMinimalClusterConfig("cluster1");
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        String schemaContent1 = "{\"type\":\"string\"}";
        cachedConfigHolder.updateConfiguration(config1, Collections.singletonMap(ref1, schemaContent1));
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isPresent(), "Schema should be present initially.");

        cachedConfigHolder.updateConfiguration(config1, null); // Clear schemas by passing null map
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isEmpty(), "Schema content should be empty after updating with null schemas map.");
    }

    @Test
    void updateConfiguration_withEmptySchemas_clearsExistingSchemas() {
        PipelineClusterConfig config1 = createMinimalClusterConfig("cluster1");
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        String schemaContent1 = "{\"type\":\"string\"}";
        cachedConfigHolder.updateConfiguration(config1, Collections.singletonMap(ref1, schemaContent1));
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isPresent(), "Schema should be present initially.");

        cachedConfigHolder.updateConfiguration(config1, Collections.emptyMap()); // Clear schemas by passing empty map
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isEmpty(), "Schema content should be empty after updating with empty schemas map.");
    }


    @Test
    void concurrencyTest_multipleReadersOneWriter_maintainsConsistency() throws InterruptedException {
        final int numReaders = 10;
        final int numWrites = 5; // Number of different configurations the writer will cycle through
        final int readsPerReaderPerWrite = 100;
        final ExecutorService executor = Executors.newFixedThreadPool(numReaders + 1);
        final CountDownLatch latch = new CountDownLatch(numReaders + 1);
        final AtomicBoolean testFailed = new AtomicBoolean(false);
        final AtomicReference<PipelineClusterConfig> lastWrittenConfig = new AtomicReference<>();
        final AtomicReference<Map<SchemaReference, String>> lastWrittenSchemas = new AtomicReference<>();

        // Writer Thread
        executor.submit(() -> {
            try {
                for (int i = 0; i < numWrites; i++) {
                    PipelineClusterConfig newConfig = createMinimalClusterConfig("cluster-write-" + i);
                    Map<SchemaReference, String> newSchemas = new HashMap<>();
                    SchemaReference ref = new SchemaReference("subject-write-" + i, i + 1);
                    newSchemas.put(ref, "{\"version\":" + (i + 1) + "}");

                    lastWrittenConfig.set(newConfig); // Update before actual write for readers to potentially see intermediate state
                    lastWrittenSchemas.set(Collections.unmodifiableMap(new HashMap<>(newSchemas))); // Store a copy

                    cachedConfigHolder.updateConfiguration(newConfig, newSchemas);
                    LOG.trace("Writer: Updated to config {}", newConfig.clusterName());
                    try {
                        Thread.sleep(10); // Brief pause to allow readers to catch up or interleave
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                // Final clear
                lastWrittenConfig.set(null);
                lastWrittenSchemas.set(Collections.emptyMap());
                cachedConfigHolder.updateConfiguration(null, null);
                LOG.trace("Writer: Cleared configuration.");

            } catch (Exception e) {
                LOG.error("TEST FAILED! Writer thread error", e);
                testFailed.set(true);
            } finally {
                latch.countDown();
            }
        });

        // Reader Threads
        for (int i = 0; i < numReaders; i++) {
            final int readerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < numWrites * readsPerReaderPerWrite; j++) {
                        Optional<PipelineClusterConfig> currentConfigOpt = cachedConfigHolder.getCurrentConfig();
                        PipelineClusterConfig currentConfigSnapshot = currentConfigOpt.orElse(null); // For consistent check against lastWrittenConfig
                        Map<SchemaReference, String> currentSchemasSnapshot = new HashMap<>();

                        // Grab a copy of last written schemas to check against if config is present
                        // This is a tricky part of testing concurrent reads of multiple related items.
                        // We are checking for *eventual consistency* after a write.
                        PipelineClusterConfig expectedConfig = lastWrittenConfig.get();
                        Map<SchemaReference, String> expectedSchemas = lastWrittenSchemas.get();

                        if (currentConfigSnapshot != null && expectedConfig != null && currentConfigSnapshot.clusterName().equals(expectedConfig.clusterName())) {
                            // If we read a config, try to read its associated schemas.
                            // The key is that getSchameContent should reflect the state *at the time of its call*.
                            if (expectedSchemas != null) {
                                for (SchemaReference ref : expectedSchemas.keySet()) {
                                    Optional<String> schemaContent = cachedConfigHolder.getSchemaContent(ref);
                                    String expectedContent = expectedSchemas.get(ref);
                                    if (schemaContent.isPresent()) {
                                        if (!schemaContent.get().equals(expectedContent)) {
                                            LOG.error("Reader [{}]: Inconsistent schema for ref {}! Expected '{}', Got '{}' for config '{}'",
                                                    readerId, ref.toIdentifier(), expectedContent, schemaContent.get(), currentConfigSnapshot.clusterName());
                                            testFailed.set(true);
                                        }
                                    } else {
                                        // Schema that was expected (part of lastWrittenSchemas for currentConfigSnapshot) was not found
                                        LOG.error("Reader [{}]: Expected schema {} not found for config '{}'",
                                                readerId, ref.toIdentifier(), currentConfigSnapshot.clusterName());
                                        testFailed.set(true);
                                    }
                                }
                            }
                        } else if (currentConfigSnapshot == null && expectedConfig != null) {
                            // Reader saw null config, but writer might have just written one. This is an acceptable transient state.
                            LOG.trace("Reader [{}]: Saw null config while writer possibly wrote {}. Acceptable race.", readerId, expectedConfig.clusterName());
                        } else if (currentConfigSnapshot != null && expectedConfig == null) {
                            // Reader saw a config, but writer might have just cleared it.
                            LOG.trace("Reader [{}]: Saw config {} while writer possibly cleared. Acceptable race.", readerId, currentConfigSnapshot.clusterName());
                        }
                        // Minimal work to allow context switching
                        if (j % 10 == 0) Thread.yield();
                    }
                } catch (Exception e) {
                    LOG.error("TEST FAILED! Reader thread [{}] error", readerId, e);
                    testFailed.set(true);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Test threads did not complete in time.");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate gracefully.");

        assertFalse(testFailed.get(), "One or more threads reported a failure/inconsistency. Check error logs for details.");
    }
}