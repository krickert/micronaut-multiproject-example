package com.krickert.search.engine.integration;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
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
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.schema.model.SchemaVersionData;
import com.krickert.search.config.schema.model.SchemaType;
import java.time.Instant;

/**
 * Scenario 1 with Debug: Document -> Chunker -> TestModule -> Validate
 * 
 * Uses test-resources with the module-test environment.
 * Test-module outputs to Kafka for verification.
 */
@Disabled("Engine test resource provider not starting - need to debug")
@MicronautTest
@KafkaListener(groupId = "chunker-test-listener", 
               offsetReset = io.micronaut.configuration.kafka.annotation.OffsetReset.EARLIEST)
class EngineScenario1Test {

    private static final Logger logger = LoggerFactory.getLogger(EngineScenario1Test.class);

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
    BusinessOperationsService businessOpsService;

    @Inject
    DynamicConfigurationManager configManager;

    @Inject
    SchemaValidationService schemaValidationService;

    @Inject
    ConsulKvService consulKvService;
    
    @Inject
    ObjectMapper objectMapper;

    // Engine container properties injected by test resources
    @Property(name = "engine.grpc.host")
    String engineHost;
    
    @Property(name = "engine.grpc.port")
    Integer enginePort;

    // Module container properties for registration
    @Property(name = "chunker.grpc.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port")
    Integer chunkerPort;
    
    @Property(name = "test-module.grpc.host")
    String testModuleHost;
    
    @Property(name = "test-module.grpc.port")
    Integer testModulePort;

    // Internal port numbers for Docker network communication
    private static final int CHUNKER_INTERNAL_PORT = 50051;
    private static final int TEST_MODULE_INTERNAL_PORT = 50062;
    
    // Use the engine container's cluster name
    private static final String CLUSTER_NAME = "test-cluster";

    private String clusterName = CLUSTER_NAME;
    
    private ManagedChannel engineChannel;
    private PipeStreamEngineGrpc.PipeStreamEngineBlockingStub engineStub;

