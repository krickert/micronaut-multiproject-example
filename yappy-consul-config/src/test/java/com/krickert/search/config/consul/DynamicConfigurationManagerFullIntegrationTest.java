    package com.krickert.search.config.consul;

    import com.fasterxml.jackson.core.JsonProcessingException;
    import com.fasterxml.jackson.databind.ObjectMapper;
    import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
    import com.krickert.search.config.consul.factory.DynamicConfigurationManagerFactory;
    import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
    import com.krickert.search.config.pipeline.model.*;
    import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
    import com.krickert.search.config.pipeline.model.test.SamplePipelineConfigObjects;
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
    // NO Mockito imports needed for the core SUT dependencies in this class

    @MicronautTest(startApplication = false, environments = {"test-dynamic-manager-full"}) // Use a distinct environment if needed
    @Property(name = "micronaut.config-client.enabled", value = "false")
    @Property(name = "consul.client.enabled", value = "true")
    @Property(name = "testcontainers.consul.enabled", value = "true")
    @Property(name = "app.config.cluster-name", value = DynamicConfigurationManagerFullIntegrationTest.DEFAULT_PROPERTY_CLUSTER)
    class DynamicConfigurationManagerFullIntegrationTest {

        private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerFullIntegrationTest.class);
        static final String DEFAULT_PROPERTY_CLUSTER = "propertyClusterFullDefault";
        static final String TEST_EXECUTION_CLUSTER = "dynamicManagerFullTestCluster";
    //TODO: this should be in the service layer.  DO NOT use this directly
    //        @Inject
    //        Consul directConsulClientForTestSetup;
        @Inject
        ObjectMapper objectMapper;

        @Inject
        ConsulBusinessOperationsService consulBusinessOperationsService;
        @Inject
        ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher;
        @Inject
        KiwiprojectConsulConfigFetcher realConsulConfigFetcher;
        @Inject
        CachedConfigHolder testCachedConfigHolder;

        @Inject
        DefaultConfigurationValidator realConfigurationValidator; // Inject your DefaultConfigurationValidator

        @Inject
        TestApplicationEventListener testApplicationEventListener; // Reusing from the other test for convenience

        @Inject
        DynamicConfigurationManagerFactory dynamicConfigurationManagerFactory;

        //TODO: THIS NEEDS TO BE IN THE SERVICE LAYER
        //private KeyValueClient testKvClient;
        private String clusterConfigKeyPrefix;
        private String schemaVersionsKeyPrefix;
        private int appWatchSeconds;

        private DynamicConfigurationManager dynamicConfigurationManager;

        // Re-use TestApplicationEventListener and SimpleMapCachedConfigHolder
        // (They are already suitable as real, simple implementations for testing)
        @Singleton
        static class TestApplicationEventListener { // Copied from DynamicConfigurationManagerImplMicronautTest
            private static final Logger EVENT_LISTENER_LOG = LoggerFactory.getLogger(TestApplicationEventListener.class);
            private final BlockingQueue<ClusterConfigUpdateEvent> receivedEvents = new ArrayBlockingQueue<>(10);

            @io.micronaut.runtime.event.annotation.EventListener
            void onClusterConfigUpdate(ClusterConfigUpdateEvent event) {
                EVENT_LISTENER_LOG.info("TestApplicationEventListener (Full Integ) received event for cluster '{}'. Old present: {}, New cluster: {}",
                        event.newConfig().clusterName(), event.oldConfig().isPresent(), event.newConfig().clusterName());
                if (TEST_EXECUTION_CLUSTER.equals(event.newConfig().clusterName()) ||
                    (event.oldConfig().isPresent() && TEST_EXECUTION_CLUSTER.equals(event.oldConfig().get().clusterName()))) {
                    receivedEvents.offer(event);
                } else {
                     EVENT_LISTENER_LOG.warn("TestApplicationEventListener (Full Integ) ignored event for different cluster: {}. Expected: {}",
                            event.newConfig().clusterName(), TEST_EXECUTION_CLUSTER);
                }
            }
            public ClusterConfigUpdateEvent pollEvent(long timeout, TimeUnit unit) throws InterruptedException { return receivedEvents.poll(timeout, unit); }
            public void clear() { receivedEvents.clear(); }
        }



        @BeforeEach
        void setUp() {
            clusterConfigKeyPrefix = realConsulConfigFetcher.clusterConfigKeyPrefix;
            schemaVersionsKeyPrefix = realConsulConfigFetcher.schemaVersionsKeyPrefix;
            appWatchSeconds = realConsulConfigFetcher.appWatchSeconds;

            deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
            testApplicationEventListener.clear();

            // Construct SUT using the factory
            dynamicConfigurationManager = dynamicConfigurationManagerFactory.createDynamicConfigurationManager(TEST_EXECUTION_CLUSTER);
            LOG.info("DynamicConfigurationManagerImpl (Full Integ) constructed for cluster: {} with DefaultConfigurationValidator", TEST_EXECUTION_CLUSTER);
        }

        @AfterEach
        void tearDown() {
            if (dynamicConfigurationManager != null) {
                dynamicConfigurationManager.shutdown();
            }
            deleteConsulKeysForCluster(TEST_EXECUTION_CLUSTER);
            LOG.info("Test (Full Integ) finished, keys for cluster {} potentially cleaned.", TEST_EXECUTION_CLUSTER);
        }

        // --- Helper methods (copy from DynamicConfigurationManagerImplMicronautTest) ---
        private void deleteConsulKeysForCluster(String clusterName) {
            LOG.debug("Attempting to clean Consul key for cluster: {}", clusterName);
            consulBusinessOperationsService.deleteClusterConfiguration(clusterName).block();
        }
        private String getFullClusterKey(String clusterName) { return clusterConfigKeyPrefix + clusterName; }
        private String getFullSchemaKey(String subject, int version) { return String.format("%s%s/%d", schemaVersionsKeyPrefix, subject, version); }
        private PipelineClusterConfig createDummyClusterConfig(String name, String... topics) {
            return PipelineClusterConfig.builder()
                    .clusterName(name)
                    .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                    .defaultPipelineName(name + "-default")
                    .allowedKafkaTopics(topics != null ? Set.of(topics) : Collections.emptySet())
                    .allowedGrpcServices(Collections.emptySet())
                    .build();
        }
        private PipelineClusterConfig createClusterConfigWithSchema(String name, SchemaReference schemaRef, String... topics) {
            PipelineModuleConfiguration moduleWithSchema = new PipelineModuleConfiguration("ModuleWithSchema", "module_schema_impl_id", schemaRef);
            PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(moduleWithSchema.implementationId(), moduleWithSchema));
            return PipelineClusterConfig.builder()
                    .clusterName(name)
                    .pipelineModuleMap(moduleMap)
                    .defaultPipelineName(name + "-default")
                    .allowedKafkaTopics(topics != null ? Set.of(topics) : Collections.emptySet())
                    .allowedGrpcServices(Collections.emptySet())
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

            // Determine if this is a cluster config or schema version based on the key
            if (key.startsWith(clusterConfigKeyPrefix)) {
                // Extract cluster name from key
                String clusterName = key.substring(clusterConfigKeyPrefix.length());

                // Store cluster configuration
                Boolean result = consulBusinessOperationsService.storeClusterConfiguration(clusterName, object).block();
                assertTrue(result != null && result, "Failed to seed cluster configuration for key: " + key);
            } else if (key.startsWith(schemaVersionsKeyPrefix)) {
                // Extract subject and version from key
                String path = key.substring(schemaVersionsKeyPrefix.length());

                String[] parts = path.split("/");
                if (parts.length == 2) {
                    String subject = parts[0];
                    int version = Integer.parseInt(parts[1]);

                    // Store schema version
                    Boolean result = consulBusinessOperationsService.storeSchemaVersion(subject, version, object).block();
                    assertTrue(result != null && result, "Failed to seed schema version for key: " + key);
                } else {
                    // Fallback to generic putValue for other keys
                    Boolean result = consulBusinessOperationsService.putValue(key, object).block();
                    assertTrue(result != null && result, "Failed to seed Consul KV for key: " + key);
                }
            } else {
                // Fallback to generic putValue for other keys
                Boolean result = consulBusinessOperationsService.putValue(key, object).block();
                assertTrue(result != null && result, "Failed to seed Consul KV for key: " + key);
            }

            try { TimeUnit.MILLISECONDS.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // --- Test Scenarios ---

        @Test
        @DisplayName("Full Integration: Happy Path - Initial Load, Update, and Delete (All Rules Pass)")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void fullIntegration_happyPath_initialLoad_update_delete() throws Exception {
            // --- 1. Initial Load ---
            // Create a config that you expect to pass ALL your validation rules.
            // This might involve setting up specific topics, module configurations, schemas, etc.
            // For simplicity, let's assume a basic config with a schema passes your rules.
            SchemaReference schemaRef1 = new SchemaReference("fullIntegHappySchema1", 1);
            PipelineClusterConfig initialConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, schemaRef1, "topicFullHappyInit");
            SchemaVersionData schemaData1 = createDummySchemaData(schemaRef1.subject(), schemaRef1.version(), "{\"type\":\"string\"}");
            String fullSchemaKey1 = getFullSchemaKey(schemaRef1.subject(), schemaRef1.version());
            String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);

            // Delete schema version
            consulBusinessOperationsService.deleteSchemaVersion(schemaRef1.subject(), schemaRef1.version()).block();
            seedConsulKv(fullSchemaKey1, schemaData1);
            seedConsulKv(fullClusterKey, initialConfig);

            LOG.info("FullInteg-HappyPath: Initializing DynamicConfigurationManager...");
            dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
            LOG.info("FullInteg-HappyPath: Initialization complete.");

            ClusterConfigUpdateEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 15, TimeUnit.SECONDS);
            assertNotNull(initialEvent, "Should have received an initial load event");
            assertTrue(initialEvent.oldConfig().isEmpty(), "Old config should be empty for initial load");
            assertEquals(initialConfig, initialEvent.newConfig(), "New config in event should match initial seeded config");
            assertEquals(initialConfig, testCachedConfigHolder.getCurrentConfig().orElse(null), "Cache should hold initial config");
            assertEquals(schemaData1.schemaContent(), testCachedConfigHolder.getSchemaContent(schemaRef1).orElse(null), "Schema should be cached");
            LOG.info("FullInteg-HappyPath: Initial load verified.");

            // --- 2. Watch Update (also valid) ---
            PipelineClusterConfig updatedConfig = createClusterConfigWithSchema(TEST_EXECUTION_CLUSTER, schemaRef1, "topicFullHappyInit", "topicFullHappyUpdate");
            LOG.info("FullInteg-HappyPath: Seeding updated config to trigger watch...");
            seedConsulKv(fullClusterKey, updatedConfig);
            LOG.info("FullInteg-HappyPath: Updated config seeded.");

            ClusterConfigUpdateEvent updateEvent = null;
            long endTimeUpdate = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 20);
            while(System.currentTimeMillis() < endTimeUpdate) {
                ClusterConfigUpdateEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
                if (polledEvent != null && updatedConfig.equals(polledEvent.newConfig())) {
                    updateEvent = polledEvent;
                    break;
                }
            }
            assertNotNull(updateEvent, "Should have received an update event from watch");
            assertEquals(Optional.of(initialConfig), updateEvent.oldConfig(), "Old config in update event should be the initial one");
            assertEquals(updatedConfig, updateEvent.newConfig(), "New config in update event should match updated config");
            assertEquals(updatedConfig, testCachedConfigHolder.getCurrentConfig().orElse(null), "Cache should hold updated config");
            LOG.info("FullInteg-HappyPath: Watch update verified.");

            // --- 3. Deletion ---
            LOG.info("FullInteg-HappyPath: Deleting config from Consul...");
            consulBusinessOperationsService.deleteClusterConfiguration(TEST_EXECUTION_CLUSTER).block();
            TimeUnit.MILLISECONDS.sleep(appWatchSeconds * 1000L / 2 + 500);
            LOG.info("FullInteg-HappyPath: Config deleted from Consul.");

            ClusterConfigUpdateEvent deletionEvent = null;
            long endTimeDelete = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(appWatchSeconds + 15);
            while (System.currentTimeMillis() < endTimeDelete) {
                ClusterConfigUpdateEvent polledEvent = testApplicationEventListener.pollEvent(1, TimeUnit.SECONDS);
                if (polledEvent != null && polledEvent.oldConfig().isPresent() && updatedConfig.equals(polledEvent.oldConfig().get()) &&
                    (polledEvent.newConfig().allowedKafkaTopics() == null || polledEvent.newConfig().allowedKafkaTopics().isEmpty())) {
                    deletionEvent = polledEvent;
                    break;
                }
            }
            assertNotNull(deletionEvent, "Should have received a deletion event");
            assertEquals(Optional.of(updatedConfig), deletionEvent.oldConfig(), "Old config in deletion event should be the updated one");
            assertFalse(testCachedConfigHolder.getCurrentConfig().isPresent(), "Cache should be empty after deletion");
            LOG.info("FullInteg-HappyPath: Deletion verified.");

            // Clean up schema
            consulBusinessOperationsService.deleteSchemaVersion(schemaRef1.subject(), schemaRef1.version()).block();
        }


        @Test
        @DisplayName("Full Integration: Initial Load - Fails CustomConfigSchemaValidator (Example)")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void fullIntegration_initialLoad_failsCustomConfigSchemaValidator() throws Exception {
            // --- Setup Data that will FAIL the CustomConfigSchemaValidator ---
            SchemaReference missingSchemaRef = new SchemaReference("customSchemaMissing", 1);
            String moduleImplementationId = "module_schema_impl_id";

            // Create a module that references the missing schema
            PipelineModuleConfiguration moduleWithMissingSchema = new PipelineModuleConfiguration(
                    "ModuleWithMissingSchema",
                    moduleImplementationId,
                    missingSchemaRef
            );
            PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(moduleImplementationId, moduleWithMissingSchema));

            // Create a step that uses this module and has a customConfig
            PipelineStepConfig stepUsingMissingSchema = PipelineStepConfig.builder()
                    .stepName("step1_uses_missing_schema")
                    .stepType(StepType.PIPELINE)
                    .processorInfo(new PipelineStepConfig.ProcessorInfo(moduleImplementationId, null))
                    .customConfig(PipelineConfigTestUtils.createJsonConfigOptions("{\"someKey\":\"someValue\"}"))
                    .build();

            // Create a pipeline containing this step
            PipelineConfig pipelineConfig = new PipelineConfig(
                    "pipeline_with_bad_step", // name
                    Map.of(stepUsingMissingSchema.stepName(), stepUsingMissingSchema) // pipelineSteps
            );

            // Create a pipeline graph containing this pipeline
            PipelineGraphConfig graphConfig = new PipelineGraphConfig(
                    Map.of(pipelineConfig.name(), pipelineConfig) // pipelines (use pipelineConfig.name() as key)
            );

            // Create the final cluster config
            PipelineClusterConfig configViolatingRule = PipelineClusterConfig.builder()
                    .clusterName(TEST_EXECUTION_CLUSTER)
                    .pipelineGraphConfig(graphConfig)
                    .pipelineModuleMap(moduleMap)
                    .defaultPipelineName(TEST_EXECUTION_CLUSTER + "-default")
                    .allowedKafkaTopics(Set.of("topicViolatesRule"))
                    .allowedGrpcServices(Collections.emptySet())
                    .build();

            String fullClusterKey = getFullClusterKey(TEST_EXECUTION_CLUSTER);
            String fullMissingSchemaKey = getFullSchemaKey(missingSchemaRef.subject(), missingSchemaRef.version());

            // Ensure schema is NOT there
            consulBusinessOperationsService.deleteSchemaVersion(missingSchemaRef.subject(), missingSchemaRef.version()).block();
            seedConsulKv(fullClusterKey, configViolatingRule);

            LOG.info("FullInteg-RuleFail: Initializing DynamicConfigurationManager with config violating CustomConfigSchemaValidator...");
            dynamicConfigurationManager.initialize(TEST_EXECUTION_CLUSTER);
            LOG.info("FullInteg-RuleFail: Initialization complete (expecting validation failure).");

            // --- Verify Initial Load Failure ---
            ClusterConfigUpdateEvent initialEvent = testApplicationEventListener.pollEvent(appWatchSeconds + 5, TimeUnit.SECONDS);
            assertNull(initialEvent, "Should NOT have received a successful config update event due to validation failure");

            Optional<PipelineClusterConfig> cachedConfigAfterInit = testCachedConfigHolder.getCurrentConfig();
            assertFalse(cachedConfigAfterInit.isPresent(), "Config should NOT be in cache after validation failure");
            LOG.info("FullInteg-RuleFail: Verified no config cached and no successful event published.");
        }

    }
