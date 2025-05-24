package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
// NEW EVENT TYPE
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.consul.factory.DynamicConfigurationManagerFactory;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
import com.krickert.search.config.schema.model.SchemaCompatibility;
import com.krickert.search.config.schema.model.SchemaType;
import com.krickert.search.config.schema.model.SchemaVersionData;
import io.micronaut.context.annotation.Property;
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

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false, environments = {"test-dynamic-manager-full"})
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
@Property(name = "app.config.cluster-name", value = DynamicConfigurationManagerFullIntegrationTest.DEFAULT_PROPERTY_CLUSTER)
class DynamicConfigurationManagerFullIntegrationTest {

    static final String DEFAULT_PROPERTY_CLUSTER = "propertyClusterFullDefault";
    static final String TEST_EXECUTION_CLUSTER = "dynamicManagerFullTestCluster";
    private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerFullIntegrationTest.class);
    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;
    @Inject
    KiwiprojectConsulConfigFetcher realConsulConfigFetcher;
    @Inject
    CachedConfigHolder testCachedConfigHolder;

    // DefaultConfigurationValidator is injected into DynamicConfigurationManagerImpl by Micronaut
    // No need to inject it separately here unless you want to mock it, which we are not.

    @Inject
    TestApplicationEventListener testApplicationEventListener; // Using the dedicated listener

    @Inject
    DynamicConfigurationManagerFactory dynamicConfigurationManagerFactory;

    private String clusterConfigKeyPrefix;
    private String schemaVersionsKeyPrefix;
    private int appWatchSeconds;

    private DynamicConfigurationManager dynamicConfigurationManager;

    @BeforeEach
    void setUp() {
        clusterConfigKeyPrefix = realConsulConfigFetcher.clusterConfigKeyPrefix;
        schemaVersionsKeyPrefix = realConsulConfigFetcher.schemaVersionsKeyPrefix;
        appWatchSeconds = realConsulConfigFetcher.appWatchSeconds;

        deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
        deleteConsulKeysForCluster(DEFAULT_PROPERTY_CLUSTER);
        testApplicationEventListener.clear();

        // Construct SUT using the factory.
        dynamicConfigurationManager = dynamicConfigurationManagerFactory.createDynamicConfigurationManager(TEST_EXECUTION_CLUSTER);
        LOG.info("DynamicConfigurationManagerImpl (Full Integ) constructed for cluster: {}", TEST_EXECUTION_CLUSTER);
    }

    @AfterEach
    void tearDown() {
        if (dynamicConfigurationManager != null) {
            dynamicConfigurationManager.shutdown();
        }
        deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
        deleteConsulKeysForCluster(DEFAULT_PROPERTY_CLUSTER);
        LOG.info("Test (Full Integ) finished, keys for cluster {} potentially cleaned.", TEST_EXECUTION_CLUSTER);
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

    // In DynamicConfigurationManagerFullIntegrationTest.java

    // Helper to create a fully valid config for happy path tests
    private PipelineClusterConfig createFullyValidClusterConfig(String clusterName, SchemaReference schemaRef, String actualJsonDataForStep) {
        PipelineModuleConfiguration module = PipelineConfigTestUtils.createModuleWithSchema(
                "HappyModule", "happy_module_impl", schemaRef, Map.of("moduleKey", "moduleValue")
        );
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(module.implementationId(), module));

        PipelineStepConfig step1 = null;
        try {
            step1 = PipelineConfigTestUtils.createStep(
                    "happyStep1",
                    StepType.PIPELINE,
                    "happy_module_impl",
                    PipelineConfigTestUtils.createJsonConfigOptions(actualJsonDataForStep),
                    List.of(PipelineConfigTestUtils.createKafkaInput("input-" + clusterName)), // Consumes from external Kafka
                    // Step 1 outputs INTERNALLY to step2
                    Map.of("default", PipelineConfigTestUtils.createInternalOutputTarget("happyStep2"))
            );
        } catch (JsonProcessingException e) {
            fail("Error creating step1 config: " + e.getMessage(), e);
        }

        PipelineStepConfig step2 = PipelineConfigTestUtils.createStep(
                "happyStep2",
                StepType.SINK,
                "happy_module_impl",
                null,
                Collections.emptyList(), // No Kafka input; receives data internally from step1
                null
        );

        Objects.requireNonNull(step1, "step1 should not be null after try-catch");

        PipelineConfig pipeline = PipelineConfigTestUtils.createPipeline("happyPipe", Map.of(step1.stepName(), step1, step2.stepName(), step2));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(pipeline.name(), pipeline));

        return PipelineClusterConfig.builder()
                .clusterName(clusterName)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName(pipeline.name())
                // Only the initial input topic is needed if internal transport is used between steps
                .allowedKafkaTopics(Set.of("input-" + clusterName))
                .allowedGrpcServices(Set.of("happy_module_impl"))
                .build();
    }
    private PipelineClusterConfig createClusterConfigWithSchema(String name, SchemaReference schemaRef, String... topics) {
        PipelineModuleConfiguration moduleWithSchema = new PipelineModuleConfiguration(
                "ModuleWithSchema",
                "module_schema_impl_id",
                schemaRef,
                Map.of("sampleConfigKey", "sampleValue")
        );
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(moduleWithSchema.implementationId(), moduleWithSchema));

        PipelineStepConfig stepUsingSchema = null;
        try {
            stepUsingSchema = PipelineStepConfig.builder()
                    .stepName("stepUsingSchemaFromModule")
                    .stepType(StepType.PIPELINE)
                    .processorInfo(new PipelineStepConfig.ProcessorInfo(null, "module_schema_impl_id"))
                    .customConfig(PipelineConfigTestUtils.createJsonConfigOptions("{\"sampleConfigKey\":\"a string value\"}"))
                    .build();
        } catch (JsonProcessingException e) {
            fail(e);
        }

        PipelineConfig pipeline = new PipelineConfig(name + "-pipe", Map.of(stepUsingSchema.stepName(), stepUsingSchema));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(pipeline.name(), pipeline));

        return PipelineClusterConfig.builder()
                .clusterName(name)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName(name + "-pipe")
                .allowedKafkaTopics(topics != null ? Set.of(topics) : Collections.emptySet())
                .allowedGrpcServices(Set.of("module_schema_impl_id"))
                .build();
    }


    private SchemaVersionData createDummySchemaData(String subject, int version, String content) {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new SchemaVersionData((long) (Math.random() * 1000000), subject, version, content,
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, createdAt, "Integration test schema " + subject + " v" + version);
    }

    private void seedConsulKv(String key, Object object) throws JsonProcessingException {
        LOG.info("Seeding Consul KV (Full Integ): {} = {}", key,
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
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Full Integration: Happy Path - Initial Load, Update, and Delete (All Rules Pass)")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void fullIntegration_happyPath_initialLoad_update_delete() throws Exception {
        // --- 1. Initial Load ---
        SchemaReference schemaRef1 = new SchemaReference("fullIntegHappySchema1", 1);

        // This is the SCHEMA for the step's customConfig
        String schemaForStep1Config = "{\"type\":\"object\", \"properties\":{\"configKey\":{\"type\":\"string\"}}, \"required\":[\"configKey\"]}";
        // This is the actual DATA for the step's customConfig, valid against the schema above
        String actualJsonDataForStep1 = "{\"configKey\":\"someValue\"}";

        PipelineClusterConfig initialConfig = createFullyValidClusterConfig(TEST_EXECUTION_CLUSTER, schemaRef1, actualJsonDataForStep1);
        // The SchemaVersionData in Consul should contain the SCHEMA, not the data
        SchemaVersionData schemaData1 = createDummySchemaData(schemaRef1.subject(), schemaRef1.version(), schemaForStep1Config);

        String fullSchemaKey1 = getFullSchemaKey(schemaRef1.subject(), schemaRef1.version());
        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        // Clean up previous state if any
        consulBusinessOperationsService.deleteSchemaVersion(schemaRef1.subject(), schemaRef1.version()).block();
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_EXECUTION_CLUSTER).block(); // Ensure cluster config is also clean

        seedConsulKv(fullSchemaKey1, schemaData1);
        seedConsulKv(fullClusterKey, initialConfig);

        LOG.info("FullInteg-HappyPath: Initializing DynamicConfigurationManager...");
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
        LOG.info("FullInteg-HappyPath: Initialization complete.");

        // ... rest of the test assertions for initial load, update, delete ...
        // The polling logic for events should now work as the initial load should succeed.

        PipelineClusterConfigChangeEvent initialEvent = null;
        long initialEndTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 25);
        LOG.info("Polling for initial load event for config: {}", initialConfig.clusterName());
        while (System.currentTimeMillis() < initialEndTime) {
            PipelineClusterConfigChangeEvent polled = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polled != null) {
                LOG.info("Polled event during initial load: cluster='{}', isDeletion={}", polled.clusterName(), polled.isDeletion());
                if (TEST_EXECUTION_CLUSTER.equals(polled.clusterName()) && !polled.isDeletion() &&
                        initialConfig.equals(polled.newConfig())) {
                    initialEvent = polled;
                    LOG.info("Matched expected initial event.");
                    break;
                }
            }
        }
        assertNotNull(initialEvent, "Should have received the specific initial load event for " + initialConfig.clusterName());
        assertFalse(initialEvent.isDeletion(), "Initial event should not be a deletion");
        assertEquals(initialConfig, initialEvent.newConfig(), "New config in event should match initial seeded config");
        assertEquals(initialConfig, testCachedConfigHolder.getCurrentConfig().orElse(null), "Cache should hold initial config");
        assertEquals(schemaData1.schemaContent(), testCachedConfigHolder.getSchemaContent(schemaRef1).orElse(null), "Schema should be cached");
        LOG.info("FullInteg-HappyPath: Initial load verified.");
        testApplicationEventListener.clear();

        // --- 2. Watch Update (also valid) ---
        Set<String> updatedTopics = new java.util.HashSet<>(initialConfig.allowedKafkaTopics());
        updatedTopics.add("new-topic-for-update-" + TEST_EXECUTION_CLUSTER);

        PipelineClusterConfig updatedConfig = initialConfig.toBuilder()
                .allowedKafkaTopics(updatedTopics)
                .build();

        LOG.info("FullInteg-HappyPath: Seeding updated config to trigger watch...");
        seedConsulKv(fullClusterKey, updatedConfig);
        LOG.info("FullInteg-HappyPath: Updated config seeded.");

        PipelineClusterConfigChangeEvent updateEvent = null;
        long endTimeUpdate = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 25);
        LOG.info("Polling for update event for config: {}", updatedConfig.clusterName());
        while (System.currentTimeMillis() < endTimeUpdate) {
            PipelineClusterConfigChangeEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polledEvent != null) {
                LOG.info("Polled event during update: cluster='{}', isDeletion={}", polledEvent.clusterName(), polledEvent.isDeletion());
                if (TEST_EXECUTION_CLUSTER.equals(polledEvent.clusterName()) && !polledEvent.isDeletion() && updatedConfig.equals(polledEvent.newConfig())) {
                    updateEvent = polledEvent;
                    LOG.info("Matched expected update event.");
                    break;
                }
            }
        }
        assertNotNull(updateEvent, "Should have received an update event from watch for " + updatedConfig.clusterName());
        assertFalse(updateEvent.isDeletion());
        assertEquals(updatedConfig, updateEvent.newConfig(), "New config in update event should match updated config");
        assertEquals(updatedConfig, testCachedConfigHolder.getCurrentConfig().orElse(null), "Cache should hold updated config");
        LOG.info("FullInteg-HappyPath: Watch update verified.");
        testApplicationEventListener.clear();

        // --- 3. Deletion ---
        LOG.info("FullInteg-HappyPath: Deleting config from Consul...");
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_EXECUTION_CLUSTER).block();

        PipelineClusterConfigChangeEvent deletionEvent = null;
        long endTimeDelete = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 20);
        LOG.info("Polling for deletion event for cluster: {}", TEST_EXECUTION_CLUSTER);
        while (System.currentTimeMillis() < endTimeDelete) {
            PipelineClusterConfigChangeEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
            if (polledEvent != null) {
                LOG.info("Polled event during deletion: clusterName='{}', isDeletion={}", polledEvent.clusterName(), polledEvent.isDeletion());
                if (TEST_EXECUTION_CLUSTER.equals(polledEvent.clusterName()) && polledEvent.isDeletion()) {
                    deletionEvent = polledEvent;
                    LOG.info("Matched expected deletion event.");
                    break;
                }
            }
        }
        assertNotNull(deletionEvent, "Should have received a deletion event for " + TEST_EXECUTION_CLUSTER);
        assertTrue(deletionEvent.isDeletion());
        assertNull(deletionEvent.newConfig(), "newConfig should be null for a deletion event");
        assertFalse(testCachedConfigHolder.getCurrentConfig().isPresent(), "Cache should be empty after deletion");
        LOG.info("FullInteg-HappyPath: Deletion verified.");

        consulBusinessOperationsService.deleteSchemaVersion(schemaRef1.subject(), schemaRef1.version()).block();
    }


    @Test
    @DisplayName("Full Integration: Initial Load - Fails CustomConfigSchemaValidator (Missing Schema)")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void fullIntegration_initialLoad_failsCustomConfigSchemaValidator_missingSchema() throws Exception {
        SchemaReference missingSchemaRef = new SchemaReference("customSchemaTrulyMissing", 1);
        // This config will reference 'missingSchemaRef' via its module.
        // The schema for 'missingSchemaRef' will NOT be seeded.
        PipelineClusterConfig configViolatingRule = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, missingSchemaRef, "topicViolatesRule");

        String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

        // Ensure schema is NOT there
        consulBusinessOperationsService.deleteSchemaVersion(missingSchemaRef.subject(), missingSchemaRef.version()).block();
        seedConsulKv(fullClusterKey, configViolatingRule);

        LOG.info("FullInteg-RuleFail-MissingSchema: Initializing DynamicConfigurationManager...");
        dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
        LOG.info("FullInteg-RuleFail-MissingSchema: Initialization complete (expecting validation failure due to missing schema).");

        // --- Verify Initial Load Failure ---
        PipelineClusterConfigChangeEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNull(initialEvent, "Should NOT have received a successful config update event due to missing schema");

        Optional<PipelineClusterConfig> cachedConfigAfterInit = testCachedConfigHolder.getCurrentConfig();
        assertFalse(cachedConfigAfterInit.isPresent(), "Config should NOT be in cache after failure due to missing schema");
        LOG.info("FullInteg-RuleFail-MissingSchema: Verified no config cached and no successful event published.");
    }


    // Dedicated Event Listener for this Test Class
    @Singleton
    static class TestApplicationEventListener {
        private static final Logger EVENT_LISTENER_LOG = LoggerFactory.getLogger(TestApplicationEventListener.class);
        // Listen for the NEW event type
        private final BlockingQueue<PipelineClusterConfigChangeEvent> receivedEvents = new ArrayBlockingQueue<>(20);

        @io.micronaut.runtime.event.annotation.EventListener
        void onClusterConfigUpdate(PipelineClusterConfigChangeEvent event) { // CORRECTED Event Type
            EVENT_LISTENER_LOG.info("TestApplicationEventListener (Full Integ) received event for cluster '{}'. Is Deletion: {}",
                    event.clusterName(), event.isDeletion());
            // Filter based on the event's clusterName using THIS class's constant
            if (DynamicConfigurationManagerFullIntegrationTest.TEST_EXECUTION_CLUSTER.equals(event.clusterName())) {
                if (!receivedEvents.offer(event)) {
                    EVENT_LISTENER_LOG.error("TestApplicationEventListener (Full Integ): FAILED TO OFFER EVENT TO QUEUE! Queue might be full. Event: {}", event);
                }
            } else {
                EVENT_LISTENER_LOG.warn("TestApplicationEventListener (Full Integ) ignored event for different cluster: {}. Expected: {}",
                        event.clusterName(), DynamicConfigurationManagerFullIntegrationTest.TEST_EXECUTION_CLUSTER);
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