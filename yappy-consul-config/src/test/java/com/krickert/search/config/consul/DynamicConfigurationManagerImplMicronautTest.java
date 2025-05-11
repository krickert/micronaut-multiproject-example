package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.schema.registry.model.SchemaCompatibility;
import com.krickert.search.config.schema.registry.model.SchemaType;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@MicronautTest(startApplication = false, environments = {"test-dynamic-manager"})
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
// This property is for the @Value in DynamicConfigurationManagerImpl constructor if not overridden
@Property(name = "app.config.cluster-name", value = DynamicConfigurationManagerImplMicronautTest.DEFAULT_PROPERTY_CLUSTER)
class DynamicConfigurationManagerImplMicronautTest {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerImplMicronautTest.class);
    static final String DEFAULT_PROPERTY_CLUSTER = "propertyClusterDefault"; // For @Value in SUT constructor
    static final String TEST_EXECUTION_CLUSTER = "dynamicManagerTestCluster"; // Cluster name used in tests

    @Inject
    Consul directConsulClientForTestSetup;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher;

    @Inject
    KiwiprojectConsulConfigFetcher realConsulConfigFetcher; // Use the real, TestContainers-backed fetcher

    @Inject
    TestApplicationEventListener testApplicationEventListener;

    private KeyValueClient testKvClient;
    private String clusterConfigKeyPrefix;
    private String schemaVersionsKeyPrefix;
    private int appWatchSeconds;


    // SUT instance, created per test or in BeforeEach
    private DynamicConfigurationManagerImpl dynamicConfigurationManager;

    // Dependencies for manual SUT construction
    private ConfigurationValidator mockValidator;
    private CachedConfigHolder testCachedConfigHolder;

    @BeforeEach
    void setUp() {
        testKvClient = directConsulClientForTestSetup.keyValueClient();

        // Get prefixes from the real fetcher (it reads them from properties)
        clusterConfigKeyPrefix = realConsulConfigFetcher.clusterConfigKeyPrefix;
        schemaVersionsKeyPrefix = realConsulConfigFetcher.schemaVersionsKeyPrefix;
        appWatchSeconds = realConsulConfigFetcher.appWatchSeconds;


        // Clean relevant Consul keys before each test
        deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
        // Add deletion for any general schema keys used in tests if necessary

        testApplicationEventListener.clear();

        // Prepare dependencies for manual SUT construction
        mockValidator = mock(ConfigurationValidator.class);
        testCachedConfigHolder = new SimpleMapCachedConfigHolder();

        // Construct the SUT manually for each test
        dynamicConfigurationManager = new DynamicConfigurationManagerImpl(
                TEST_EXECUTION_CLUSTER,       // Explicitly pass the cluster name for this test instance
                realConsulConfigFetcher,    // Use the real fetcher
                mockValidator,
                testCachedConfigHolder,
                eventPublisher
        );
        LOG.info("DynamicConfigurationManagerImpl manually constructed for cluster: {}", TEST_EXECUTION_CLUSTER);
    }

    @AfterEach
    void tearDown() {
        if (dynamicConfigurationManager != null) {
            dynamicConfigurationManager.shutdown();
        }
        deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
        LOG.info("Test finished, keys for cluster {} potentially cleaned.", TEST_EXECUTION_CLUSTER);
    }

    private void deleteConsulKeysForCluster(String clusterName) {
        String fullClusterKey = getFullClusterKey(clusterName);
        LOG.debug("Attempting to clean Consul key: {}", fullClusterKey);
        testKvClient.deleteKey(fullClusterKey);
        // If tests use specific schemas, they should clean them up too or use unique names
    }

    private String getFullClusterKey(String clusterName) {
        return clusterConfigKeyPrefix + clusterName;
    }

    private String getFullSchemaKey(String subject, int version) {
        return String.format("%s%s/%d", schemaVersionsKeyPrefix, subject, version);
    }

    private PipelineClusterConfig createDummyClusterConfig(String name, String... topics) {
        return new PipelineClusterConfig(
                name,
                null,
                new PipelineModuleMap(Collections.emptyMap()),
                topics != null ? Set.of(topics) : Collections.emptySet(),
                Collections.emptySet()
        );
    }

    private PipelineClusterConfig createClusterConfigWithSchema(String name, SchemaReference schemaRef, String... topics) {
        PipelineModuleConfiguration moduleWithSchema = new PipelineModuleConfiguration("ModuleWithSchema", "module_schema_impl_id", schemaRef);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(moduleWithSchema.implementationId(), moduleWithSchema));
        return new PipelineClusterConfig(
                name,
                null,
                moduleMap,
                topics != null ? Set.of(topics) : Collections.emptySet(),
                Collections.emptySet()
        );
    }

    private SchemaVersionData createDummySchemaData(String subject, int version, String content) {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS); // Ensure Jackson compatibility
        return new SchemaVersionData(
                (long) (Math.random() * 1000000), subject, version, content,
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, createdAt, "Integration test schema " + subject + " v" + version
        );
    }

    private void seedConsulKv(String key, Object object) throws JsonProcessingException {
        String jsonValue = objectMapper.writeValueAsString(object);
        LOG.info("Seeding Consul KV: {} = {}", key, jsonValue.length() > 150 ? jsonValue.substring(0, 150) + "..." : jsonValue);
        assertTrue(testKvClient.putValue(key, jsonValue), "Failed to seed Consul KV for key: " + key);
        // Allow a brief moment for Consul to process and for KVCache (if active) to pick up
        // This is more critical for watch tests than initial load.
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Test-specific event listener bean
    @Singleton
    static class TestApplicationEventListener {
        private static final Logger EVENT_LISTENER_LOG = LoggerFactory.getLogger(TestApplicationEventListener.class);
        private final BlockingQueue<ClusterConfigUpdateEvent> receivedEvents = new ArrayBlockingQueue<>(10);

        @io.micronaut.runtime.event.annotation.EventListener
        void onClusterConfigUpdate(ClusterConfigUpdateEvent event) {
            EVENT_LISTENER_LOG.info("TestApplicationEventListener received event for cluster '{}'. Old present: {}, New cluster: {}",
                    event.newConfig().clusterName(), event.oldConfig().isPresent(), event.newConfig().clusterName());
            // Only offer events relevant to the cluster name used in these tests
            if (TEST_EXECUTION_CLUSTER.equals(event.newConfig().clusterName()) ||
                (event.oldConfig().isPresent() && TEST_EXECUTION_CLUSTER.equals(event.oldConfig().get().clusterName()))) {
                receivedEvents.offer(event);
            } else {
                EVENT_LISTENER_LOG.warn("TestApplicationEventListener ignored event for different cluster: {}. Expected: {}",
                        event.newConfig().clusterName(), TEST_EXECUTION_CLUSTER);
            }
        }

        public ClusterConfigUpdateEvent pollEvent(long timeout, TimeUnit unit) throws InterruptedException {
            return receivedEvents.poll(timeout, unit);
        }

        public void clear() {
            receivedEvents.clear();
        }
    }

    // Simple in-memory cache holder for testing
    static class SimpleMapCachedConfigHolder implements CachedConfigHolder {
        private PipelineClusterConfig currentConfig;
        private Map<SchemaReference, String> currentSchemas = new HashMap<>();

        @Override
        public synchronized Optional<PipelineClusterConfig> getCurrentConfig() {
            return Optional.ofNullable(currentConfig);
        }

        @Override
        public synchronized Optional<String> getSchemaContent(SchemaReference schemaRef) {
            return Optional.ofNullable(currentSchemas.get(schemaRef));
        }

        @Override
        public synchronized void updateConfiguration(PipelineClusterConfig newConfig, Map<SchemaReference, String> schemaCache) {
            this.currentConfig = newConfig;
            this.currentSchemas = new HashMap<>(schemaCache);
            LOG.info("SimpleMapCachedConfigHolder updated. Config: {}, Schemas: {}",
                    newConfig != null ? newConfig.clusterName() : "null", schemaCache.keySet());
        }

        @Override
        public synchronized void clearConfiguration() {
            this.currentConfig = null;
            this.currentSchemas.clear();
            LOG.info("SimpleMapCachedConfigHolder cleared.");
        }
    }

    // --- Actual Test Methods ---

    @Test
    @DisplayName("Integration: Successful initial load with schema, then watch update")
    @Timeout(value = 60, unit = TimeUnit.SECONDS) // Generous timeout for integration test with watches
    void integration_initialLoad_thenWatchUpdate() throws Exception {
        // --- Setup Data ---
        SchemaReference schemaRef1 = new SchemaReference("integSchemaSubject1", 1);
        PipelineClusterConfig initialConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, schemaRef1, "topicInit1");
        SchemaVersionData schemaData1 = createDummySchemaData(schemaRef1.subject(), schemaRef1.version(), "{\"type\":\"object\",\"properties\":{\"field1\":{\"type\":\"string\"}}}");

        String fullSchemaKey1 = getFullSchemaKey(schemaRef1.subject(), schemaRef1.version());
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        // Clean specific schema key for this test
        testKvClient.deleteKey(fullSchemaKey1);

        // Seed schema into Consul FIRST
        seedConsulKv(fullSchemaKey1, schemaData1);
        // Seed initial cluster config into Consul
        seedConsulKv(fullClusterKey, initialConfig);

        // Configure mock validator for initial load
        when(mockValidator.validate(eq(initialConfig), any()))
                .thenReturn(ValidationResult.valid());

        // --- Act: Initialize DCM ---
        LOG.info("integration_initialLoad_thenWatchUpdate: Initializing DynamicConfigurationManager for cluster '{}'...", TEST_EXECUTION_CLUSTER);
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
        LOG.info("integration_initialLoad_thenWatchUpdate: Initialization complete.");

        // --- Verify Initial Load ---
        ClusterConfigUpdateEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(initialEvent, "Should have received an initial load event");
        assertTrue(initialEvent.oldConfig().isEmpty(), "Old config should be empty for initial load");
        assertEquals(initialConfig, initialEvent.newConfig(), "New config in event should match initial seeded config");

        Optional<PipelineClusterConfig> cachedConfigAfterInit = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterInit.isPresent(), "Config should be in cache after initial load");
        assertEquals(initialConfig, cachedConfigAfterInit.get());
        assertEquals(schemaData1.schemaContent(), testCachedConfigHolder.getSchemaContent(schemaRef1).orElse(null), "Schema content should be cached");
        LOG.info("integration_initialLoad_thenWatchUpdate: Initial load verified.");

        // --- Setup for Watch Update ---
        PipelineClusterConfig updatedConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, schemaRef1, "topicInit1", "topicUpdate2");
        // Validator for the update
        when(mockValidator.validate(eq(updatedConfig), any()))
                .thenReturn(ValidationResult.valid());

        // --- Act: Trigger Watch Update by changing Consul data ---
        LOG.info("integration_initialLoad_thenWatchUpdate: Seeding updated config to trigger watch...");
        seedConsulKv(fullClusterKey, updatedConfig);
        LOG.info("integration_initialLoad_thenWatchUpdate: Updated config seeded.");

        // --- Verify Watch Update ---
        // The KVCache might fire multiple times if the value changes rapidly or due to its internal polling.
        // We are interested in the event that reflects the 'updatedConfig'.
        ClusterConfigUpdateEvent updateEvent = null;
        long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15);
        while(System.currentTimeMillis() < endTime) {
            ClusterConfigUpdateEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polledEvent != null && updatedConfig.equals(polledEvent.newConfig())) {
                updateEvent = polledEvent;
                break;
            }
            if (polledEvent != null) {
                LOG.info("integration_initialLoad_thenWatchUpdate: Polled an intermediate event: {}", polledEvent.newConfig().allowedKafkaTopics());
            }
        }

        assertNotNull(updateEvent, "Should have received an update event from watch for the final updatedConfig");
        assertTrue(updateEvent.oldConfig().isPresent(), "Old config should be present in update event");
        assertEquals(initialConfig, updateEvent.oldConfig().get(), "Old config in event should be the previously loaded one");
        assertEquals(updatedConfig, updateEvent.newConfig(), "New config in event should match the updated seeded config");

        Optional<PipelineClusterConfig> cachedConfigAfterUpdate = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterUpdate.isPresent(), "Config should be in cache after update");
        assertEquals(updatedConfig, cachedConfigAfterUpdate.get());
        assertEquals(schemaData1.schemaContent(), testCachedConfigHolder.getSchemaContent(schemaRef1).orElse(null), "Schema content should still be cached");
        LOG.info("integration_initialLoad_thenWatchUpdate: Watch update verified.");

        // Clean up schema key used in this test
        testKvClient.deleteKey(fullSchemaKey1);
    }

    @Test
    @DisplayName("Integration: Initial load - no config found, then config appears via watch")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_initialLoad_noConfigFound_thenAppearsOnWatch() throws Exception {
        // --- Setup: Ensure no pre-existing config for this cluster ---
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        testKvClient.deleteKey(fullClusterKey); // Explicitly delete to be sure
        LOG.info("integration_noConfigFound_thenAppears: Ensured key {} is deleted before test.", fullClusterKey);

        // No specific validator stubbing needed for the initial (empty) phase

        // --- Act: Initialize DCM ---
        LOG.info("integration_noConfigFound_thenAppears: Initializing DynamicConfigurationManager for cluster '{}'...", TEST_EXECUTION_CLUSTER);
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
        LOG.info("integration_noConfigFound_thenAppears: Initialization complete.");

        // --- Verify Initial Phase (No Config) ---
        ClusterConfigUpdateEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 2, TimeUnit.SECONDS); // Shorter poll, expect no event
        assertNull(initialEvent, "Should NOT have received an event as no initial config was found");

        Optional<PipelineClusterConfig> cachedConfigAfterInit = testCachedConfigHolder.getCurrentConfig();
        assertFalse(cachedConfigAfterInit.isPresent(), "Config should NOT be in cache as none was found initially");
        LOG.info("integration_noConfigFound_thenAppears: Verified no initial config loaded or event published.");

        // --- Setup for Watch Discovery ---
        PipelineClusterConfig newConfigAppearing = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicAppeared1");
        // Validator for the newly appearing config
        when(mockValidator.validate(eq(newConfigAppearing), any()))
                .thenReturn(ValidationResult.valid());

        // --- Act: Trigger Watch Discovery by seeding Consul data ---
        LOG.info("integration_noConfigFound_thenAppears: Seeding new config to be discovered by watch...");
        seedConsulKv(fullClusterKey, newConfigAppearing);
        LOG.info("integration_noConfigFound_thenAppears: New config seeded.");

        // --- Verify Watch Discovery ---
        ClusterConfigUpdateEvent discoveredEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 15, TimeUnit.SECONDS);
        assertNotNull(discoveredEvent, "Should have received an event from watch when config appeared");
        assertTrue(discoveredEvent.oldConfig().isEmpty(), "Old config in event should be empty as this is the first load via watch");
        assertEquals(newConfigAppearing, discoveredEvent.newConfig(), "New config in event should match the appeared config");

        Optional<PipelineClusterConfig> cachedConfigAfterDiscovery = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterDiscovery.isPresent(), "Config should be in cache after being discovered by watch");
        assertEquals(newConfigAppearing, cachedConfigAfterDiscovery.get());
        LOG.info("integration_noConfigFound_thenAppears: Config discovered by watch and processed successfully.");
    }

    @Test
    @DisplayName("Integration: Initial load - config present but fails validation")
    @Timeout(value = 30, unit = TimeUnit.SECONDS) // Shorter, as no successful watch event is expected immediately
    void integration_initialLoad_configFailsValidation() throws Exception {
        // --- Setup Data ---
        PipelineClusterConfig invalidInitialConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicInvalid1");
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        // Seed the invalid config into Consul
        seedConsulKv(fullClusterKey, invalidInitialConfig);

        // Configure mock validator to return invalid for this specific config
        when(mockValidator.validate(eq(invalidInitialConfig), any()))
                .thenReturn(ValidationResult.invalid(Collections.singletonList("Test validation error: initial config is bad")));

        // --- Act: Initialize DCM ---
        LOG.info("integration_initialLoad_failsValidation: Initializing DynamicConfigurationManager for cluster '{}'...", TEST_EXECUTION_CLUSTER);
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
        LOG.info("integration_initialLoad_failsValidation: Initialization complete.");

        // --- Verify Initial Load Failure ---
        // Expect no successful update event.
        // Depending on internal logic, an error event *could* be published, but we're focused on no *successful* update.
        ClusterConfigUpdateEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 2, TimeUnit.SECONDS); // Short poll
        assertNull(initialEvent, "Should NOT have received a successful config update event due to validation failure");

        Optional<PipelineClusterConfig> cachedConfigAfterInit = testCachedConfigHolder.getCurrentConfig();
        assertFalse(cachedConfigAfterInit.isPresent(), "Config should NOT be in cache after initial load validation failure");
        LOG.info("integration_initialLoad_failsValidation: Verified no config cached and no successful event published due to validation failure.");

        // --- Verify Watch is Still Active (Optional but good) ---
        // To prove the watch is active, we can seed a *new, valid* config and see if it gets picked up.
        // This part is similar to the 'noConfigFound_thenAppearsOnWatch' test's latter half.
        LOG.info("integration_initialLoad_failsValidation: Attempting to seed a valid config to check if watch is active...");
        PipelineClusterConfig subsequentValidConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicValidAfterFail");
        when(mockValidator.validate(eq(subsequentValidConfig), any())) // Validator for the new, valid config
                .thenReturn(ValidationResult.valid());

        seedConsulKv(fullClusterKey, subsequentValidConfig);
        LOG.info("integration_initialLoad_failsValidation: Subsequent valid config seeded.");

        ClusterConfigUpdateEvent recoveryEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 15, TimeUnit.SECONDS);
        assertNotNull(recoveryEvent, "Should have received an event when a subsequent valid config appeared on the watch");
        assertTrue(recoveryEvent.oldConfig().isEmpty(), "Old config in recovery event should be empty (as initial validation failed)");
        assertEquals(subsequentValidConfig, recoveryEvent.newConfig(), "New config in recovery event should match the valid seeded config");

        Optional<PipelineClusterConfig> cachedConfigAfterRecovery = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterRecovery.isPresent(), "Config should be in cache after valid config discovered by watch");
        assertEquals(subsequentValidConfig, cachedConfigAfterRecovery.get());
        LOG.info("integration_initialLoad_failsValidation: Watch successfully picked up a subsequent valid configuration.");
    }

    @Test
    @DisplayName("Integration: Config present, then deleted via watch")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_configPresent_thenDeletedViaWatch() throws Exception {
        // --- Setup Initial Valid Config ---
        PipelineClusterConfig initialConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicToDelete1");
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        // Seed initial config
        seedConsulKv(fullClusterKey, initialConfig);

        // Configure mock validator for initial load
        when(mockValidator.validate(eq(initialConfig), any()))
                .thenReturn(ValidationResult.valid());

        // --- Act: Initialize DCM ---
        LOG.info("integration_configDeleted: Initializing DynamicConfigurationManager for cluster '{}'...", TEST_EXECUTION_CLUSTER);
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
        LOG.info("integration_configDeleted: Initialization complete.");

        // --- Verify Initial Load ---
        ClusterConfigUpdateEvent initialLoadEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(initialLoadEvent, "Should have received an initial load event");
        assertEquals(initialConfig, initialLoadEvent.newConfig(), "New config in initial event should match seeded config");
        assertTrue(testCachedConfigHolder.getCurrentConfig().isPresent(), "Config should be in cache after initial load");
        assertEquals(initialConfig, testCachedConfigHolder.getCurrentConfig().get());
        LOG.info("integration_configDeleted: Initial load verified.");

        // --- Act: Delete the config from Consul ---
        LOG.info("integration_configDeleted: Deleting config from Consul for key {}...", fullClusterKey);
        testKvClient.deleteKey(fullClusterKey);
        // Add a small delay to ensure KVCache picks up the delete
        TimeUnit.MILLISECONDS.sleep(appWatchSeconds * 1000L / 2 + 500); // Wait for a bit more than half a watch cycle
        LOG.info("integration_configDeleted: Config deleted from Consul.");

        // --- Verify Deletion Event and Cache State ---
        ClusterConfigUpdateEvent deletionEvent = null;
        long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15); // Total wait time

        LOG.info("integration_configDeleted: Polling for deletion event...");
        while (System.currentTimeMillis() < endTime) {
            ClusterConfigUpdateEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polledEvent != null) {
                LOG.info("integration_configDeleted: Polled event. OldConfig present: {}, NewConfig topics: {}",
                        polledEvent.oldConfig().isPresent(),
                        polledEvent.newConfig().allowedKafkaTopics());

                // Check if this is the true deletion event:
                // oldConfig should be the one we expect, and newConfig should be an empty shell.
                if (polledEvent.oldConfig().isPresent() &&
                        initialConfig.equals(polledEvent.oldConfig().get()) &&
                        (polledEvent.newConfig().allowedKafkaTopics() == null || polledEvent.newConfig().allowedKafkaTopics().isEmpty()) &&
                        (polledEvent.newConfig().pipelineModuleMap() == null || polledEvent.newConfig().pipelineModuleMap().availableModules().isEmpty())) {
                    deletionEvent = polledEvent;
                    LOG.info("integration_configDeleted: True deletion event found: {}", deletionEvent);
                    break;
                } else {
                    LOG.info("integration_configDeleted: Intermediate event received, continuing to poll for deletion event.");
                }
            }
            if (System.currentTimeMillis() >= endTime && deletionEvent == null) {
                LOG.warn("integration_configDeleted: Timeout reached while polling for deletion event.");
            }
        }

        assertNotNull(deletionEvent, "Should have received a deletion event from watch");

        // Now the assertions for the deletionEvent should pass because we've specifically found it.
        assertTrue(deletionEvent.oldConfig().isPresent(), "Old config should be present in deletion event");
        assertEquals(initialConfig, deletionEvent.oldConfig().get(), "Old config in deletion event should be the one that was deleted");

        assertNotNull(deletionEvent.newConfig(), "New config in deletion event should not be null (it's an 'empty' shell)");
        assertEquals(TEST_EXECUTION_CLUSTER, deletionEvent.newConfig().clusterName(), "New config shell should have the correct cluster name");
        assertTrue(deletionEvent.newConfig().allowedKafkaTopics() == null || deletionEvent.newConfig().allowedKafkaTopics().isEmpty(), "New config topics should be empty for deletion");
        assertTrue(deletionEvent.newConfig().pipelineModuleMap() == null || deletionEvent.newConfig().pipelineModuleMap().availableModules().isEmpty(), "New config modules should be empty for deletion");

        Optional<PipelineClusterConfig> cachedConfigAfterDelete = testCachedConfigHolder.getCurrentConfig();
        assertFalse(cachedConfigAfterDelete.isPresent(), "Config should be cleared from cache after deletion");
        LOG.info("integration_configDeleted: Deletion processed successfully, cache cleared.");
        // Assert that the new config is effectively empty (no topics, no modules, etc.)
        assertTrue(deletionEvent.newConfig().allowedKafkaTopics() == null || deletionEvent.newConfig().allowedKafkaTopics().isEmpty());
        assertTrue(deletionEvent.newConfig().pipelineModuleMap() == null || deletionEvent.newConfig().pipelineModuleMap().availableModules().isEmpty());
        assertFalse(cachedConfigAfterDelete.isPresent(), "Config should be cleared from cache after deletion");
        LOG.info("integration_configDeleted: Deletion processed successfully, cache cleared.");
    }
}