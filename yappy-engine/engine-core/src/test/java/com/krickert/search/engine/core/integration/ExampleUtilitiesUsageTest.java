package com.krickert.search.engine.core.integration;

import com.krickert.search.engine.core.test.util.*;
import com.krickert.search.grpc.RegistrationStatus;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example test demonstrating how to use the test utilities.
 * This test shows common patterns for module registration, pipeline creation,
 * and data processing in integration tests.
 */
@MicronautTest(startApplication = false)
public class ExampleUtilitiesUsageTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ExampleUtilitiesUsageTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    private TestResourcesManager resourcesManager;
    private ModuleRegistrationTestHelper registrationHelper;
    private GrpcTestHelper grpcHelper;
    
    @BeforeEach
    void setup() {
        // Initialize test utilities
        resourcesManager = new TestResourcesManager(applicationContext);
        
        // Note: In a real test, we would wait for containers to be ready
        // For now, we'll skip this check as not all containers are configured
        
        // Log environment info for debugging
        resourcesManager.logEnvironmentInfo();
    }
    
    @AfterEach
    void cleanup() {
        if (registrationHelper != null) {
            registrationHelper.close();
        }
        if (grpcHelper != null) {
            grpcHelper.close();
        }
    }
    
    @Test
    @Disabled("Requires registration service to be running")
    void demonstrateModuleRegistration() {
        LOG.info("=== Module Registration Example ===");
        
        // Initialize registration helper (assuming registration service is available)
        String registrationHost = "localhost"; // Would get from test resources
        int registrationPort = 50000; // Would get from test resources
        
        registrationHelper = new ModuleRegistrationTestHelper(registrationHost, registrationPort);
        
        // Register a processor module
        RegistrationStatus response = registrationHelper.registerProcessorModule(
                "test-chunker", "localhost", 50051
        );
        
        LOG.info("Registered module with message: {}", response.getMessage());
        
        // Verify registration
        var modules = registrationHelper.getAllModules();
        assertThat(modules).isNotEmpty();
        
        // Clean up - using the service name as ID
        registrationHelper.deregisterModule("test-chunker");
    }
    
    @Test
    void demonstratePipelineCreation() {
        LOG.info("=== Pipeline Creation Example ===");
        
        // Create a simple pipeline
        List<String> steps = Arrays.asList("chunker", "tika", "embedder", "opensearch-sink");
        PipelineConfig pipeline = PipelineManagementTestHelper.createSimplePipeline(
                "document-processing-pipeline", steps
        );
        
        // Log pipeline details
        PipelineManagementTestHelper.logPipelineDetails(pipeline);
        
        // Verify pipeline structure
        assertThat(pipeline.name()).isEqualTo("document-processing-pipeline");
        assertThat(pipeline.pipelineSteps()).hasSize(4);
    }
    
    @Test
    void demonstrateTestDataCreation() {
        LOG.info("=== Test Data Creation Example ===");
        
        // Create various test data scenarios
        
        // 1. Simple text processing
        List<PipeStream> textData = TestDataBuilder.Scenarios.simpleTextProcessing();
        assertThat(textData).hasSize(3);
        
        // 2. Mixed content types
        List<PipeStream> mixedData = TestDataBuilder.Scenarios.mixedContentTypes();
        assertThat(mixedData)
                .hasSize(3);
        
        // 3. Custom data with metadata
        PipeStream customDoc = TestDataBuilder.create()
                .withDocumentAndMetadata(
                        "custom-doc-1",
                        "This is custom content",
                        java.util.Map.of(
                                "author", "Test Suite",
                                "version", "1.0",
                                "tags", "test,example"
                        )
                )
                .buildSingle();
        
        assertThat(customDoc.getDocument().hasCustomData()).isTrue();
        
        // 4. Large document
        PipeStream largeDoc = TestDataBuilder.create()
                .withLargeDocument("large-doc", 100) // 100 KB document
                .buildSingle();
        
        assertThat(largeDoc.getDocument().getBody().length()).isGreaterThan(100_000);
    }
    
    @Test
    @Disabled("Requires chunker container to be running")
    void demonstrateGrpcTesting() {
        LOG.info("=== gRPC Testing Example ===");
        
        // Get connection info for a module
        TestResourcesManager.ContainerInfo chunkerInfo = resourcesManager.getContainerInfo("chunker");
        LOG.info("Chunker service: {}", chunkerInfo);
        
        // Create gRPC helper
        grpcHelper = new GrpcTestHelper(chunkerInfo.getHost(), chunkerInfo.getPort());
        
        // Wait for service to be healthy
        assertThat(grpcHelper.waitForHealthy(30, TimeUnit.SECONDS))
                .as("Chunker service should be healthy")
                .isTrue();
        
        // Test processing
        PipeStream testDoc = TestDataBuilder.create()
                .withTextDocument("test-1", "Test content for chunking")
                .buildSingle();
        
        // Note: Actual processing would require converting PipeStream to ProcessRequest
        // This is just a demonstration of the test utilities
        LOG.info("Created test document: {}", testDoc.getDocument().getId());
    }
    
    @Test
    @Disabled("Requires all containers to be configured")
    void demonstrateResourcesManager() {
        LOG.info("=== Resources Manager Example ===");
        
        // Get all container connection info
        LOG.info("Container endpoints:");
        
        // Base infrastructure
        LOG.info(" - Consul: {}", resourcesManager.getContainerInfo("consul"));
        LOG.info(" - Kafka: {}", resourcesManager.getContainerInfo("kafka"));
        LOG.info(" - Apicurio: {}", resourcesManager.getContainerInfo("apicurio"));
        
        // Module containers
        for (String module : Arrays.asList("chunker", "tika", "embedder", "echo", "test-module")) {
            try {
                LOG.info(" - {}: {}", module, resourcesManager.getContainerInfo(module));
            } catch (Exception e) {
                LOG.warn(" - {}: Not available", module);
            }
        }
        
        // Wait for a condition
        boolean conditionMet = TestResourcesManager.waitFor(
                () -> applicationContext.getProperty("kafka.bootstrap.servers", String.class).isPresent(),
                10, TimeUnit.SECONDS
        );
        
        assertThat(conditionMet).as("Kafka should be available").isTrue();
    }
}