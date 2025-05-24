package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
// DynamicConfigurationManagerFactory might not be needed if SUT is directly injected and we don't manually create instances
// import com.krickert.search.config.consul.factory.DynamicConfigurationManagerFactory;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.schema.model.SchemaCompatibility;
import com.krickert.search.config.schema.model.SchemaType;
import com.krickert.search.config.schema.model.SchemaVersionData;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
// Removed: import static org.mockito.ArgumentMatchers.any;
// Removed: import static org.mockito.ArgumentMatchers.eq;
// Removed: import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;


@MicronautTest(startApplication = false, environments = {"test-dynamic-manager"})
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
@Property(name = "app.config.cluster-name", value = DynamicConfigurationManagerImplMicronautTest.DEFAULT_PROPERTY_CLUSTER)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicConfigurationManagerImplMicronautTest {

    static final String DEFAULT_PROPERTY_CLUSTER = "propertyClusterDefault";
    static final String TEST_EXECUTION_CLUSTER = "dynamicManagerTestCluster";
    private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerImplMicronautTest.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher;

    @Inject
    KiwiprojectConsulConfigFetcher realConsulConfigFetcher;

    @Inject
    TestApplicationEventListener testApplicationEventListener;

    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;

    // @Inject
    // private DynamicConfigurationManagerFactory dynamicConfigurationManagerFactory; // Likely not needed now

    private String clusterConfigKeyPrefix;
    private String schemaVersionsKeyPrefix;
    private int appWatchSeconds;

    @Inject
    private DynamicConfigurationManagerImpl dynamicConfigurationManager; // SUT

    @Inject
    private CachedConfigHolder cachedConfigHolder;

    // Removed: private ConfigurationValidator mockValidator;

    @BeforeAll
    void classSetUp() {
        clusterConfigKeyPrefix = realConsulConfigFetcher.clusterConfigKeyPrefix;
        schemaVersionsKeyPrefix = realConsulConfigFetcher.schemaVersionsKeyPrefix;
        appWatchSeconds = realConsulConfigFetcher.appWatchSeconds;
        LOG.info("DynamicConfigurationManagerImplMicronautTest class setup complete. DCM initialized by @PostConstruct for: {}", DEFAULT_PROPERTY_CLUSTER);
    }

    @BeforeEach
    void setUpPerTest() {
        deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
        deleteConsulKeysForCluster(DEFAULT_PROPERTY_CLUSTER);
        testApplicationEventListener.clear();
        // No mockValidator reset needed as we're using the real one.
    }

    @AfterEach
    void tearDownPerTest() {
        deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
        deleteConsulKeysForCluster(DEFAULT_PROPERTY_CLUSTER);
        LOG.info("Test method finished, keys for cluster {} and {} potentially cleaned.", TEST_EXECUTION_CLUSTER, DEFAULT_PROPERTY_CLUSTER);
    }

    private void deleteConsulKeysForCluster(String clusterName) {
        LOG.debug("Attempting to clean Consul key for cluster: {}", clusterName);
        consulBusinessOperationsService.deleteClusterConfiguration(clusterName).block();
    }

    private String getFullClusterKey(String clusterName) {
        return clusterConfigKeyPrefix + clusterName;
    }

    private String getFullSchemaKey(String subject, int version) {
        return String.format("%s%s/%d", schemaVersionsKeyPrefix, subject, version);
    }

    private PipelineClusterConfig createDummyClusterConfig(String name, String... topics) {
        // This config is simple and should pass basic validation if no complex rules are hit.
        return PipelineClusterConfig.builder()
                .clusterName(name)
                .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap())) // Valid empty graph
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))   // Valid empty modules
                .defaultPipelineName(name + "-default")
                .allowedKafkaTopics(topics != null ? Set.of(topics) : Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
    }

    private PipelineClusterConfig createClusterConfigWithSchema(String name, SchemaReference schemaRef, String... topics) {
        // Ensure the module implementation ID exists if it's used in a step's processorInfo
        PipelineModuleConfiguration moduleWithSchema = new PipelineModuleConfiguration(
                "ModuleWithSchema",
                "module_schema_impl_id", // This ID needs to be known if steps reference it
                schemaRef,
                Map.of("sampleConfigKey", "sampleValue") // Add some custom config
        );
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(moduleWithSchema.implementationId(), moduleWithSchema));

        // Create a simple step that uses this module to make the schema reference relevant
        PipelineStepConfig stepUsingSchema = PipelineStepConfig.builder()
                .stepName("stepUsingSchema")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo(null, "module_schema_impl_id")) // References the module
                // customConfig will be validated against the schemaRef from the module
                .customConfig(new PipelineStepConfig.JsonConfigOptions(
                        objectMapper.valueToTree(Map.of("sampleConfigKey", "sampleValue")), // Config that should be valid
                        Collections.emptyMap()
                ))
                .build();
        PipelineConfig pipeline = new PipelineConfig(name + "-pipe", Map.of("stepUsingSchema", stepUsingSchema));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(pipeline.name(), pipeline));


        return PipelineClusterConfig.builder()
                .clusterName(name)
                .pipelineGraphConfig(graphConfig) // Add graph
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName(name + "-default")
                .allowedKafkaTopics(topics != null ? Set.of(topics) : Collections.emptySet())
                .allowedGrpcServices(Set.of("module_schema_impl_id")) // Allow the module if it's a gRPC service
                .build();
    }

    private SchemaVersionData createDummySchemaData(String subject, int version, String content) {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new SchemaVersionData(
                (long) (Math.random() * 1000000), subject, version, content,
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, createdAt, "Integration test schema " + subject + " v" + version
        );
    }

    private void seedConsulKv(String key, Object object) throws JsonProcessingException {
        LOG.info("Seeding Consul KV: {} = {}", key,
                object.toString().length() > 150 ? object.toString().substring(0, 150) + "..." : object.toString());

        if (key.startsWith(clusterConfigKeyPrefix)) {
            String clusterName = key.substring(clusterConfigKeyPrefix.length());
            Boolean result = consulBusinessOperationsService.storeClusterConfiguration(clusterName, object).block();
            assertTrue(result != null && result, "Failed to seed cluster configuration for key: " + key);
        } else if (key.startsWith(schemaVersionsKeyPrefix)) {
            String path = key.substring(schemaVersionsKeyPrefix.length());
            String[] parts = path.split("/");
            if (parts.length == 2) {
                String subject = parts[0];
                int version = Integer.parseInt(parts[1]);
                Boolean result = consulBusinessOperationsService.storeSchemaVersion(subject, version, object).block();
                assertTrue(result != null && result, "Failed to seed schema version for key: " + key);
            } else {
                Boolean result = consulBusinessOperationsService.putValue(key, object).block();
                assertTrue(result != null && result, "Failed to seed Consul KV for key: " + key);
            }
        } else {
            Boolean result = consulBusinessOperationsService.putValue(key, object).block();
            assertTrue(result != null && result, "Failed to seed Consul KV for key: " + key);
        }
        try {
            TimeUnit.MILLISECONDS.sleep(500); // Increased sleep for Consul to process, KVCache has its own watch interval
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Integration: Successful initial load with schema, then watch update")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_initialLoad_thenWatchUpdate() throws Exception {
        SchemaReference schemaRef1 = new SchemaReference("integSchemaSubject1", 1);
        // CORRECTED: Schema content must match the customConfig used in createClusterConfigWithSchema
        SchemaVersionData schemaData1 = createDummySchemaData(schemaRef1.subject(), schemaRef1.version(),
                "{\"type\":\"object\",\"properties\":{\"sampleConfigKey\":{\"type\":\"string\"}}}");
        PipelineClusterConfig initialConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, schemaRef1, "topicInit1");

        String fullSchemaKey1 = getFullSchemaKey(schemaRef1.subject(), schemaRef1.version());
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        // Ensure clean state for this specific schema
        consulBusinessOperationsService.deleteSchemaVersion(schemaRef1.subject(), schemaRef1.version()).block();

        // Seed schema into Consul FIRST
        seedConsulKv(fullSchemaKey1, schemaData1);
        // Seed initial cluster config into Consul
        seedConsulKv(fullClusterKey, initialConfig);

        // We are using the REAL validator, so no 'when(mockValidator...)'
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        // Refined polling for the specific initial event
        PipelineClusterConfigChangeEvent initialEvent = null;
        long initialEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15); // Increased timeout
        LOG.info("Polling for initial load event for config: {}", initialConfig.clusterName());
        while (System.currentTimeMillis() < initialEndTime) {
            PipelineClusterConfigChangeEvent polled = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polled != null) {
                LOG.info("Polled event during initial load: newConfig cluster='{}', isDeletion={}", polled.clusterName(), polled.isDeletion());
                if (TEST_EXECUTION_CLUSTER.equals(polled.clusterName()) && !polled.isDeletion() && initialConfig.equals(polled.newConfig())) {
                    initialEvent = polled;
                    LOG.info("Matched expected initial event.");
                    break;
                }
            }
        }
        assertNotNull(initialEvent, "Should have received the specific initial load event for " + initialConfig.clusterName());
        // ... rest of assertions for initialEvent ...
        assertFalse(initialEvent.isDeletion());
        assertEquals(initialConfig, initialEvent.newConfig());
        assertEquals(TEST_EXECUTION_CLUSTER, initialEvent.clusterName());
        assertEquals(initialConfig, cachedConfigHolder.getCurrentConfig().orElse(null));
        assertEquals(schemaData1.schemaContent(), cachedConfigHolder.getSchemaContent(schemaRef1).orElse(null));
        LOG.info("Initial load verified for {}.", initialConfig.clusterName());
        testApplicationEventListener.clear(); // Clear before next action

        // --- Watch Update ---
        // The updatedConfig uses the same schemaRef1, so schemaData1 is still valid for it.
        PipelineClusterConfig updatedConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, schemaRef1, "topicInit1", "topicUpdate2");
        // No validator mocking
        LOG.info("Seeding updated config for watch: {}", updatedConfig.clusterName());
        seedConsulKv(fullClusterKey, updatedConfig);

        PipelineClusterConfigChangeEvent updateEvent = null;
        long updateEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 20); // Increased timeout
        LOG.info("Polling for update event for config: {}", updatedConfig.clusterName());
        while (System.currentTimeMillis() < updateEndTime) {
            PipelineClusterConfigChangeEvent polled = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polled != null) {
                LOG.info("Polled event during update: newConfig cluster='{}', isDeletion={}", polled.clusterName(), polled.isDeletion());
                if (TEST_EXECUTION_CLUSTER.equals(polled.clusterName()) && !polled.isDeletion() && updatedConfig.equals(polled.newConfig())) {
                    updateEvent = polled;
                    LOG.info("Matched expected update event.");
                    break;
                }
            }
        }
        assertNotNull(updateEvent, "Should have received the specific update event from watch for " + updatedConfig.clusterName());
        // ... rest of assertions for updateEvent ...
        assertFalse(updateEvent.isDeletion());
        assertEquals(updatedConfig, updateEvent.newConfig());
        assertEquals(TEST_EXECUTION_CLUSTER, updateEvent.clusterName());
        assertEquals(updatedConfig, cachedConfigHolder.getCurrentConfig().orElse(null));
        LOG.info("Watch update verified for {}.", updatedConfig.clusterName());
        testApplicationEventListener.clear();


        // --- Deletion ---
        LOG.info("Deleting config from Consul: {}", TEST_EXECUTION_CLUSTER);
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_EXECUTION_CLUSTER).block();
        // No need for manual sleep if KVCache is quick enough or pollEvent has timeout

        PipelineClusterConfigChangeEvent deletionEvent = null;
        long deleteEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15);
        LOG.info("Polling for deletion event for cluster: {}", TEST_EXECUTION_CLUSTER);
        while (System.currentTimeMillis() < deleteEndTime) {
            PipelineClusterConfigChangeEvent polled = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polled != null) {
                LOG.info("Polled event during deletion: clusterName='{}', isDeletion={}", polled.clusterName(), polled.isDeletion());
                if (TEST_EXECUTION_CLUSTER.equals(polled.clusterName()) && polled.isDeletion()) {
                    deletionEvent = polled;
                    LOG.info("Matched expected deletion event.");
                    break;
                }
            }
        }
        assertNotNull(deletionEvent, "Should have received a deletion event for " + TEST_EXECUTION_CLUSTER);
        assertTrue(deletionEvent.isDeletion());
        assertNull(deletionEvent.newConfig());
        assertEquals(TEST_EXECUTION_CLUSTER, deletionEvent.clusterName());
        assertFalse(cachedConfigHolder.getCurrentConfig().isPresent());
        LOG.info("Deletion verified for {}.", TEST_EXECUTION_CLUSTER);

        consulBusinessOperationsService.deleteSchemaVersion(schemaRef1.subject(), schemaRef1.version()).block();
    }

    @Test
    @DisplayName("Integration: Initial load - no config found, then config appears via watch")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_initialLoad_noConfigFound_thenAppearsOnWatch() throws Exception {
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_EXECUTION_CLUSTER).block();

        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        PipelineClusterConfigChangeEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 2, TimeUnit.SECONDS);
        assertNull(initialEvent);
        assertFalse(cachedConfigHolder.getCurrentConfig().isPresent());

        PipelineClusterConfig newConfigAppearing = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicAppeared1");
        // No validator mocking
        seedConsulKv(fullClusterKey, newConfigAppearing);

        PipelineClusterConfigChangeEvent discoveredEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 15, TimeUnit.SECONDS);
        assertNotNull(discoveredEvent);
        assertFalse(discoveredEvent.isDeletion());
        assertEquals(newConfigAppearing, discoveredEvent.newConfig());
        assertEquals(TEST_EXECUTION_CLUSTER, discoveredEvent.clusterName());
        assertEquals(newConfigAppearing, cachedConfigHolder.getCurrentConfig().orElse(null));
    }

    @Test
    @DisplayName("Integration: Initial load - config present but fails validation")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void integration_initialLoad_configFailsValidation() throws Exception {
        // Create a config that will fail ReferentialIntegrityValidator
        PipelineStepConfig.ProcessorInfo invalidProcInfo = new PipelineStepConfig.ProcessorInfo("non-existent-service", null);
        PipelineStepConfig invalidStep = PipelineStepConfig.builder().stepName("s1").stepType(StepType.PIPELINE).processorInfo(invalidProcInfo).build();
        PipelineConfig invalidPipe = new PipelineConfig("p1", Map.of("s1", invalidStep));
        PipelineClusterConfig invalidInitialConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_EXECUTION_CLUSTER)
                .pipelineGraphConfig(new PipelineGraphConfig(Map.of("p1", invalidPipe)))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap())) // No modules defined
                .allowedGrpcServices(Collections.emptySet()) // Service not in whitelist
                .build();

        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        seedConsulKv(fullClusterKey, invalidInitialConfig);

        // No validator mocking
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        PipelineClusterConfigChangeEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 2, TimeUnit.SECONDS);
        assertNull(initialEvent);
        assertFalse(cachedConfigHolder.getCurrentConfig().isPresent());

        // Seed a valid config to ensure watch is still active
        PipelineClusterConfig subsequentValidConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicValidAfterFail");
        seedConsulKv(fullClusterKey, subsequentValidConfig);

        PipelineClusterConfigChangeEvent recoveryEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 15, TimeUnit.SECONDS);
        assertNotNull(recoveryEvent);
        assertFalse(recoveryEvent.isDeletion());
        assertEquals(subsequentValidConfig, recoveryEvent.newConfig());
        assertEquals(subsequentValidConfig, cachedConfigHolder.getCurrentConfig().orElse(null));
    }

    @Test
    @DisplayName("Integration: Config present, then deleted via watch")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_configPresent_thenDeletedViaWatch() throws Exception {
        PipelineClusterConfig initialConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicToDelete1");
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        seedConsulKv(fullClusterKey, initialConfig);

        // No validator mocking
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        PipelineClusterConfigChangeEvent initialLoadEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(initialLoadEvent);
        assertEquals(initialConfig, initialLoadEvent.newConfig());
        assertEquals(initialConfig, cachedConfigHolder.getCurrentConfig().orElse(null));

        consulBusinessOperationsService.deleteClusterConfiguration(TEST_EXECUTION_CLUSTER).block();
        TimeUnit.MILLISECONDS.sleep(appWatchSeconds * 1000L / 2 + 500);

        PipelineClusterConfigChangeEvent deletionEvent = null;
        long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15);
        while (System.currentTimeMillis() < endTime) {
            PipelineClusterConfigChangeEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polledEvent != null && polledEvent.isDeletion()) {
                deletionEvent = polledEvent;
                break;
            }
        }
        assertNotNull(deletionEvent);
        assertTrue(deletionEvent.isDeletion());
        assertNull(deletionEvent.newConfig());
        assertEquals(TEST_EXECUTION_CLUSTER, deletionEvent.clusterName());
        assertFalse(cachedConfigHolder.getCurrentConfig().isPresent());
    }

    @Test
    @DisplayName("Integration: Watch update - new config fails validation, keeps old config")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_watchUpdate_newConfigFailsValidation_keepsOldConfig() throws Exception {
        // --- Setup Initial Valid Config ---
        SchemaReference initialSchemaRef = new SchemaReference("integSchemaInitial", 1);
        SchemaVersionData initialSchemaData = createDummySchemaData(initialSchemaRef.subject(), initialSchemaRef.version(),
                "{\"type\":\"object\",\"properties\":{\"sampleConfigKey\":{\"type\":\"string\"}}}");
        PipelineClusterConfig initialValidConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, initialSchemaRef, "topicInitialValid");

        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        String fullInitialSchemaKey = getFullSchemaKey(initialSchemaRef.subject(), initialSchemaRef.version());

        consulBusinessOperationsService.deleteSchemaVersion(initialSchemaRef.subject(), initialSchemaRef.version()).block();
        seedConsulKv(fullInitialSchemaKey, initialSchemaData);
        seedConsulKv(fullClusterKey, initialValidConfig);

        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        // Poll for the initial valid event
        PipelineClusterConfigChangeEvent initialLoadEvent = null;
        long initialEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15);
        LOG.info("Polling for initial load event for config: {}", initialValidConfig.clusterName());
        while (System.currentTimeMillis() < initialEndTime) {
            PipelineClusterConfigChangeEvent polled = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polled != null && TEST_EXECUTION_CLUSTER.equals(polled.clusterName()) && !polled.isDeletion() && initialValidConfig.equals(polled.newConfig())) {
                initialLoadEvent = polled;
                LOG.info("Matched expected initial event.");
                break;
            }
        }
        assertNotNull(initialLoadEvent, "Should have received an initial load event for " + initialValidConfig.clusterName());
        assertEquals(initialValidConfig, cachedConfigHolder.getCurrentConfig().orElse(null));
        LOG.info("Initial load verified for {}.", initialValidConfig.clusterName());
        testApplicationEventListener.clear(); // Clear before the invalid update

        // --- Setup for Watch Update (which will fail validation) ---
        PipelineStepConfig.ProcessorInfo invalidProcInfo = new PipelineStepConfig.ProcessorInfo(null, "non-existent-module-for-update");
        PipelineStepConfig stepWithInvalidProc = PipelineStepConfig.builder().stepName("sNewInvalid").stepType(StepType.PIPELINE).processorInfo(invalidProcInfo).build();
        PipelineConfig pipeWithInvalidStep = new PipelineConfig("pNewInvalid", Map.of("sNewInvalid", stepWithInvalidProc));
        PipelineClusterConfig newInvalidConfigFromWatch = PipelineClusterConfig.builder()
                .clusterName(TEST_EXECUTION_CLUSTER)
                .pipelineGraphConfig(new PipelineGraphConfig(Map.of("pNewInvalid", pipeWithInvalidStep)))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .allowedKafkaTopics(Set.of("topicNewInvalid"))
                .build();

        LOG.info("Seeding new (invalid) config to trigger watch: {}", newInvalidConfigFromWatch.clusterName());
        seedConsulKv(fullClusterKey, newInvalidConfigFromWatch);

        // --- Verify Behavior after Failed Validation on Watch ---
        // We expect NO successful update event for newInvalidConfigFromWatch.
        // Poll for a while to see if any event comes through.
        boolean foundUnexpectedSuccessEvent = false;
        long pollEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 10);
        LOG.info("Polling for events after seeding invalid config...");
        while (System.currentTimeMillis() < pollEndTime) {
            PipelineClusterConfigChangeEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polledEvent != null) {
                LOG.warn("Polled an event after seeding invalid config: {}", polledEvent);
                if (newInvalidConfigFromWatch.equals(polledEvent.newConfig()) && !polledEvent.isDeletion()) {
                    foundUnexpectedSuccessEvent = true;
                    break;
                }
            }
        }

        assertFalse(foundUnexpectedSuccessEvent, "Should NOT have received a successful update event for the new invalid config.");

        // CRITICAL: Verify that the cache still holds the OLD VALID config
        assertEquals(Optional.of(initialValidConfig), cachedConfigHolder.getCurrentConfig(),
                "Cache should still hold the initial valid config after invalid update attempt.");
        LOG.info("Verified cache still holds the old valid configuration after invalid update attempt.");

        consulBusinessOperationsService.deleteSchemaVersion(initialSchemaRef.subject(), initialSchemaRef.version()).block();
    }

    @Test
    @DisplayName("Integration: Watch update - new config references missing schema, keeps old config")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void integration_watchUpdate_newConfigMissingSchema_keepsOldConfig() throws Exception {
        PipelineClusterConfig initialValidConfig = createDummyClusterConfig(TEST_EXECUTION_CLUSTER, "topicInitialValid");
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
        seedConsulKv(fullClusterKey, initialValidConfig);

        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);

        // Poll for the initial valid event
        PipelineClusterConfigChangeEvent initialLoadEvent = null;
        long initialEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15);
        LOG.info("Polling for initial load event for config: {}", initialValidConfig.clusterName());
        while (System.currentTimeMillis() < initialEndTime) {
            PipelineClusterConfigChangeEvent polled = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polled != null && TEST_EXECUTION_CLUSTER.equals(polled.clusterName()) && !polled.isDeletion() && initialValidConfig.equals(polled.newConfig())) {
                initialLoadEvent = polled;
                LOG.info("Matched expected initial event.");
                break;
            }
        }
        assertNotNull(initialLoadEvent, "Should have received an initial load event for " + initialValidConfig.clusterName());
        assertEquals(initialValidConfig, cachedConfigHolder.getCurrentConfig().orElse(null));
        LOG.info("Initial load verified for {}.", initialValidConfig.clusterName());
        testApplicationEventListener.clear(); // Clear before the update with missing schema

        // --- Setup for Watch Update (with a config referencing a MISSING schema) ---
        SchemaReference missingSchemaRef = new SchemaReference("integSchemaSubjectMissing", 1);
        PipelineClusterConfig newConfigMissingSchema = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, missingSchemaRef, "topicNewMissingSchema");

        // Ensure the schema is NOT in Consul (using the path the delegate expects for CustomConfigSchemaValidator)
        // And also ensure it's not in the versioned path that DCM's fetchSchemaVersionData uses
        String delegateSchemaKey = "config/pipeline/schemas/" + missingSchemaRef.subject();
        consulBusinessOperationsService.deleteSchemaVersion(missingSchemaRef.subject(), missingSchemaRef.version()).block();
        LOG.info("Ensured schema for {} is deleted from both potential paths.", missingSchemaRef.toIdentifier());

        LOG.info("Seeding new config (referencing missing schema) to trigger watch: {}", newConfigMissingSchema.clusterName());
        seedConsulKv(fullClusterKey, newConfigMissingSchema);

        // --- Verify Behavior after Missing Schema on Watch ---
        boolean foundUnexpectedSuccessEvent = false;
        long pollEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 10);
        LOG.info("Polling for events after seeding config with missing schema...");
        while(System.currentTimeMillis() < pollEndTime) {
            PipelineClusterConfigChangeEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polledEvent != null) {
                LOG.warn("Polled an event after seeding config with missing schema: {}", polledEvent);
                if (newConfigMissingSchema.equals(polledEvent.newConfig()) && !polledEvent.isDeletion()) {
                    foundUnexpectedSuccessEvent = true;
                    break;
                }
            }
        }
        assertFalse(foundUnexpectedSuccessEvent, "Should NOT have received a successful update event for the config with the missing schema.");

        // CRITICAL: Verify that the cache still holds the OLD VALID config
        assertEquals(Optional.of(initialValidConfig), cachedConfigHolder.getCurrentConfig(),
                "Cache should still hold the initial valid config after attempting to load config with missing schema.");
        LOG.info("Verified cache still holds the old valid configuration after attempting to load config with missing schema.");
    }

    // Test-specific event listener bean
    @Singleton
    static class TestApplicationEventListener {
        private static final Logger EVENT_LISTENER_LOG = LoggerFactory.getLogger(TestApplicationEventListener.class);
        private final BlockingQueue<PipelineClusterConfigChangeEvent> receivedEvents = new ArrayBlockingQueue<>(10);

        @io.micronaut.runtime.event.annotation.EventListener
        void onClusterConfigUpdate(PipelineClusterConfigChangeEvent event) {
            EVENT_LISTENER_LOG.info("TestApplicationEventListener received PipelineClusterConfigChangeEvent for cluster '{}'. Is Deletion: {}",
                    event.clusterName(), event.isDeletion());
            if (TEST_EXECUTION_CLUSTER.equals(event.clusterName()) || DEFAULT_PROPERTY_CLUSTER.equals(event.clusterName())) {
                receivedEvents.offer(event);
            } else {
                EVENT_LISTENER_LOG.warn("TestApplicationEventListener ignored event for different cluster: {}. Expected: {} or {}",
                        event.clusterName(), TEST_EXECUTION_CLUSTER, DEFAULT_PROPERTY_CLUSTER);
            }
        }

        public PipelineClusterConfigChangeEvent pollEvent(long timeout, TimeUnit unit) throws InterruptedException {
            return receivedEvents.poll(timeout, unit);
        }

        public void clear() {
            receivedEvents.clear();
        }
    }
}