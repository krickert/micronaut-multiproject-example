package com.krickert.search.pipeline.engine.bootstrap;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test-bootstrapper") // Use a specific environment for isolation
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
// Set a default cluster name for the test class context
@Property(name = "app.config.cluster-name", value = YappyEngineBootstrapperIntegrationTest.TEST_CLUSTER_NAME)
class YappyEngineBootstrapperIntegrationTest {

    static final String TEST_CLUSTER_NAME = "bootstrapperTestCluster";
    private static final Logger LOG_TEST = LoggerFactory.getLogger(YappyEngineBootstrapperIntegrationTest.class);

    @Inject
    ApplicationContext applicationContext; // Ensures the context is started and events are fired

    @Inject
    ConsulBusinessOperationsService consulOps;

    // The YappyEngineBootstrapper bean will be created by Micronaut and will
    // listen to the ApplicationStartupEvent automatically.

    @BeforeEach
    @AfterEach
    void cleanupConsul() {
        LOG_TEST.info("Cleaning up Consul for cluster: {}", TEST_CLUSTER_NAME);
        // Delete the test cluster config from Consul to ensure a clean state
        try {
            Boolean deleted = consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME)
                                     .blockOptional(Duration.ofSeconds(5)) // Give it a moment to complete
                                     .orElse(false);
            LOG_TEST.info("Cleanup: Deletion of cluster config for '{}' was successful: {}", TEST_CLUSTER_NAME, deleted);
        } catch (Exception e) {
            LOG_TEST.error("Error during Consul cleanup for cluster {}: {}", TEST_CLUSTER_NAME, e.getMessage());
        }
        // A small delay can sometimes help ensure Consul has processed the delete
        // before the next test starts, especially in fast-running test suites.
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Bootstrapper should NOT create default config if one already exists in Consul")
    void onApplicationEvent_whenConfigExists_doesNotOverwrite() {
        // Arrange: Seed an existing config
        PipelineClusterConfig existingConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .defaultPipelineName("my-existing-pipe") // Distinguishable from default
                .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap()))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .allowedKafkaTopics(Collections.singleton("existing-topic"))
                .allowedGrpcServices(Collections.singleton("existing-service"))
                .build();

        LOG_TEST.info("Seeding pre-existing config for cluster: {}", TEST_CLUSTER_NAME);
        Boolean stored = consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, existingConfig)
                                .blockOptional(Duration.ofSeconds(5))
                                .orElse(false);
        assertTrue(stored, "Pre-existing config should be stored successfully");

        // Act:
        // The MicronautTest annotation handles starting the application context,
        // which fires ApplicationStartupEvent, and YappyEngineBootstrapper will react.
        // We need to wait for the bootstrapper's potentially async operations.

        // Assert: Check Consul; the config should be the one we seeded.
        await().atMost(10, TimeUnit.SECONDS) // Increased timeout for CI/slower environments
               .pollInterval(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> {
                   Optional<PipelineClusterConfig> configInConsulOpt = consulOps.getPipelineClusterConfig(TEST_CLUSTER_NAME)
                                                                              .block(Duration.ofSeconds(5));
                   assertTrue(configInConsulOpt.isPresent(), "Config should still exist in Consul");
                   PipelineClusterConfig configInConsul = configInConsulOpt.get();
                   assertEquals(existingConfig.defaultPipelineName(), configInConsul.defaultPipelineName(),
                           "Config in Consul should be the original seeded one.");
                   assertEquals(existingConfig.allowedKafkaTopics(), configInConsul.allowedKafkaTopics(),
                           "Allowed Kafka topics should match the original seeded config.");
                   LOG_TEST.info("Verified existing config for cluster '{}' was not overwritten.", TEST_CLUSTER_NAME);
               });
    }

    @Test
    @DisplayName("Bootstrapper should create default config if one does NOT exist in Consul")
    void onApplicationEvent_whenConfigDoesNotExist_createsDefault() {
        // Arrange:
        // The @BeforeEach/@AfterEach cleanupConsul() ensures no config exists for TEST_CLUSTER_NAME.
        LOG_TEST.info("Ensured no pre-existing config for cluster: {}", TEST_CLUSTER_NAME);

        // Act:
        // Application startup triggers the bootstrapper.
        // Wait for bootstrapper's logic to complete.

        // Assert: Check Consul; a default config should now exist.
        await().atMost(10, TimeUnit.SECONDS)
               .pollInterval(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> {
                   Optional<PipelineClusterConfig> configInConsulOpt = consulOps.getPipelineClusterConfig(TEST_CLUSTER_NAME)
                                                                              .block(Duration.ofSeconds(5));
                   assertTrue(configInConsulOpt.isPresent(), "Default config should have been created in Consul");

                   PipelineClusterConfig defaultConfigInConsul = configInConsulOpt.get();
                   assertEquals(TEST_CLUSTER_NAME, defaultConfigInConsul.clusterName());
                   assertNull(defaultConfigInConsul.defaultPipelineName(), "Default config should have null defaultPipelineName");
                   assertNotNull(defaultConfigInConsul.pipelineGraphConfig(), "Default config should have a pipelineGraphConfig");
                   assertTrue(defaultConfigInConsul.pipelineGraphConfig().pipelines().isEmpty(), "Default config pipeline graph should be empty");
                   assertNotNull(defaultConfigInConsul.pipelineModuleMap(), "Default config should have a pipelineModuleMap");
                   assertTrue(defaultConfigInConsul.pipelineModuleMap().availableModules().isEmpty(), "Default config module map should be empty");
                   assertTrue(defaultConfigInConsul.allowedKafkaTopics().isEmpty(), "Default config should have empty allowedKafkaTopics");
                   assertTrue(defaultConfigInConsul.allowedGrpcServices().isEmpty(), "Default config should have empty allowedGrpcServices");
                   LOG_TEST.info("Verified default config was created for cluster '{}'.", TEST_CLUSTER_NAME);
               });
    }

    // Note: Testing the "Consul NOT Configured" scenario where consul.client.host/port
    // are missing is more complex in a standard @MicronautTest.
    // It would typically involve:
    // 1. Running the test with a different application context where those properties are absent.
    //    This can be done with profiles or by launching the context manually with specific properties.
    // 2. A reliable way to assert log messages (e.g., using a custom Logback appender
    //    or Micronaut's test utilities for log capture if available and suitable).
    // For now, we're focusing on the scenarios where Consul is expected to be available.
}