    @BeforeEach
    void setup() {
        logger.info("Using cluster name: {}", clusterName);
        
        // Log the injected test resource properties
        logger.info("Test resources configuration:");
        logger.info("  Engine: {}:{}", engineHost, enginePort);
        logger.info("  Chunker: {}:{}", chunkerHost, chunkerPort);
        logger.info("  Test-module: {}:{}", testModuleHost, testModulePort);
        
        // Check if engine properties were properly injected
        if ("localhost".equals(engineHost) && enginePort == 50070) {
            logger.warn("Engine container may not have started - using default values");
        }

        // Clear any previous messages
        receivedMessages.clear();
        
        // Create gRPC channel to engine container
        logger.info("Connecting to engine at {}:{}", engineHost, enginePort);
        engineChannel = ManagedChannelBuilder
            .forAddress(engineHost, enginePort)
            .usePlaintext()
            .build();
        
        engineStub = PipeStreamEngineGrpc.newBlockingStub(engineChannel);

        // Clean up any stale Consul data from previous test runs
        cleanupStaleConsulData();

        // Store the schemas first, before any configuration that references them
        logger.info("Storing schemas in Consul...");
        storeTestModuleSchema();
        storeChunkerSchema();

        // Give ample time for schemas to be stored and propagated in Consul
        // This is critical because the DynamicConfigurationManager watches for changes
        // and will reject configurations if schemas are not available
        logger.info("Waiting for schemas to propagate in Consul...");
        try {
            Thread.sleep(3000);  // Wait for schemas to be fully available
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now setup pipeline configuration - schemas should be available
        logger.info("Setting up pipeline configuration...");
        setupPipelineConfiguration()
            .doOnSuccess(v -> logger.info("Pipeline configuration setup completed"))
            .doOnError(e -> logger.error("Failed to setup pipeline configuration", e))
            .block(Duration.ofSeconds(30));

        // Give Consul time to propagate the configuration changes and for watchers to update
        try {
            Thread.sleep(5000);  // Increased delay to allow watchers to pick up schema changes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Wait for configuration to be available - allow more time for Consul to stabilize
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))  // Increased timeout
            .pollInterval(Duration.ofSeconds(2))  // Increased poll interval
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
        // Store test-module schema as SchemaVersionData in the format expected by DynamicConfigurationManager
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

        // Create SchemaVersionData object
        SchemaVersionData schemaData = new SchemaVersionData(
            null, // globalId
            "test-module-schema", // subject
            1, // version
            testModuleSchema, // schemaContent
            SchemaType.JSON_SCHEMA, // schemaType
            null, // compatibility
            Instant.now(), // createdAt
            "Test module schema for outputting to various targets" // versionDescription
        );

        try {
            // Store schema at the expected location: config/pipeline/schemas/{subject}/{version}
            String schemaKey = "config/pipeline/schemas/test-module-schema/1";
            String schemaJson = objectMapper.writeValueAsString(schemaData);
            consulKvService.putValue(schemaKey, schemaJson).block();
            logger.info("Stored test-module schema at key: {}", schemaKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store test-module schema", e);
        }
    }

    private void storeChunkerSchema() {
        // Define chunker schema directly in the test
        String chunkerSchema = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "source_field": {
                  "type": "string",
                  "default": "body",
                  "description": "Field to chunk from the document"
                },
                "chunk_size": {
                  "type": "integer",
                  "default": 512,
                  "minimum": 1,
                  "description": "Size of each chunk in characters"
                },
                "overlap_size": {
                  "type": "integer",
                  "default": 50,
                  "minimum": 0,
                  "description": "Overlap between chunks"
                },
                "chunk_overlap": {
                  "type": "integer",
                  "default": 50,
                  "minimum": 0,
                  "description": "Alias for overlap_size"
                },
                "chunk_config_id": {
                  "type": "string",
                  "description": "Unique identifier for this chunk configuration"
                },
                "chunk_id_template": {
                  "type": "string",
                  "default": "%s_%s_chunk_%d",
                  "description": "Template for chunk ID generation"
                },
                "result_set_name_template": {
                  "type": "string", 
                  "default": "%s_%s_chunks",
                  "description": "Template for result set name"
                },
                "log_prefix": {
                  "type": "string",
                  "default": "[CHUNKER] ",
                  "description": "Prefix for log messages"
                }
              },
              "additionalProperties": false
            }
            """;

        // Validate the schema using SchemaValidationService  
        logger.info("Validating chunker schema JSON syntax before storing...");
        Boolean isValidJson = schemaValidationService.isValidJson(chunkerSchema).block();
        if (isValidJson == null || !isValidJson) {
            throw new RuntimeException("Chunker schema is not valid JSON");
        }
        logger.info("Chunker schema JSON validation passed");

        // Create SchemaVersionData object
        SchemaVersionData schemaData = new SchemaVersionData(
            null, // globalId
            "chunker-schema", // subject
            1, // version
            chunkerSchema, // schemaContent
            SchemaType.JSON_SCHEMA, // schemaType
            null, // compatibility
            Instant.now(), // createdAt
            "Chunker module schema for document chunking configuration" // versionDescription
        );

        try {
            // Store schema at the expected location: config/pipeline/schemas/{subject}/{version}
            String schemaKey = "config/pipeline/schemas/chunker-schema/1";
            String schemaJson = objectMapper.writeValueAsString(schemaData);
            consulKvService.putValue(schemaKey, schemaJson).block();
            logger.info("Stored chunker schema at key: {}", schemaKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store chunker schema", e);
        }
    }

    private Mono<Void> setupPipelineConfiguration() {
        // Register services with Docker network aliases for containerized engine
        // The engine container will connect to modules using Docker network aliases
        logger.info("Registering services for containerized engine - chunker at chunker:{}, test-module at test-module:{}", 
            CHUNKER_INTERNAL_PORT, TEST_MODULE_INTERNAL_PORT);

        // Register chunker service with Docker network alias
        Registration chunkerReg = ImmutableRegistration.builder()
                .id(clusterName + "-chunker-test")
                .name(clusterName + "-chunker")
                .address("chunker")  // Docker network alias
                .port(CHUNKER_INTERNAL_PORT)  // Internal container port
                .build();

        // Register test-module service with Docker network alias  
        Registration testModuleReg = ImmutableRegistration.builder()
                .id(clusterName + "-test-module-test")
                .name(clusterName + "-test-module")
                .address("test-module")  // Docker network alias
                .port(TEST_MODULE_INTERNAL_PORT)  // Internal container port
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
                    "chunk_overlap": 10,
                    "chunk_config_id": "test-chunker-config",
                    "chunk_id_template": "%s_%s_chunk_%d",
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

        // When - send to engine via gRPC
        logger.info("Sending document to engine via gRPC");
        try {
            engineStub.processPipeAsync(inputStream);
            logger.info("Document sent successfully to engine");
        } catch (Exception e) {
            logger.error("Failed to send document to engine", e);
            throw e;
        }

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
    
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        if (engineChannel != null && !engineChannel.isShutdown()) {
            logger.info("Shutting down gRPC channel");
            engineChannel.shutdown();
            try {
                // Wait for channel to terminate
                if (!engineChannel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    engineChannel.shutdownNow();
                }
            } catch (InterruptedException e) {
                engineChannel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
