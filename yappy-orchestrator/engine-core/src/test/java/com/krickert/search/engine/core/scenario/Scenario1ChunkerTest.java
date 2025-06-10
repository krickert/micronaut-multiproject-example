package com.krickert.search.engine.core.scenario;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.core.PipelineEngine;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scenario 1: Document -> Chunker -> Validate Output
 * 
 * Uses test-resources with the module-test environment.
 */
@MicronautTest(environments = "module-test")
class Scenario1ChunkerTest {
    
    private static final Logger logger = LoggerFactory.getLogger(Scenario1ChunkerTest.class);
    
    @Inject
    PipelineEngine pipelineEngine;
    
    @Inject
    BusinessOperationsService businessOpsService;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Property(name = "chunker.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port") 
    int chunkerPort;
    
    @Property(name = "app.config.cluster-name")
    String clusterName;
    
    @BeforeEach
    void setUp() {
        logger.info("Setting up test with cluster name: {}", clusterName);
        
        // Set up minimal pipeline configuration for testing
        setupPipelineConfiguration()
            .doOnSuccess(v -> logger.info("Pipeline configuration setup completed"))
            .doOnError(e -> logger.error("Failed to setup pipeline configuration", e))
            .block(Duration.ofSeconds(30));
        
        // Wait for configuration to be available in the configuration manager
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                var config = configManager.getCurrentPipelineClusterConfig();
                logger.debug("Current cluster config: {}", config.isPresent() ? "present" : "absent");
                if (config.isEmpty()) {
                    throw new AssertionError("Pipeline cluster configuration not yet available");
                }
                var pipelineConfig = configManager.getPipelineConfig("test-pipeline");
                logger.debug("Pipeline config for 'test-pipeline': {}", pipelineConfig.isPresent() ? "present" : "absent");
                if (pipelineConfig.isEmpty()) {
                    throw new AssertionError("Test pipeline configuration not yet available");
                }
                logger.info("Configuration is now available for testing");
            });
    }
    
    private Mono<Void> setupPipelineConfiguration() {
        // Register chunker service with cluster prefix for service discovery
        Registration chunkerReg = ImmutableRegistration.builder()
                .id(clusterName + "-chunker-test")
                .name(clusterName + "-chunker")
                .address(chunkerHost)
                .port(chunkerPort)
                .build();
        
        return businessOpsService.registerService(chunkerReg)
            .then(createAndStorePipelineConfiguration());
    }
    
    private Mono<Void> createAndStorePipelineConfiguration() {
        // For now, create a simple step without custom configuration to avoid validation issues
        // We'll add configuration once we implement proper schema handling
        
        // Create ProcessorInfo for chunker (gRPC service)
        var processorInfo = new PipelineStepConfig.ProcessorInfo("chunker", null);
        
        // Create chunker step with minimal configuration
        var chunkerStep = new PipelineStepConfig(
            "chunker",
            StepType.PIPELINE, // Chunker is a processing step, not a sink
            processorInfo
        );
        
        // Create test pipeline with just chunker
        var pipeline = new PipelineConfig(
            "test-pipeline",
            Map.of("chunker", chunkerStep)
        );
        
        // Create pipeline graph
        var pipelineGraph = new PipelineGraphConfig(
            Map.of("test-pipeline", pipeline)
        );
        
        // Create module configuration for chunker
        var moduleConfig = new PipelineModuleConfiguration(
            "chunker",
            "chunker",
            null // No schema reference needed for test
        );
        
        var moduleMap = new PipelineModuleMap(
            Map.of("chunker", moduleConfig)
        );
        
        // Create cluster config
        var clusterConfig = new PipelineClusterConfig(
            clusterName,
            pipelineGraph,
            moduleMap,
            "test-pipeline",
            Set.of(), // No whitelisted topics
            Set.of("chunker") // Active modules
        );
        
        // Store configuration
        return businessOpsService.storeClusterConfiguration(clusterName, clusterConfig)
            .flatMap(success -> {
                if (!success) {
                    return Mono.error(new RuntimeException("Failed to store pipeline configuration"));
                }
                logger.info("Pipeline configuration stored successfully");
                return Mono.empty();
            });
    }
    
    @Test
    void testScenario1_DocumentToChunker() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc-1")
                .setTitle("Test Document")
                .setBody("First sentence goes here. Second sentence is here. Third one too. And a fourth.")
                .build();
        
        PipeStream input = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("chunker")
                .build();
        
        logger.info("Sending document {} through chunker", doc.getId());
        
        // When
        var result = pipelineEngine.processMessage(input)
                .doOnSubscribe(s -> logger.info("Starting pipeline processing"))
                .doOnSuccess(v -> logger.info("Pipeline processing completed successfully"))
                .doOnError(e -> logger.error("Pipeline processing failed", e))
                .doOnTerminate(() -> logger.info("Pipeline processing terminated"));
        
        // Then - just verify it completes without error
        StepVerifier.create(result)
                .expectComplete()
                .verify(Duration.ofSeconds(30));
        
        logger.info("Document processed successfully");
        
        // TODO: To verify output, we would need to either:
        // 1. Have a sink that captures the output
        // 2. Query the downstream system
        // 3. Have the engine return results
    }
}