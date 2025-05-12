package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
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
import static org.mockito.Mockito.*;


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

    @Test
    @DisplayName("Integration: Watch update - new config fails validation, keeps old config")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_watchUpdate_newConfigFailsValidation_keepsOldConfig() throws Exception {
        // --- Setup Initial Valid Config ---
        SchemaReference initialSchemaRef = new SchemaReference("integSchemaInitial", 1);
        PipelineClusterConfig initialValidConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, initialSchemaRef, "topicInitialValid");
        SchemaVersionData initialSchemaData = createDummySchemaData(initialSchemaRef.subject(), initialSchemaRef.version(), "{\"type\":\"string\"}");

        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        String fullInitialSchemaKey = getFullSchemaKey(initialSchemaRef.subject(), initialSchemaRef.version());

        testKvClient.deleteKey(fullInitialSchemaKey); // Clean schema key

        seedConsulKv(fullInitialSchemaKey, initialSchemaData);
        seedConsulKv(fullClusterKey, initialValidConfig);

        when(mockValidator.validate(eq(initialValidConfig), any()))
                .thenReturn(ValidationResult.valid());

        // --- Initialize DCM ---
        LOG.info("integration_watchUpdate_failsValidation: Initializing DCM...");
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        // --- Verify Initial Load ---
        ClusterConfigUpdateEvent initialLoadEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(initialLoadEvent, "Should have received an initial load event");
        assertEquals(initialValidConfig, initialLoadEvent.newConfig());
        assertEquals(initialValidConfig, testCachedConfigHolder.getCurrentConfig().orElse(null));
        assertEquals(initialSchemaData.schemaContent(), testCachedConfigHolder.getSchemaContent(initialSchemaRef).orElse(null));
        LOG.info("integration_watchUpdate_failsValidation: Initial load verified.");
        testApplicationEventListener.clear(); // Clear events before watch update

        // --- Setup for Watch Update (which will fail validation) ---
        SchemaReference newSchemaRef = new SchemaReference("integSchemaNewInvalid", 1); // Could be same or different
        PipelineClusterConfig newInvalidConfigFromWatch = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, newSchemaRef, "topicNewInvalid");
        SchemaVersionData newSchemaData = createDummySchemaData(newSchemaRef.subject(), newSchemaRef.version(), "{\"type\":\"integer\"}");
        String fullNewSchemaKey = getFullSchemaKey(newSchemaRef.subject(), newSchemaRef.version());

        testKvClient.deleteKey(fullNewSchemaKey); // Clean new schema key
        seedConsulKv(fullNewSchemaKey, newSchemaData); // Seed the schema for the new invalid config

        // Configure validator to FAIL for the new config
        when(mockValidator.validate(eq(newInvalidConfigFromWatch), any()))
                .thenReturn(ValidationResult.invalid(Collections.singletonList("Test: New config from watch is invalid")));

        // --- Act: Trigger Watch Update by changing Consul data ---
        LOG.info("integration_watchUpdate_failsValidation: Seeding new (invalid) config to trigger watch...");
        seedConsulKv(fullClusterKey, newInvalidConfigFromWatch);
        LOG.info("integration_watchUpdate_failsValidation: New (invalid) config seeded.");

        // --- Verify Behavior after Failed Validation on Watch ---
        // We expect NO successful update event for newInvalidConfigFromWatch.
        // The KVCache might fire, DCM will process, validation will fail, and nothing should change in cache/event for success.
        ClusterConfigUpdateEvent eventAfterInvalidUpdate = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS); // Poll for a while

        if (eventAfterInvalidUpdate != null) {
            LOG.warn("integration_watchUpdate_failsValidation: Polled an event: {}. This should not be for the new invalid config.", eventAfterInvalidUpdate);
            // If an event *is* received, it MUST NOT be the newInvalidConfigFromWatch as the 'newConfig'
            // and its oldConfig should be the initialValidConfig. This could happen if KVCache fires multiple times.
            assertNotEquals(newInvalidConfigFromWatch, eventAfterInvalidUpdate.newConfig(),
                    "Event's newConfig should not be the invalid one.");
            if (eventAfterInvalidUpdate.oldConfig().isPresent()) {
                assertEquals(initialValidConfig, eventAfterInvalidUpdate.oldConfig().get(), "If an event occurred, its oldConfig should be the initial one.");
            }
        } else {
            LOG.info("integration_watchUpdate_failsValidation: Correctly received no new successful update event after invalid config from watch.");
        }


        // CRITICAL: Verify that the cache still holds the OLD VALID config
        Optional<PipelineClusterConfig> cachedConfigAfterInvalid = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterInvalid.isPresent(), "Cache should still contain a config");
        assertEquals(initialValidConfig, cachedConfigAfterInvalid.get(), "Cache should still hold the initial valid config");
        assertEquals(initialSchemaData.schemaContent(), testCachedConfigHolder.getSchemaContent(initialSchemaRef).orElse(null), "Cache should still hold initial valid schema");
        LOG.info("integration_watchUpdate_failsValidation: Verified cache still holds the old valid configuration.");

        // Verify that the validator was indeed called with the new invalid config
        // The schema provider passed to validate should be able to resolve newSchemaRef
        verify(mockValidator).validate(eq(newInvalidConfigFromWatch), argThat(provider -> {
            Optional<String> schemaContentOpt = provider.apply(newSchemaRef); // Use .apply()
            return schemaContentOpt.isPresent() &&
                    newSchemaData.schemaContent().equals(schemaContentOpt.get());
        }));
        LOG.info("integration_watchUpdate_failsValidation: Verified validator was called for the new invalid config.");

        // Clean up schema keys
        testKvClient.deleteKey(fullInitialSchemaKey);
        testKvClient.deleteKey(fullNewSchemaKey);
    }
    @Test
    @DisplayName("Integration: Watch update - new config references missing schema, keeps old config")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_watchUpdate_newConfigMissingSchema_keepsOldConfig() throws Exception {
        // --- Setup Initial Valid Config (can be simple, without schemas for this test's initial state) ---
        PipelineClusterConfig initialValidConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicInitialValid");
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        seedConsulKv(fullClusterKey, initialValidConfig);
        when(mockValidator.validate(eq(initialValidConfig), any()))
                .thenReturn(ValidationResult.valid());

        // --- Initialize DCM ---
        LOG.info("integration_watchUpdate_missingSchema: Initializing DCM...");
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        // --- Verify Initial Load ---
        ClusterConfigUpdateEvent initialLoadEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(initialLoadEvent, "Should have received an initial load event");
        assertEquals(initialValidConfig, initialLoadEvent.newConfig());
        assertEquals(initialValidConfig, testCachedConfigHolder.getCurrentConfig().orElse(null));
        LOG.info("integration_watchUpdate_missingSchema: Initial load verified.");
        testApplicationEventListener.clear(); // Clear events before watch update

        // --- Setup for Watch Update (with a config referencing a MISSING schema) ---
        SchemaReference missingSchemaRef = new SchemaReference("integSchemaSubjectMissing", 1);
        PipelineClusterConfig newConfigMissingSchema = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, missingSchemaRef, "topicNewMissingSchema");
        String fullMissingSchemaKey = getFullSchemaKey(missingSchemaRef.subject(), missingSchemaRef.version());

        // Ensure the schema is NOT in Consul
        testKvClient.deleteKey(fullMissingSchemaKey);
        LOG.info("integration_watchUpdate_missingSchema: Ensured schema key {} is deleted.", fullMissingSchemaKey);

        // Configure validator to FAIL if the schemaProvider cannot provide 'missingSchemaRef'
        when(mockValidator.validate(eq(newConfigMissingSchema), argThat(provider ->
                !provider.apply(missingSchemaRef).isPresent() // Check that the schema is indeed missing from the provider
        ))).thenReturn(ValidationResult.invalid(Collections.singletonList("Test: Schema " + missingSchemaRef + " not found")));

        // --- Act: Trigger Watch Update by changing Consul data ---
        LOG.info("integration_watchUpdate_missingSchema: Seeding new config (referencing missing schema) to trigger watch...");
        seedConsulKv(fullClusterKey, newConfigMissingSchema);
        LOG.info("integration_watchUpdate_missingSchema: New config (referencing missing schema) seeded.");

        // --- Verify Behavior after Missing Schema on Watch ---
        // Expect NO successful update event for newConfigMissingSchema.
        ClusterConfigUpdateEvent eventAfterMissingSchema = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);

        if (eventAfterMissingSchema != null) {
            LOG.warn("integration_watchUpdate_missingSchema: Polled an event: {}. This should not be for the new config with missing schema.", eventAfterMissingSchema);
            assertNotEquals(newConfigMissingSchema, eventAfterMissingSchema.newConfig(),
                    "Event's newConfig should not be the one with the missing schema.");
        } else {
            LOG.info("integration_watchUpdate_missingSchema: Correctly received no new successful update event after config with missing schema from watch.");
        }

        // CRITICAL: Verify that the cache still holds the OLD VALID config
        Optional<PipelineClusterConfig> cachedConfigAfterMissingSchema = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterMissingSchema.isPresent(), "Cache should still contain a config");
        assertEquals(initialValidConfig, cachedConfigAfterMissingSchema.get(), "Cache should still hold the initial valid config");
        LOG.info("integration_watchUpdate_missingSchema: Verified cache still holds the old valid configuration.");

        // Verify that the validator was indeed called with the new config and a schema provider
        // that would return empty for the missing schema.
        verify(mockValidator).validate(eq(newConfigMissingSchema), argThat(provider ->
                !provider.apply(missingSchemaRef).isPresent()
        ));
        LOG.info("integration_watchUpdate_missingSchema: Verified validator was called for the new config with a provider that could not resolve the missing schema.");
    }

    @Test
    @DisplayName("Integration: Initial load - config references schema, but schema fetch fails, watch still starts")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_initialLoad_configReferencesMissingSchema_watchStarts() throws Exception {
        // --- Setup Data ---
        SchemaReference missingSchemaRef = new SchemaReference("integInitialMissingSchema", 1);
        PipelineClusterConfig initialConfigWithMissingSchema = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, missingSchemaRef, "topicInitialMissingSchema");

        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        String fullMissingSchemaKey = getFullSchemaKey(missingSchemaRef.subject(), missingSchemaRef.version());

        // Seed the cluster config
        seedConsulKv(fullClusterKey, initialConfigWithMissingSchema);

        // Ensure the referenced schema is NOT in Consul
        testKvClient.deleteKey(fullMissingSchemaKey);
        LOG.info("integration_initialLoad_missingSchema: Ensured schema key {} is deleted for initial load.", fullMissingSchemaKey);

        // Configure mock validator:
        // It should be called with initialConfigWithMissingSchema.
        // The schemaProvider given to it should return Optional.empty() for missingSchemaRef.
        // In this case, the validator should deem the config invalid.
        when(mockValidator.validate(
                eq(initialConfigWithMissingSchema),
                argThat(provider -> !provider.apply(missingSchemaRef).isPresent()) // Verifies schema is missing from provider
        )).thenReturn(ValidationResult.invalid(Collections.singletonList("Validation Error: Schema " + missingSchemaRef + " could not be resolved")));

        // --- Act: Initialize DCM ---
        LOG.info("integration_initialLoad_missingSchema: Initializing DynamicConfigurationManager for cluster '{}'...", TEST_EXECUTION_CLUSTER);
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
        LOG.info("integration_initialLoad_missingSchema: Initialization complete (expected to proceed to watch setup).");

        // --- Verify Initial Load Failure (due to missing schema leading to validation failure) ---
        ClusterConfigUpdateEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 2, TimeUnit.SECONDS); // Short poll
        assertNull(initialEvent, "Should NOT have received a successful config update event due to missing schema during initial load");

        Optional<PipelineClusterConfig> cachedConfigAfterInit = testCachedConfigHolder.getCurrentConfig();
        assertFalse(cachedConfigAfterInit.isPresent(), "Config should NOT be in cache after initial load with missing schema");
        LOG.info("integration_initialLoad_missingSchema: Verified no config cached and no successful event published.");

        // --- Verify Watch is Active by seeding a new, fully valid config AND its schema ---
        LOG.info("integration_initialLoad_missingSchema: Attempting to seed a fully valid config and its schema to check if watch is active...");

        SchemaReference nowPresentSchemaRef = new SchemaReference("integNowPresentSchema", 1);
        PipelineClusterConfig subsequentValidConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, nowPresentSchemaRef, "topicSubsequentlyValid");
        SchemaVersionData nowPresentSchemaData = createDummySchemaData(nowPresentSchemaRef.subject(), nowPresentSchemaRef.version(), "{\"type\":\"number\"}");
        String fullNowPresentSchemaKey = getFullSchemaKey(nowPresentSchemaRef.subject(), nowPresentSchemaRef.version());

        // Clean and seed the new schema
        testKvClient.deleteKey(fullNowPresentSchemaKey);
        seedConsulKv(fullNowPresentSchemaKey, nowPresentSchemaData);

        // Validator for the new, valid config (this time schema provider WILL find the schema)
        when(mockValidator.validate(
                eq(subsequentValidConfig),
                argThat(provider -> provider.apply(nowPresentSchemaRef).isPresent() &&
                        nowPresentSchemaData.schemaContent().equals(provider.apply(nowPresentSchemaRef).get()))
        )).thenReturn(ValidationResult.valid());

        // Seed the new valid cluster config
        seedConsulKv(fullClusterKey, subsequentValidConfig);
        LOG.info("integration_initialLoad_missingSchema: Subsequent valid config and its schema seeded.");

        ClusterConfigUpdateEvent recoveryEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 15, TimeUnit.SECONDS);
        assertNotNull(recoveryEvent, "Should have received an event when a subsequent valid config (with schema) appeared on the watch");
        assertTrue(recoveryEvent.oldConfig().isEmpty(), "Old config in recovery event should be empty (as initial load effectively failed)");
        assertEquals(subsequentValidConfig, recoveryEvent.newConfig(), "New config in recovery event should match the valid seeded config");

        Optional<PipelineClusterConfig> cachedConfigAfterRecovery = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterRecovery.isPresent(), "Config should be in cache after valid config discovered by watch");
        assertEquals(subsequentValidConfig, cachedConfigAfterRecovery.get());
        assertEquals(nowPresentSchemaData.schemaContent(), testCachedConfigHolder.getSchemaContent(nowPresentSchemaRef).orElse(null), "The new schema should be cached");
        LOG.info("integration_initialLoad_missingSchema: Watch successfully picked up a subsequent valid configuration and its schema.");

        // Cleanup
        testKvClient.deleteKey(fullNowPresentSchemaKey);
    }

    @Test
    @DisplayName("Integration: Watch update - schema fetch throws RuntimeException, keeps old config")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_watchUpdate_schemaFetchThrowsRuntimeException_keepsOldConfig() throws Exception {
        // --- Setup Initial Valid Config ---
        PipelineClusterConfig initialValidConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicInitialForSchemaFetchFail");
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        seedConsulKv(fullClusterKey, initialValidConfig);
        when(mockValidator.validate(eq(initialValidConfig), any()))
                .thenReturn(ValidationResult.valid());

        // Spy on the realConsulConfigFetcher bean
        KiwiprojectConsulConfigFetcher spiedFetcher = spy(this.realConsulConfigFetcher);

        // IMPORTANT: Construct a NEW DynamicConfigurationManager for THIS TEST that uses the spy
        DynamicConfigurationManagerImpl localDcmForTest = new DynamicConfigurationManagerImpl(
                TEST_EXECUTION_CLUSTER,
                spiedFetcher, // Use the spied fetcher
                mockValidator,
                testCachedConfigHolder,
                eventPublisher
        );

        LOG.info("integration_watchUpdate_schemaFetchThrowsRT: Initializing DCM with spied fetcher...");
        localDcmForTest.initialize(TEST_EXECUTION_CLUSTER); // Initialize this local instance

        // --- Verify Initial Load (using localDcmForTest) ---
        ClusterConfigUpdateEvent initialLoadEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(initialLoadEvent, "Should have received an initial load event");
        assertEquals(initialValidConfig, initialLoadEvent.newConfig());
        assertEquals(initialValidConfig, testCachedConfigHolder.getCurrentConfig().orElse(null));
        LOG.info("integration_watchUpdate_schemaFetchThrowsRT: Initial load verified.");
        testApplicationEventListener.clear();

        // --- Setup for Watch Update (where schema fetch will throw) ---
        SchemaReference problematicSchemaRef = new SchemaReference("integSchemaFetchProblem", 1);
        PipelineClusterConfig newConfigWithProblematicSchema = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, problematicSchemaRef, "topicNewProblematicSchema");

        RuntimeException simulatedSchemaFetchException = new RuntimeException("Simulated Consul/Network error during schema fetch!");
        doThrow(simulatedSchemaFetchException)
                .when(spiedFetcher).fetchSchemaVersionData(eq(problematicSchemaRef.subject()), eq(problematicSchemaRef.version()));

        // --- Act: Trigger Watch Update by changing Consul data ---
        LOG.info("integration_watchUpdate_schemaFetchThrowsRT: Seeding new config (problematic schema fetch) to trigger watch...");
        seedConsulKv(fullClusterKey, newConfigWithProblematicSchema);
        LOG.info("integration_watchUpdate_schemaFetchThrowsRT: New config (problematic schema fetch) seeded.");

        // --- Verify Behavior after Schema Fetch Throws Exception ---
        ClusterConfigUpdateEvent eventAfterFetchError = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);

        if (eventAfterFetchError != null) {
            LOG.warn("integration_watchUpdate_schemaFetchThrowsRT: Polled an event: {}. This should not be for the new config with schema fetch error.", eventAfterFetchError);
            assertNotEquals(newConfigWithProblematicSchema, eventAfterFetchError.newConfig(),
                    "Event's newConfig should not be the one with the schema fetch error.");
        } else {
            LOG.info("integration_watchUpdate_schemaFetchThrowsRT: Correctly received no new successful update event after config with schema fetch error.");
        }

        Optional<PipelineClusterConfig> cachedConfigAfterFetchError = testCachedConfigHolder.getCurrentConfig();
        assertTrue(cachedConfigAfterFetchError.isPresent(), "Cache should still contain a config");
        assertEquals(initialValidConfig, cachedConfigAfterFetchError.get(), "Cache should still hold the initial valid config");
        LOG.info("integration_watchUpdate_schemaFetchThrowsRT: Verified cache still holds the old valid configuration.");

        verify(spiedFetcher).fetchSchemaVersionData(eq(problematicSchemaRef.subject()), eq(problematicSchemaRef.version()));
        LOG.info("integration_watchUpdate_schemaFetchThrowsRT: Verified schema fetch was attempted for the problematic schema.");

        // Shutdown the locally created DCM
        localDcmForTest.shutdown();
    }

    @Test
    @DisplayName("Integration: initialize() - when consulConfigFetcher.connect() fails, throws ConfigurationManagerInitializationException")
    @Timeout(value = 15, unit = TimeUnit.SECONDS) // Shorter timeout, not waiting for watches
    void integration_initialize_whenConnectFails_throwsInitializationException() throws InterruptedException {
        // Spy on the realConsulConfigFetcher bean
        KiwiprojectConsulConfigFetcher spiedFetcher = spy(this.realConsulConfigFetcher);

        // IMPORTANT: Construct a NEW DynamicConfigurationManager for THIS TEST that uses the spy
        DynamicConfigurationManagerImpl localDcmForTest = new DynamicConfigurationManagerImpl(
                TEST_EXECUTION_CLUSTER,
                spiedFetcher, // Use the spied fetcher
                mockValidator,
                testCachedConfigHolder,
                eventPublisher
        );

        // Configure the spied connect() method to throw an exception
        RuntimeException simulatedConnectException = new RuntimeException("Simulated KCCF.connect() failure!");
        doThrow(simulatedConnectException).when(spiedFetcher).connect();

        // --- Act & Assert ---
        LOG.info("integration_initialize_connectFails: Attempting to initialize DCM where connect() will fail...");
        ConfigurationManagerInitializationException thrown = assertThrows(
                ConfigurationManagerInitializationException.class,
                () -> localDcmForTest.initialize(TEST_EXECUTION_CLUSTER),
                "initialize() should throw ConfigurationManagerInitializationException when connect() fails"
        );

        LOG.info("integration_initialize_connectFails: Correctly caught ConfigurationManagerInitializationException: {}", thrown.getMessage());
        assertNotNull(thrown.getCause(), "The original exception should be the cause");
        assertSame(simulatedConnectException, thrown.getCause(), "Cause should be the simulated connect exception");
        assertEquals("Failed to initialize Consul connection or watch for cluster " + TEST_EXECUTION_CLUSTER, thrown.getMessage());

        // --- Verify Interactions ---
        // Verify connect() was attempted
        verify(spiedFetcher).connect();

        // Verify that subsequent operations were NOT attempted
        verify(spiedFetcher, never()).fetchPipelineClusterConfig(anyString());
        verify(spiedFetcher, never()).watchClusterConfig(anyString(), any());

        // Verify cache and event listener were not affected
        assertFalse(testCachedConfigHolder.getCurrentConfig().isPresent(), "Cache should be empty if connect failed");
        assertNull(testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS), "No event should be published if connect failed");
        LOG.info("integration_initialize_connectFails: Verifications complete.");

        // No need to call localDcmForTest.shutdown() as initialize failed before watch setup
    }
}