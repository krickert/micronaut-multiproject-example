package com.krickert.yappy.integration.container;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the Tika Parser application, managed by Micronaut Test Resources.
 * This test verifies that the application starts correctly with all its dependencies
 * (Consul, Kafka, etc.) and can process requests.
 */
@MicronautTest(environments = "test") // startApplication defaults to true, which is what we want
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TikaParserContainerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(TikaParserContainerIntegrationTest.class);
    private static final String TEST_CLUSTER_NAME = "tika-parser-test-cluster";

    @Inject
    ApplicationContext applicationContext;

    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;

    // Inject an HTTP client that automatically points to the container's HTTP port
    // The property `yappy.engine.http.url` is supplied by your EngineTikaParserTestResourceProvider
    @Inject
    @Client("${yappy.engine.http.url}")
    HttpClient httpClient;

    @BeforeEach
    void setUp() {
        // Clean up any existing test data in Consul before each test
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        // Clean up test data after each test
        cleanupTestData();
    }

    private void cleanupTestData() {
        try {
            consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
            LOG.debug("Cleaned up test cluster configuration: {}", TEST_CLUSTER_NAME);
        } catch (Exception e) {
            LOG.debug("No existing configuration to clean up for cluster '{}': {}", TEST_CLUSTER_NAME, e.getMessage());
        }
    }

    @Test
    @DisplayName("Verify Dependent Service Properties are Injected")
    void test01_verifyDependentServicesAndProperties() {
        LOG.info("Verifying that all dependent service properties were injected by Test Resources...");

        // Assert that the properties are present. Their absence would cause the test to fail on startup.
        assertNotNull(applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null), "kafka.bootstrap.servers should be injected");
        assertNotNull(applicationContext.getProperty("consul.client.host", String.class).orElse(null), "consul.client.host should be injected");
        assertNotNull(applicationContext.getProperty("apicurio.registry.url", String.class).orElse(null), "apicurio.registry.url should be injected");
        assertNotNull(applicationContext.getProperty("yappy.engine.http.url", String.class).orElse(null), "The container's own URL should be injected");

        LOG.info("Kafka Bootstrap Servers: {}", applicationContext.getProperty("kafka.bootstrap.servers", String.class).get());
        LOG.info("Consul Host: {}", applicationContext.getProperty("consul.client.host", String.class).get());
        LOG.info("Engine HTTP URL: {}", applicationContext.getProperty("yappy.engine.http.url", String.class).get());
        LOG.info("Test PASSED: All required properties are present.");
    }

    @Test
    @DisplayName("Verify Consul Seeding and Health Endpoint")
    void test02_verifySeedingAndHealth() {
        LOG.info("Verifying seeding requirements and container health...");

        // 1. Seed the configuration into Consul
        PipelineClusterConfig testConfig = createTikaParserPipelineConfig();
        Boolean storeResult = consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, testConfig).block();
        assertTrue(storeResult, "Failed to store cluster configuration in Consul");

        // 2. Verify the container's health endpoint is responsive
        String healthResponse = httpClient.toBlocking().retrieve(HttpRequest.GET("/health"));
        assertNotNull(healthResponse, "Health endpoint should return a response");
        assertTrue(healthResponse.contains("\"status\":\"UP\""), "Container health status should be UP");

        LOG.info("Test PASSED: Seeding successful and container is healthy.");
    }

    // Helper method remains the same
    private PipelineClusterConfig createTikaParserPipelineConfig() {
        PipelineModuleConfiguration tikaModule = new PipelineModuleConfiguration("TikaParser", "tika-parser-service", null, Map.of("maxFileSize", "100MB", "parseTimeout", "30s"));
        PipelineStepConfig tikaStep = PipelineConfigTestUtils.createStep("tika-parse", StepType.PIPELINE, "tika-parser-service", null, List.of(PipelineConfigTestUtils.createKafkaInput("raw-documents")), Map.of("default", PipelineConfigTestUtils.createKafkaOutputTarget("parsed-documents", "end")));
        PipelineConfig tikaParserPipeline = PipelineConfigTestUtils.createPipeline("tika-parser-pipeline", Map.of("tika-parse", tikaStep));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("tika-parser-pipeline", tikaParserPipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("tika-parser-service", tikaModule));
        return PipelineClusterConfig.builder().clusterName(TEST_CLUSTER_NAME).pipelineGraphConfig(graphConfig).pipelineModuleMap(moduleMap).defaultPipelineName("tika-parser-pipeline").allowedKafkaTopics(Set.of("raw-documents", "parsed-documents")).allowedGrpcServices(Set.of("tika-parser-service")).build();
    }
}