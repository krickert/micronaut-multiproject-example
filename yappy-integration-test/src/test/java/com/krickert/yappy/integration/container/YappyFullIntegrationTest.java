package com.krickert.yappy.integration.container;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Modern integration test using Micronaut Test Resources.
 * This test loads a specific configuration to activate all service containers
 * and verifies the entire application stack is working together.
 */
// This is the most important change:
// It tells Micronaut to load your specific container configuration file.
@MicronautTest(propertySources = "classpath:application-container-test.yml")
public class YappyFullIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(YappyFullIntegrationTest.class);
    private static final String TEST_CLUSTER_NAME = "tika-parser-test-cluster";

    @Inject
    ApplicationContext applicationContext;

    // This client is now guaranteed to be configured by your provider
    @Inject
    @Client("${yappy.engine.http.url}")
    HttpClient httpClient;

    // We can even inject beans that depend on other containers, like Consul
    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;

    @Test
    @DisplayName("1. Verifies All Test Resource Properties Are Injected")
    void testAllServicePropertiesAreInjected() {
        LOG.info("Verifying that all service properties were injected by Test Resources...");

        // Assert that properties from ALL our providers are present
        assertNotNull(applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null), "kafka.bootstrap.servers should be injected");
        assertNotNull(applicationContext.getProperty("consul.client.host", String.class).orElse(null), "consul.client.host should be injected");
        assertNotNull(applicationContext.getProperty("apicurio.registry.url", String.class).orElse(null), "apicurio.registry.url should be injected");
        
        // This is the key property from your custom provider
        String engineUrl = applicationContext.getProperty("yappy.engine.http.url", String.class).orElse(null);
        assertNotNull(engineUrl, "The container's own URL (yappy.engine.http.url) should be injected");

        LOG.info("Engine HTTP URL: {}", engineUrl);
        LOG.info("Test PASSED: All required properties are present.");
    }

    @Test
    @DisplayName("2. Verifies the Engine-Tika-Parser Container Health Endpoint")
    void testEngineHealthEndpointIsAccessible() {
        LOG.info("Checking Engine health endpoint via test resource provider...");
        
        // This request now goes to the container started automatically by your provider
        String healthResponse = httpClient.toBlocking().retrieve(HttpRequest.GET("/health"));
        assertNotNull(healthResponse, "Health endpoint should return a response");
        assertTrue(healthResponse.contains("\"status\":\"UP\""), "Container health status should be UP");
        
        LOG.info("Test PASSED: Engine container is healthy and responsive.");
    }

    @Test
    @DisplayName("3. Verifies Interaction with a Dependent Container (Consul)")
    void testConsulInteraction() {
        LOG.info("Verifying we can seed data into the Consul container...");
        
        // This proves our application can connect to the Consul container and perform operations
        PipelineClusterConfig testConfig = createTikaParserPipelineConfig();
        Boolean storeResult = consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, testConfig).block();
        assertTrue(storeResult, "Failed to store cluster configuration in Consul");

        PipelineClusterConfig retrievedConfig = consulBusinessOperationsService
                .getPipelineClusterConfig(TEST_CLUSTER_NAME)
                .block()
                .orElse(null);
        
        assertNotNull(retrievedConfig, "Should be able to retrieve the seeded config from Consul");
        assertEquals(TEST_CLUSTER_NAME, retrievedConfig.clusterName());
        
        LOG.info("Test PASSED: Successfully seeded and retrieved data from Consul container.");
    }

    // Helper method for creating test data
    private PipelineClusterConfig createTikaParserPipelineConfig() {
        PipelineModuleConfiguration tikaModule = new PipelineModuleConfiguration("TikaParser", "tika-parser-service", null, Map.of("maxFileSize", "100MB", "parseTimeout", "30s"));
        PipelineStepConfig tikaStep = PipelineConfigTestUtils.createStep("tika-parse", StepType.PIPELINE, "tika-parser-service", null, List.of(PipelineConfigTestUtils.createKafkaInput("raw-documents")), Map.of("default", PipelineConfigTestUtils.createKafkaOutputTarget("parsed-documents", "end")));
        PipelineConfig tikaParserPipeline = PipelineConfigTestUtils.createPipeline("tika-parser-pipeline", Map.of("tika-parse", tikaStep));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("tika-parser-pipeline", tikaParserPipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("tika-parser-service", tikaModule));
        return PipelineClusterConfig.builder().clusterName(TEST_CLUSTER_NAME).pipelineGraphConfig(graphConfig).pipelineModuleMap(moduleMap).defaultPipelineName("tika-parser-pipeline").allowedKafkaTopics(Set.of("raw-documents", "parsed-documents")).allowedGrpcServices(Set.of("tika-parser-service")).build();
    }
}