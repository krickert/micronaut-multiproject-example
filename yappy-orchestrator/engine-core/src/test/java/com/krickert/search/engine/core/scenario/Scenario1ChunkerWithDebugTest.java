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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.krickert.yappy.modules.chunker.ChunkerOptions;
import com.krickert.search.config.consul.service.ConsulKvService;

/**
 * Scenario 1 with Debug: Document -> Chunker -> TestModule -> Validate
 * 
 * Uses test-resources with the module-test environment.
 * Test-module outputs to Kafka for verification.
 */
@MicronautTest(environments = "module-test")
@KafkaListener(groupId = "chunker-test-listener", 
               offsetReset = io.micronaut.configuration.kafka.annotation.OffsetReset.EARLIEST)
class Scenario1ChunkerWithDebugTest {
    
    private static final Logger logger = LoggerFactory.getLogger(Scenario1ChunkerWithDebugTest.class);
    
    // List to collect messages from test-module via Kafka
    private final List<PipeStream> receivedMessages = new CopyOnWriteArrayList<>();
    
    @Topic("test-module-output")
    void receiveFromTestModule(UUID key, PipeStream value) {
        logger.info("Received message from test-module: streamId={}, docId={}, chunks={}", 
            value.getStreamId(), 
            value.hasDocument() ? value.getDocument().getId() : "no-doc",
            value.hasDocument() && value.getDocument().getSemanticResultsCount() > 0 
                ? value.getDocument().getSemanticResults(0).getChunksCount() : 0);
        receivedMessages.add(value);
    }
    
    @Inject
    PipelineEngine pipelineEngine;
    
    @Inject
    BusinessOperationsService businessOpsService;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    ConsulSchemaRegistryDelegate schemaRegistryDelegate;
    
    @Inject
    SchemaValidationService schemaValidationService;
    
    @Inject
    ConsulKvService consulKvService;
    
    @Property(name = "chunker.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port") 
    int chunkerPort;
    
    @Property(name = "test-module-after-chunker.host")
    String testModuleHost;
    
    @Property(name = "test-module-after-chunker.grpc.port") 
    int testModulePort;
    
    @Property(name = "app.config.cluster-name")
    String baseClusterName;
    
    private String clusterName;
    
    @BeforeEach
    void setup() {
        // For now, use the base cluster name directly to avoid watching issues
        // TODO: Fix DynamicConfigurationManager to support dynamic cluster names
        clusterName = baseClusterName;
        logger.info("Using cluster name: {}", clusterName);
        
        // Clear any previous messages
        receivedMessages.clear();
        
        // Clean up any stale Consul data from previous test runs
        cleanupStaleConsulData();
        
        // First store the schemas in Consul
        storeTestModuleSchema();
        storeChunkerSchema();
        
        // Setup pipeline configuration with chunker and test-module
        setupPipelineConfiguration()
            .doOnSuccess(v -> logger.info("Pipeline configuration setup completed"))
            .doOnError(e -> logger.error("Failed to setup pipeline configuration", e))
            .block(Duration.ofSeconds(30));
        
        // Give Consul time to propagate the configuration changes
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Wait for configuration to be available - allow more time for Consul to stabilize
        Awaitility.await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofSeconds(1))
            .ignoreExceptions()  // Ignore intermediate exceptions while waiting for config
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
    
