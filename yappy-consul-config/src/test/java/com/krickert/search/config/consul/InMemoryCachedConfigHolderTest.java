package com.krickert.search.config.consul;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryCachedConfigHolderTest {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InMemoryCachedConfigHolderTest.class);

    private InMemoryCachedConfigHolder cachedConfigHolder;

    @BeforeEach
    void setUp() {
        cachedConfigHolder = new InMemoryCachedConfigHolder();
    }

    @Test
    void initialState_isEmpty() {
        assertTrue(cachedConfigHolder.getCurrentConfig().isEmpty(), "Initial config should be empty.");
        assertTrue(cachedConfigHolder.getSchemaContent(new SchemaReference("any", 1)).isEmpty(), "Initial schema content should be empty.");
    }

    @Test
    void updateConfiguration_withValidConfig_storesAndReturnsConfigAndSchemas() {
        PipelineClusterConfig config1 = new PipelineClusterConfig("cluster1", null, null, null, null);
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        String schemaContent1 = "{\"type\":\"string\"}";
        Map<SchemaReference, String> schemaMap1 = Map.of(ref1, schemaContent1);

        cachedConfigHolder.updateConfiguration(config1, schemaMap1);

        assertEquals(Optional.of(config1), cachedConfigHolder.getCurrentConfig());
        assertEquals(Optional.of(schemaContent1), cachedConfigHolder.getSchemaContent(ref1));
        assertTrue(cachedConfigHolder.getSchemaContent(new SchemaReference("other", 1)).isEmpty());
    }

    @Test
    void updateConfiguration_withNullConfig_clearsExistingConfig() {
        PipelineClusterConfig config1 = new PipelineClusterConfig("cluster1", null, null, null, null);
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        Map<SchemaReference, String> schemaMap1 = Map.of(ref1, "{}");
        cachedConfigHolder.updateConfiguration(config1, schemaMap1); // Pre-populate

        assertNotNull(cachedConfigHolder.getCurrentConfig().orElse(null)); // Ensure it was populated

        cachedConfigHolder.updateConfiguration(null, Collections.emptyMap());

        assertTrue(cachedConfigHolder.getCurrentConfig().isEmpty());
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isEmpty());
    }

    @Test
    void updateConfiguration_withNullSchemaMap_storesConfigWithEffectivelyEmptySchemaCache() {
        PipelineClusterConfig config1 = new PipelineClusterConfig("cluster1", null, null, null, null);
        SchemaReference ref1 = new SchemaReference("subject1", 1);

        cachedConfigHolder.updateConfiguration(config1, null);

        assertEquals(Optional.of(config1), cachedConfigHolder.getCurrentConfig());
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isEmpty(), "Schema content should be empty for any ref if null map was passed.");
    }

    @Test
    void updateConfiguration_internalSchemaMapIsCopyAndUnmodifiable() {
        PipelineClusterConfig config1 = new PipelineClusterConfig("cluster1", null, null, null, null);
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        String schemaContent1 = "{\"type\":\"string\"}";
        Map<SchemaReference, String> mutableSchemaMap = new HashMap<>();
        mutableSchemaMap.put(ref1, schemaContent1);

        cachedConfigHolder.updateConfiguration(config1, mutableSchemaMap);

        SchemaReference ref2 = new SchemaReference("subject2", 1);
        mutableSchemaMap.put(ref2, "{\"type\":\"integer\"}"); // Modify original map

        assertEquals(Optional.of(schemaContent1), cachedConfigHolder.getSchemaContent(ref1));
        assertTrue(cachedConfigHolder.getSchemaContent(ref2).isEmpty(),
                "Cache should not reflect external modifications to the original schema map input.");
    }


    @Test
    void updateConfiguration_calledSequentially_reflectsLatestConfig() {
        PipelineClusterConfig config1 = new PipelineClusterConfig("cluster1", null, null, null, null);
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        String schemaContent1 = "{\"type\":\"string\"}";
        Map<SchemaReference, String> schemaMap1 = Map.of(ref1, schemaContent1);

        PipelineClusterConfig config2 = new PipelineClusterConfig("cluster2", null, null, null, null);
        SchemaReference ref2 = new SchemaReference("subject2", 2);
        String schemaContent2 = "{\"type\":\"integer\"}";
        Map<SchemaReference, String> schemaMap2 = Map.of(ref2, schemaContent2);

        cachedConfigHolder.updateConfiguration(config1, schemaMap1);
        cachedConfigHolder.updateConfiguration(config2, schemaMap2);

        assertEquals(Optional.of(config2), cachedConfigHolder.getCurrentConfig());
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isEmpty(), "Schema from config1 should be gone.");
        assertEquals(Optional.of(schemaContent2), cachedConfigHolder.getSchemaContent(ref2), "Schema from config2 should be present.");
    }

    @Test
    void clearConfiguration_whenConfigExists_clearsConfigAndSchemas() {
        PipelineClusterConfig config1 = new PipelineClusterConfig("cluster1", null, null, null, null);
        SchemaReference ref1 = new SchemaReference("subject1", 1);
        Map<SchemaReference, String> schemaMap1 = Map.of(ref1, "{}");
        cachedConfigHolder.updateConfiguration(config1, schemaMap1);

        assertTrue(cachedConfigHolder.getCurrentConfig().isPresent());

        cachedConfigHolder.clearConfiguration();

        assertTrue(cachedConfigHolder.getCurrentConfig().isEmpty());
        assertTrue(cachedConfigHolder.getSchemaContent(ref1).isEmpty());
    }

    @Test
    void clearConfiguration_whenAlreadyEmpty_remainsEmptyAndDoesNotThrow() {
        assertTrue(cachedConfigHolder.getCurrentConfig().isEmpty()); // Initial state
        assertDoesNotThrow(() -> cachedConfigHolder.clearConfiguration());
        assertTrue(cachedConfigHolder.getCurrentConfig().isEmpty());
    }

    @Test
    void concurrentUpdatesAndReads_maintainsConsistency() throws InterruptedException {
        int numWriterThreads = 5; // Reduced for faster local testing, increase for more rigor
        int numReaderThreads = 5;
        int operationsPerThread = 50; // Reduced for faster local testing
        AtomicBoolean testFailed = new AtomicBoolean(false);

        try (ExecutorService executor = Executors.newFixedThreadPool(numWriterThreads + numReaderThreads)) {
            CountDownLatch latch = new CountDownLatch(numWriterThreads + numReaderThreads);

            PipelineClusterConfig configA = new PipelineClusterConfig("configA", null, null, null, null);
            Map<SchemaReference, String> schemasA = Map.of(new SchemaReference("sA", 1), "sa_content");
            PipelineClusterConfig configB = new PipelineClusterConfig("configB", null, null, null, null);
            Map<SchemaReference, String> schemasB = Map.of(new SchemaReference("sB", 1), "sb_content");

            // Writer Threads
            for (int i = 0; i < numWriterThreads; i++) {
                final int writerId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            if (j % 3 == 0) {
                                cachedConfigHolder.updateConfiguration(configA, schemasA);
                            } else if (j % 3 == 1) {
                                cachedConfigHolder.updateConfiguration(configB, schemasB);
                            } else {
                                cachedConfigHolder.clearConfiguration();
                            }
                            Thread.yield(); // Allow context switching
                        }
                    } catch (Exception e) {
                        LOG.error("TEST FAILED!!! Writer thread [{}] ", writerId, e);
                        testFailed.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Reader Threads
            for (int i = 0; i < numReaderThreads; i++) {
                final int readerId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            // Get the entire state snapshot atomically for consistent checking
                            InMemoryCachedConfigHolder.CachedState stateSnapshot = cachedConfigHolder.getCachedStateSnapshotForTest();

                            if (stateSnapshot != null) {
                                PipelineClusterConfig currentConfig = stateSnapshot.clusterConfig();
                                Map<SchemaReference, String> currentSchemasInSnapshot = stateSnapshot.schemaCache();
                                Map<SchemaReference, String> expectedSchemasForThisConfig = null;

                                if (currentConfig.equals(configA)) {
                                    expectedSchemasForThisConfig = schemasA;
                                } else if (currentConfig.equals(configB)) {
                                    expectedSchemasForThisConfig = schemasB;
                                } else {
                                    // This could happen if the config was some other unexpected value
                                    // or if the equals method isn't working as expected for records (it should).
                                    // For this test, we assume only configA, configB, or null (handled by stateSnapshot != null)
                                    // are possible valid configs written by writers.
                                    // If currentConfig.clusterName() is not "configA" or "configB", it implies an issue.
                                    if (!("configA".equals(currentConfig.clusterName()) || "configB".equals(currentConfig.clusterName()))) {
                                        LOG.error("Reader [{}]: Encountered an unexpected config object: [{}]", readerId, currentConfig.clusterName());
                                        testFailed.set(true);
                                    }
                                }

                                if (expectedSchemasForThisConfig != null) {
                                    // Check that all expected schemas are present and correct
                                    for (Map.Entry<SchemaReference, String> expectedEntry : expectedSchemasForThisConfig.entrySet()) {
                                        String cachedContent = currentSchemasInSnapshot.get(expectedEntry.getKey());
                                        if (cachedContent == null || !cachedContent.equals(expectedEntry.getValue())) {
                                            LOG.error("Reader [{}]: Inconsistent schema for config [{}] and ref [{}]. Expected: [{}] Got: [{}]",
                                                    readerId,currentConfig.clusterName(),
                                                    expectedEntry.getKey(),
                                                    expectedEntry.getValue(),
                                                    (cachedContent == null ? "null" : cachedContent));
                                            testFailed.set(true);
                                        }
                                    }
                                    // Check that no unexpected schemas are present for this known config
                                    for (SchemaReference actualRef : currentSchemasInSnapshot.keySet()) {
                                        if (!expectedSchemasForThisConfig.containsKey(actualRef)) {
                                            LOG.error("Reader [{}]: Snapshot for config [{}] contained unexpected schema ref: [{}]",
                                                    readerId,
                                                    currentConfig.clusterName(),
                                                    actualRef);
                                            testFailed.set(true);
                                        }
                                    }
                                }
                            } // else: stateSnapshot was null (cleared by a writer), which is a valid state.
                            Thread.yield(); // Allow context switching
                        }
                    } catch (Exception e) {
                        LOG.error("TEST FAILED! Reader thread [{}]", readerId, e);
                        testFailed.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(15, TimeUnit.SECONDS), "Test threads did not complete in time.");
            // executor.shutdownNow(); // No, let it complete normally, then shutdown.
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate gracefully.");
        }
        assertFalse(testFailed.get(), "One or more threads reported a failure/inconsistency. Check error logs for details.");
    }
}