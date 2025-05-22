package com.krickert.search.config.consul.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.*;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false, environments = {"test-consul-ops"})
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
// Ensure a default cluster name is set if any component relies on it, though not directly used by CBOS for key construction
@Property(name = "app.config.cluster-name", value = "defaultTestClusterForOps")
class ConsulBusinessOperationsServiceMicronautTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulBusinessOperationsServiceMicronautTest.class);
    private static final String TEST_CLUSTER_NAME = "opsTestCluster";
    private static final String WHITELIST_SERVICES_KEY_SUFFIX = "services";
    private static final String WHITELIST_TOPICS_KEY_SUFFIX = "topics";

    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;

    @Inject
    ObjectMapper objectMapper;

    // These are injected to help construct full keys for cleanup, matching how CBOS does it.
    @Inject
    @Value("${app.config.consul.key-prefixes.pipeline-clusters}")
    String clusterConfigKeyPrefix;

    @Inject
    @Value("${app.config.consul.key-prefixes.whitelists:config/pipeline/whitelists}")
    String whitelistsKeyPrefix;

    @Inject
    ConsulKvService consulKvService; // Used in cleanup for whitelists


    @BeforeEach
    void setUp() {
        // Clean up potential keys before each test
        cleanupTestKeys();
        LOG.info("Test setup complete for ConsulBusinessOperationsServiceMicronautTest.");
    }

    @AfterEach
    void tearDown() {
        // Clean up potential keys after each test
        cleanupTestKeys();
        LOG.info("Test teardown complete for ConsulBusinessOperationsServiceMicronautTest.");
    }

    private void cleanupTestKeys() {
        LOG.debug("Cleaning up test keys...");
        // Delete cluster config
        String fullClusterKey = getFullClusterConfigKey(TEST_CLUSTER_NAME);
        try {
            Boolean deletedCluster = consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
            LOG.debug("Deleted cluster config for {}: {}", TEST_CLUSTER_NAME, deletedCluster);
        } catch (Exception e) {
            LOG.warn("Error deleting cluster config for {} during cleanup: {}", TEST_CLUSTER_NAME, e.getMessage());
        }

        // Delete whitelists
        String serviceWhitelistKey = getFullWhitelistKey(WHITELIST_SERVICES_KEY_SUFFIX);
        String topicWhitelistKey = getFullWhitelistKey(WHITELIST_TOPICS_KEY_SUFFIX);
        try {
            // Using consulKvService directly for cleanup as CBOS's deleteKey is generic
            // and we want to ensure these specific keys are targeted.
            // This is acceptable as CBOS uses consulKvService internally.
            Boolean deletedServiceWhitelist = consulKvService.deleteKey(serviceWhitelistKey).block();
            LOG.debug("Deleted service whitelist key {}: {}", serviceWhitelistKey, deletedServiceWhitelist);
        } catch (Exception e) {
            LOG.warn("Error deleting service whitelist key {} during cleanup: {}", serviceWhitelistKey, e.getMessage());
        }
        try {
            Boolean deletedTopicWhitelist = consulKvService.deleteKey(topicWhitelistKey).block();
            LOG.debug("Deleted topic whitelist key {}: {}", topicWhitelistKey, deletedTopicWhitelist);
        } catch (Exception e) {
            LOG.warn("Error deleting topic whitelist key {} during cleanup: {}", topicWhitelistKey, e.getMessage());
        }
    }

    private String getFullClusterConfigKey(String clusterName) {
        // Mimic logic from ConsulBusinessOperationsService or ConsulKvService for full path
        String prefix = clusterConfigKeyPrefix.endsWith("/") ? clusterConfigKeyPrefix : clusterConfigKeyPrefix + "/";
        return prefix + clusterName;
    }

    private String getFullWhitelistKey(String suffix) {
        String prefix = whitelistsKeyPrefix.endsWith("/") ? whitelistsKeyPrefix : whitelistsKeyPrefix + "/";
        return prefix + suffix;
    }


    private PipelineClusterConfig createSamplePipelineClusterConfig(String clusterName) {
        PipelineModuleConfiguration module1 = new PipelineModuleConfiguration("Mod1", "mod1_id", new SchemaReference("schema1", 1));
        PipelineModuleConfiguration module2 = new PipelineModuleConfiguration("Mod2", "mod2_id", null);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("mod1_id", module1, "mod2_id", module2));

        PipelineStepConfig step1 = PipelineStepConfig.builder()
                .stepName("stepA")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("mod1_id", null))
                .build();
        PipelineConfig pipeline1 = new PipelineConfig("pipeA", Map.of("stepA", step1));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("pipeA", pipeline1));

        return PipelineClusterConfig.builder()
                .clusterName(clusterName)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName("pipeA")
                .allowedKafkaTopics(Set.of("topic1", "topic2"))
                .allowedGrpcServices(Set.of("mod1_id", "external_grpc_service"))
                .build();
    }

    @Test
    @DisplayName("getPipelineClusterConfig - should fetch and deserialize existing config")
    void getPipelineClusterConfig_existing() {
        PipelineClusterConfig expectedConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        Boolean stored = consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, expectedConfig).block();
        assertTrue(stored, "Failed to store sample cluster config");

        StepVerifier.create(consulBusinessOperationsService.getPipelineClusterConfig(TEST_CLUSTER_NAME))
                .assertNext(configOpt -> {
                    assertTrue(configOpt.isPresent(), "Config should be present");
                    assertEquals(expectedConfig, configOpt.get(), "Fetched config does not match expected");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getPipelineClusterConfig - should return empty Optional for non-existent config")
    void getPipelineClusterConfig_nonExistent() {
        StepVerifier.create(consulBusinessOperationsService.getPipelineClusterConfig("nonExistentCluster"))
                .assertNext(configOpt -> assertFalse(configOpt.isPresent(), "Config should not be present"))
                .verifyComplete();
    }

    @Test
    @DisplayName("getPipelineClusterConfig - should return empty Optional for malformed JSON")
    void getPipelineClusterConfig_malformedJson() {
        String malformedJson = "{\"clusterName\":\"" + TEST_CLUSTER_NAME + "\", \"pipelineGraphConfig\": {malformed}}";
        String fullKey = getFullClusterConfigKey(TEST_CLUSTER_NAME);
        // Use the underlying ConsulKvService for direct string put if CBOS always serializes
        Boolean stored = consulKvService.putValue(fullKey, malformedJson).block();
        assertTrue(stored, "Failed to store malformed JSON using ConsulKvService");

        StepVerifier.create(consulBusinessOperationsService.getPipelineClusterConfig(TEST_CLUSTER_NAME))
                .assertNext(configOpt -> {
                    assertFalse(configOpt.isPresent(), "Config should not be present due to malformed JSON");
                })
                .verifyComplete();
    }


    @Test
    @DisplayName("getPipelineGraphConfig - should extract graph config")
    void getPipelineGraphConfig_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();

        StepVerifier.create(consulBusinessOperationsService.getPipelineGraphConfig(TEST_CLUSTER_NAME))
                .assertNext(graphOpt -> {
                    assertTrue(graphOpt.isPresent(), "Graph config should be present");
                    assertEquals(fullConfig.pipelineGraphConfig(), graphOpt.get());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getPipelineModuleMap - should extract module map")
    void getPipelineModuleMap_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();

        StepVerifier.create(consulBusinessOperationsService.getPipelineModuleMap(TEST_CLUSTER_NAME))
                .assertNext(mapOpt -> {
                    assertTrue(mapOpt.isPresent(), "Module map should be present");
                    assertEquals(fullConfig.pipelineModuleMap(), mapOpt.get());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getAllowedKafkaTopics - should extract allowed Kafka topics")
    void getAllowedKafkaTopics_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();

        StepVerifier.create(consulBusinessOperationsService.getAllowedKafkaTopics(TEST_CLUSTER_NAME))
                .assertNext(topicsSet -> {
                    assertNotNull(topicsSet);
                    assertEquals(fullConfig.allowedKafkaTopics(), topicsSet);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getAllowedGrpcServices - should extract allowed gRPC services")
    void getAllowedGrpcServices_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();

        StepVerifier.create(consulBusinessOperationsService.getAllowedGrpcServices(TEST_CLUSTER_NAME))
                .assertNext(servicesSet -> {
                    assertNotNull(servicesSet);
                    assertEquals(fullConfig.allowedGrpcServices(), servicesSet);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getSpecificPipelineConfig - should fetch existing pipeline")
    void getSpecificPipelineConfig_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();
        String targetPipelineName = "pipeA"; // from createSamplePipelineClusterConfig

        StepVerifier.create(consulBusinessOperationsService.getSpecificPipelineConfig(TEST_CLUSTER_NAME, targetPipelineName))
                .assertNext(pipelineOpt -> {
                    assertTrue(pipelineOpt.isPresent(), "Pipeline config should be present");
                    assertEquals(fullConfig.pipelineGraphConfig().pipelines().get(targetPipelineName), pipelineOpt.get());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("listPipelineNames - should list names of existing pipelines")
    void listPipelineNames_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();

        StepVerifier.create(consulBusinessOperationsService.listPipelineNames(TEST_CLUSTER_NAME))
                .assertNext(namesList -> {
                    assertNotNull(namesList);
                    assertEquals(1, namesList.size());
                    assertTrue(namesList.contains("pipeA"));
                })
                .verifyComplete();
    }

    // --- NEW TESTS ---

    @Test
    @DisplayName("getSpecificPipelineModuleConfiguration - should fetch existing module")
    void getSpecificPipelineModuleConfiguration_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();
        String targetModuleId = "mod1_id"; // from createSamplePipelineClusterConfig

        StepVerifier.create(consulBusinessOperationsService.getSpecificPipelineModuleConfiguration(TEST_CLUSTER_NAME, targetModuleId))
                .assertNext(moduleOpt -> {
                    assertTrue(moduleOpt.isPresent(), "Module configuration should be present");
                    assertEquals(fullConfig.pipelineModuleMap().availableModules().get(targetModuleId), moduleOpt.get());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getSpecificPipelineModuleConfiguration - should return empty for non-existent module ID")
    void getSpecificPipelineModuleConfiguration_nonExistentModule() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();

        StepVerifier.create(consulBusinessOperationsService.getSpecificPipelineModuleConfiguration(TEST_CLUSTER_NAME, "non_existent_mod_id"))
                .assertNext(moduleOpt -> assertFalse(moduleOpt.isPresent(), "Module configuration should not be present"))
                .verifyComplete();
    }

    @Test
    @DisplayName("getSpecificPipelineModuleConfiguration - should return empty for non-existent cluster")
    void getSpecificPipelineModuleConfiguration_nonExistentCluster() {
        StepVerifier.create(consulBusinessOperationsService.getSpecificPipelineModuleConfiguration("non_existent_cluster", "any_mod_id"))
                .assertNext(moduleOpt -> assertFalse(moduleOpt.isPresent(), "Module configuration should not be present for non-existent cluster"))
                .verifyComplete();
    }

    @Test
    @DisplayName("listAvailablePipelineModuleImplementations - should list existing modules")
    void listAvailablePipelineModuleImplementations_existing() {
        PipelineClusterConfig fullConfig = createSamplePipelineClusterConfig(TEST_CLUSTER_NAME);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, fullConfig).block();

        StepVerifier.create(consulBusinessOperationsService.listAvailablePipelineModuleImplementations(TEST_CLUSTER_NAME))
                .assertNext(modulesList -> {
                    assertNotNull(modulesList);
                    assertEquals(2, modulesList.size(), "Should be 2 modules from sample config");
                    assertTrue(modulesList.contains(fullConfig.pipelineModuleMap().availableModules().get("mod1_id")));
                    assertTrue(modulesList.contains(fullConfig.pipelineModuleMap().availableModules().get("mod2_id")));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("listAvailablePipelineModuleImplementations - should return empty list for empty module map")
    void listAvailablePipelineModuleImplementations_emptyMap() {
        PipelineClusterConfig emptyModulesConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap())) // Empty map
                .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap()))
                .defaultPipelineName("default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, emptyModulesConfig).block();

        StepVerifier.create(consulBusinessOperationsService.listAvailablePipelineModuleImplementations(TEST_CLUSTER_NAME))
                .assertNext(modulesList -> {
                    assertNotNull(modulesList);
                    assertTrue(modulesList.isEmpty(), "Modules list should be empty");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("listAvailablePipelineModuleImplementations - should return empty list for non-existent cluster")
    void listAvailablePipelineModuleImplementations_nonExistentCluster() {
        StepVerifier.create(consulBusinessOperationsService.listAvailablePipelineModuleImplementations("non_existent_cluster"))
                .assertNext(modulesList -> {
                    assertNotNull(modulesList);
                    assertTrue(modulesList.isEmpty(), "Modules list should be empty for non-existent cluster");
                })
                .verifyComplete();
    }


    @Test
    @DisplayName("getServiceWhitelist - should fetch and deserialize existing whitelist")
    void getServiceWhitelist_existing() throws JsonProcessingException {
        List<String> expectedWhitelist = List.of("serviceA", "serviceB");
        String serviceWhitelistKey = getFullWhitelistKey(WHITELIST_SERVICES_KEY_SUFFIX);
        consulBusinessOperationsService.putValue(serviceWhitelistKey, objectMapper.writeValueAsString(expectedWhitelist)).block();

        StepVerifier.create(consulBusinessOperationsService.getServiceWhitelist())
                .assertNext(whitelist -> {
                    assertNotNull(whitelist);
                    assertEquals(expectedWhitelist, whitelist);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getServiceWhitelist - should return empty list for non-existent whitelist")
    void getServiceWhitelist_nonExistent() {
        StepVerifier.create(consulBusinessOperationsService.getServiceWhitelist())
                .assertNext(whitelist -> {
                    assertNotNull(whitelist);
                    assertTrue(whitelist.isEmpty());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getServiceWhitelist - should return empty list for malformed JSON")
    void getServiceWhitelist_malformedJson() {
        String malformedJson = "[\"serviceA\", not-a-string]";
        String serviceWhitelistKey = getFullWhitelistKey(WHITELIST_SERVICES_KEY_SUFFIX);
        // Use underlying ConsulKvService for direct string put
        Boolean stored = consulKvService.putValue(serviceWhitelistKey, malformedJson).block();
        assertTrue(stored, "Failed to store malformed JSON for service whitelist");

        StepVerifier.create(consulBusinessOperationsService.getServiceWhitelist())
                .assertNext(whitelist -> {
                    assertNotNull(whitelist, "Whitelist should not be null even on error");
                    assertTrue(whitelist.isEmpty(), "Whitelist should be empty due to malformed JSON");
                })
                .verifyComplete();
    }


    @Test
    @DisplayName("getTopicWhitelist - should fetch and deserialize existing whitelist")
    void getTopicWhitelist_existing() throws JsonProcessingException {
        List<String> expectedWhitelist = List.of("topicX", "topicY");
        String topicWhitelistKey = getFullWhitelistKey(WHITELIST_TOPICS_KEY_SUFFIX);
        consulBusinessOperationsService.putValue(topicWhitelistKey, objectMapper.writeValueAsString(expectedWhitelist)).block();

        StepVerifier.create(consulBusinessOperationsService.getTopicWhitelist())
                .assertNext(whitelist -> {
                    assertNotNull(whitelist);
                    assertEquals(expectedWhitelist, whitelist);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getTopicWhitelist - should return empty list for malformed JSON")
    void getTopicWhitelist_malformedJson() {
        String malformedJson = "[\"topicX\", not-json]";
        String topicWhitelistKey = getFullWhitelistKey(WHITELIST_TOPICS_KEY_SUFFIX);
        // Use underlying ConsulKvService for direct string put
        Boolean stored = consulKvService.putValue(topicWhitelistKey, malformedJson).block();
        assertTrue(stored, "Failed to store malformed JSON for topic whitelist");

        StepVerifier.create(consulBusinessOperationsService.getTopicWhitelist())
                .assertNext(whitelist -> {
                    assertNotNull(whitelist, "Whitelist should not be null even on error");
                    assertTrue(whitelist.isEmpty(), "Whitelist should be empty due to malformed JSON");
                })
                .verifyComplete();
    }


    // Example for an existing method (service registry related)
    @Test
    @DisplayName("isConsulAvailable - should return true when Consul is up")
    void isConsulAvailable_whenUp() {
        // Testcontainers ensures Consul is up
        StepVerifier.create(consulBusinessOperationsService.isConsulAvailable())
                .expectNext(true)
                .verifyComplete();
    }
}