    private void cleanupStaleConsulData() {
        try {
            // List all keys under configs/ to see what's there
            var keys = consulKvService.getKeysWithPrefix("configs/").block();
            if (keys != null && !keys.isEmpty()) {
                logger.info("Found {} keys under configs/: {}", keys.size(), keys);
                
                // Delete any stale cluster configurations with UUID suffixes
                for (String key : keys) {
                    if (key.startsWith("configs/module-test-cluster-") && !key.equals("configs/" + clusterName)) {
                        logger.info("Deleting stale configuration: {}", key);
                        consulKvService.deleteKeysWithPrefix(key).block();
                    }
                }
            }
            
            // Also clean up any service registrations from previous test runs
            var services = businessOpsService.listServices().block();
            if (services != null) {
                for (String serviceName : services.keySet()) {
                    if (serviceName.startsWith("module-test-cluster-") && !serviceName.startsWith(clusterName + "-")) {
                        logger.info("Found stale service: {}", serviceName);
                        var instances = businessOpsService.getServiceInstances(serviceName).block();
                        if (instances != null) {
                            for (var instance : instances) {
                                logger.info("Deregistering stale service instance: {}", instance.getServiceId());
                                businessOpsService.deregisterService(instance.getServiceId()).block();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error during cleanup of stale Consul data: {}", e.getMessage());
            // Continue with test even if cleanup fails
        }
    }
    
    private void storeTestModuleSchema() {
        // Store test-module schema using ConsulSchemaRegistryDelegate
        String testModuleSchema = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "output_type": {
                  "type": "string",
                  "enum": ["CONSOLE", "KAFKA", "FILE"],
                  "default": "CONSOLE",
                  "description": "Where to output the processed documents"
                },
                "kafka_topic": {
                  "type": "string",
                  "description": "Kafka topic name (required when output_type is KAFKA)"
                },
                "file_path": {
                  "type": "string",
                  "description": "Directory path for output files (required when output_type is FILE)"
                },
                "file_prefix": {
                  "type": "string",
                  "default": "pipedoc",
                  "description": "Prefix for output filenames"
                }
              },
              "additionalProperties": false
            }
            """;
        
        // First validate the schema using SchemaValidationService
        logger.info("Validating test-module schema JSON syntax before storing...");
        Boolean isValidJson = schemaValidationService.isValidJson(testModuleSchema).block();
        if (isValidJson == null || !isValidJson) {
            throw new RuntimeException("Test-module schema is not valid JSON");
        }
        logger.info("Test-module schema JSON validation passed");
        
        // Store schema using the proper delegate
        String schemaId = "test-module-schema:1";
        schemaRegistryDelegate.saveSchema(schemaId, testModuleSchema).block();
        logger.info("Stored test-module schema with ID: {}", schemaId);
    }
    
    private void storeChunkerSchema() {
        // Get the chunker schema from ChunkerOptions
        String chunkerSchema = com.krickert.yappy.modules.chunker.ChunkerOptions.getJsonV7Schema();
        
        // Validate the schema using SchemaValidationService  
        logger.info("Validating chunker schema JSON syntax before storing...");
        Boolean isValidJson = schemaValidationService.isValidJson(chunkerSchema).block();
        if (isValidJson == null || !isValidJson) {
            throw new RuntimeException("Chunker schema is not valid JSON");
        }
        logger.info("Chunker schema JSON validation passed");
        
        // Store schema using the proper delegate
        String schemaId = "chunker-schema:1";
        schemaRegistryDelegate.saveSchema(schemaId, chunkerSchema).block();
        logger.info("Stored chunker schema with ID: {}", schemaId);
    }
    
    private Mono<Void> setupPipelineConfiguration() {
        // Use Docker bridge IP for container-to-container communication
        String dockerHostIp = "172.17.0.1";
        
        // Register chunker service
        Registration chunkerReg = ImmutableRegistration.builder()
                .id(clusterName + "-chunker-test")
                .name(clusterName + "-chunker")
                .address(dockerHostIp)
                .port(chunkerPort)
                .build();
        
        // Register test-module service  
        Registration testModuleReg = ImmutableRegistration.builder()
                .id(clusterName + "-test-module-test")
                .name(clusterName + "-test-module")
                .address(dockerHostIp)
                .port(testModulePort)
                .build();
        
        return businessOpsService.registerService(chunkerReg)
            .then(businessOpsService.registerService(testModuleReg))
            .then(createAndStorePipelineConfiguration());
    }
    
    private Mono<Void> createAndStorePipelineConfiguration() {
        try {
            // Initialize ObjectMapper for JSON parsing
            ObjectMapper mapper = new ObjectMapper();
            
            // Create ProcessorInfo for chunker (gRPC service)
            var chunkerProcessorInfo = new PipelineStepConfig.ProcessorInfo("chunker", null);
            
            // Create ProcessorInfo for test-module (gRPC service)
            var testModuleProcessorInfo = new PipelineStepConfig.ProcessorInfo("test-module", null);
            
            // Create custom config for chunker to produce multiple chunks
            String chunkerConfigJson = """
                {
                    "source_field": "body",
                    "chunk_size": 50,
                    "overlap_size": 10,
                    "chunk_config_id": "test-chunker-config",
                    "result_set_name_template": "%s_%s_chunks",
                    "log_prefix": "[TEST-CHUNKER] "
                }
                """;
            
            // Parse chunker JSON string to JsonNode
            var chunkerConfigNode = mapper.readTree(chunkerConfigJson);
            var chunkerConfig = new JsonConfigOptions(chunkerConfigNode, Map.of());
            
            // Create chunker step with custom config and output to test-module
            var chunkerStep = new PipelineStepConfig(
                "chunker",
                StepType.PIPELINE,
                "Chunk documents",
                "chunker-schema:1",  // Add explicit schema reference for chunker custom config
                chunkerConfig,  // Add custom config to produce multiple chunks
                Map.of("default", new PipelineStepConfig.OutputTarget(
                    "test-module",
                    TransportType.GRPC,
                    new GrpcTransportConfig("test-module", Map.of()),
                    null
                )),
                0, 1000L, 30000L, 2.0, null,
                chunkerProcessorInfo
            );
            
            // Create custom config for test-module to output to Kafka
            String testModuleConfigJson = """
                {
                    "output_type": "KAFKA",
                    "kafka_topic": "test-module-output"
                }
                """;
            
            // Parse JSON string to JsonNode
            var configNode = mapper.readTree(testModuleConfigJson);
            var testModuleConfig = new JsonConfigOptions(configNode, Map.of());
        
        // Create test-module step with custom config
        var testModuleStep = new PipelineStepConfig(
            "test-module",
            StepType.PIPELINE,  // Use PIPELINE instead of SINK as user suggested
            "Debug output",
            "test-module-schema:1",  // Schema ID reference with colon format
            testModuleConfig,
            Map.of(),  // No outputs - this is a sink
            0, 1000L, 30000L, 2.0, null,
            testModuleProcessorInfo
        );
        
        // Create test pipeline with chunker -> test-module
        var pipeline = new PipelineConfig(
            "test-pipeline",
            Map.of(
                "chunker", chunkerStep,
                "test-module", testModuleStep
            )
        );
        
        // Create pipeline graph
        var pipelineGraph = new PipelineGraphConfig(
            Map.of("test-pipeline", pipeline)
        );
        
        // Create module configurations
        var chunkerModuleConfig = new PipelineModuleConfiguration(
            "chunker",
            "chunker",
            new SchemaReference("chunker-schema", 1)  // Add schema reference for chunker
        );
        
        var testModuleModuleConfig = new PipelineModuleConfiguration(
            "test-module",
            "test-module",
            new SchemaReference("test-module-schema", 1)
        );
        
        var moduleMap = new PipelineModuleMap(
            Map.of(
                "chunker", chunkerModuleConfig,
                "test-module", testModuleModuleConfig
            )
        );
        
        // Create cluster config
        var clusterConfig = new PipelineClusterConfig(
            clusterName,
            pipelineGraph,
            moduleMap,
            "test-pipeline",
            Set.of(),
            Set.of("chunker", "test-module")
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
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Failed to create pipeline configuration", e));
        }
    }
    
    @Test
    void testScenario1_DocumentToChunkerWithDebugOutput() throws InterruptedException {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc-1")
                .setTitle("Test Document")
                .setBody("This is a test document that should be chunked. " +
                        "It contains multiple sentences that will be processed. " +
                        "The chunker should split this into smaller pieces. " +
                        "Each chunk will maintain some overlap with the previous chunk. " +
                        "This helps preserve context across chunk boundaries.")
                .build();
                
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("chunker")
                .build();
        
        logger.info("=== Starting test with document: {} ===", doc.getId());
        logger.info("Document body length: {} characters", doc.getBody().length());
        
        // When
        var result = pipelineEngine.processMessage(inputStream)
                .doOnSubscribe(s -> logger.info("Starting pipeline processing"))
                .doOnSuccess(v -> logger.info("Pipeline processing completed successfully"))
                .doOnError(e -> logger.error("Pipeline processing failed", e))
                .doOnTerminate(() -> logger.info("Pipeline processing terminated"));
        
        // Then - verify it completes without error
        StepVerifier.create(result)
                .expectComplete()
                .verify(Duration.ofSeconds(30));
        
        logger.info("=== Pipeline processing completed ===");
        
        // Wait for Kafka messages from test-module
        logger.info("Waiting for messages from test-module via Kafka...");
        
        // Give some time for the message to be processed through the pipeline
        Thread.sleep(2000);
        
        // Wait for Kafka messages from test-module (this test should FAIL if no messages received)
        logger.info("Waiting for Kafka messages from test-module. Current count: {}", receivedMessages.size());
        
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertFalse(receivedMessages.isEmpty(), 
                    "Expected to receive messages from test-module via Kafka. " +
                    "This indicates either: " +
                    "1. Test-module is not configured to output to Kafka, " +
                    "2. The custom config is not being passed properly, " +
                    "3. The pipeline routing is not working correctly, or " +
                    "4. KAFKA_ENABLED environment variable is not set for test-module");
                logger.info("SUCCESS: Received {} messages from test-module", receivedMessages.size());
            });
        
        // Verify chunker produced multiple chunks
        logger.info("=== Verifying chunker output ===");
        assertEquals(1, receivedMessages.size(), "Expected exactly one PipeStream message");
        
        PipeStream resultStream = receivedMessages.get(0);
        PipeDoc resultDoc = resultStream.getDocument();
        
        logger.info("Document ID: {}", resultDoc.getId());
        logger.info("Number of semantic results: {}", resultDoc.getSemanticResultsCount());
        
        // The chunker should have added semantic results
        assertTrue(resultDoc.getSemanticResultsCount() > 0, 
            "Expected at least one semantic result from chunker");
        
        // Get the semantic result from chunker
        var semanticResult = resultDoc.getSemanticResults(0);
        int chunkCount = semanticResult.getChunksCount();
        
        logger.info("=== Chunker Results ===");
        logger.info("Result set name: {}", semanticResult.getResultSetName());
        logger.info("Number of chunks created: {}", chunkCount);
        
        // Verify multiple chunks were created
        assertTrue(chunkCount > 1, 
            "Expected chunker to produce multiple chunks, but got: " + chunkCount);
        
        // Log details about each chunk
        for (int i = 0; i < chunkCount; i++) {
            var chunk = semanticResult.getChunks(i);
            logger.info("Chunk {}: ID={}, length={} chars", 
                i, 
                chunk.getChunkId(),
                chunk.getEmbeddingInfo().getTextContent().length());
            logger.info("  Content: '{}'", 
                chunk.getEmbeddingInfo().getTextContent()
                    .substring(0, Math.min(50, chunk.getEmbeddingInfo().getTextContent().length())) + "...");
        }
        
        logger.info("=== Test completed successfully! Chunker produced {} chunks ===", chunkCount);
    }
}