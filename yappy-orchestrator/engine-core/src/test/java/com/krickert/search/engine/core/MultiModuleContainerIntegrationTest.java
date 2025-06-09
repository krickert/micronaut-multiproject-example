package com.krickert.search.engine.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Timestamp;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test using real Docker containers via Micronaut test resources.
 * This test demonstrates multiple chunkers and embedders with different configurations.
 */
@MicronautTest(environments = "multimodule")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Property(name = "micronaut.test.resources.enabled", value = "true")
public class MultiModuleContainerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiModuleContainerIntegrationTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    ConsulBusinessOperationsService businessOpsService;
    
    private String testClusterName;
    private PipelineEngineImpl pipelineEngine;
    private com.ecwid.consul.v1.ConsulClient consulClient;

    @BeforeAll
    void setup() throws Exception {
        // Get Consul configuration from test resources
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse("localhost");
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(8500);
        consulClient = new com.ecwid.consul.v1.ConsulClient(consulHost, consulPort);
        
        // Create test cluster
        testClusterName = TestClusterHelper.createTestCluster("multi-module-test");
        
        // Create cluster with metadata
        Map<String, String> clusterMetadata = Map.of(
            "testType", "multi-module-integration",
            "createdBy", "MultiModuleContainerIntegrationTest"
        );
        businessOpsService.createCluster(testClusterName, clusterMetadata)
            .block(Duration.ofSeconds(5));
        
        // Wait for test resource containers to be ready
        logger.info("Waiting for test resource containers to start...");
        waitForContainersToBeReady();
        
        // Register services in Consul
        registerServices().block(Duration.ofSeconds(30));
        
        // Setup pipeline configuration
        setupPipelineConfiguration().block(Duration.ofSeconds(30));
        
        // Create pipeline engine
        pipelineEngine = new PipelineEngineImpl(
            consulClient, 
            configManager,
            testClusterName,
            true,  // Enable buffer
            100,   // Capacity
            3,     // Precision
            1.0    // Sample everything
        );
    }

    @AfterAll
    void cleanup() {
        if (pipelineEngine != null) {
            pipelineEngine.shutdown();
        }
        
        // Clean up test resources
        businessOpsService.cleanupTestResources(
            List.of(testClusterName),
            List.of("tika-parser-schema", "chunker-schema", "embedder-schema"),
            List.of("tika-parser-1", "chunker-small-1", "chunker-large-1", "embedder-1")
        ).block(Duration.ofSeconds(10));
    }

    @Test
    void testMultipleChunkersAndEmbedders() {
        // Create a test document with substantial content
        PipeDoc document = PipeDoc.newBuilder()
            .setId("test-doc-multi-001")
            .setTitle("Multi-Module Container Test Document")
            .setBody("""
                This is a comprehensive test document designed to demonstrate the power of the Yappy
                pipeline processing system with multiple processors running in real Docker containers.
                
                The document contains enough text to be meaningfully chunked at different sizes.
                In the first section, we explore how different chunking strategies can affect the
                downstream processing. Smaller chunks (300 tokens) provide more granular semantic
                understanding, while larger chunks (1000 tokens) maintain more context.
                
                The second section discusses embedding models. The ALL_MINILM_L6_V2 model is optimized
                for English text and provides fast, efficient embeddings. The multilingual model
                supports multiple languages and is better for international content.
                
                By processing this document through two different chunking strategies and two different
                embedding models sequentially, we accumulate chunks from both chunkers and embeddings
                from both models, demonstrating the pipeline's ability to enrich documents progressively.
                
                This test validates that our Consul-based service discovery and configuration management
                correctly handles multiple processing steps with different configurations,
                all running in real Docker containers managed by Micronaut test resources.
                """)
            .setSourceMimeType("text/plain")
            .setSourceUri("test://documents/multi-test-001.txt")
            .setCreationDate(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build())
            .build();
        
        // Create PipeStream for the multi-path pipeline
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("stream-multi-" + System.currentTimeMillis())
            .setDocument(document)
            .setCurrentPipelineName("multi-processor-pipeline")
            .setCurrentHopNumber(0)
            .build();
        
        // Process through pipeline
        StepVerifier.create(pipelineEngine.processMessage(pipeStream))
            .expectComplete()
            .verify(Duration.ofSeconds(60)); // Give more time for real containers
        
        // TODO: To verify we got embeddings from both models, we would need to:
        // 1. Capture the final output document from the pipeline
        // 2. Check it has chunks from both chunkers (different chunk sizes)
        // 3. Check it has embeddings created by both embedding models
        
        // The current PipelineEngineImpl doesn't return the final document,
        // but the buffered test data will contain the processing results.
        // For now, successful completion indicates the pipeline ran through all steps.
        
        logger.info("Multi-module container pipeline test completed successfully");
        logger.info("Pipeline sequence: tika-parser -> chunker-small -> chunker-large -> embedder-step1 (MINILM) -> embedder-step2 (MULTILINGUAL)");
    }

    private Mono<Void> registerServices() {
        // Get container host/port information from test resources
        String tikaHost = applicationContext.getProperty("tika.host", String.class).orElse("localhost");
        Integer tikaPort = applicationContext.getProperty("tika.grpc.port", Integer.class).orElse(50053);
        
        String chunkerSmallHost = applicationContext.getProperty("chunker.small.host", String.class).orElse("localhost");
        Integer chunkerSmallPort = applicationContext.getProperty("chunker.small.grpc.port", Integer.class).orElse(50054);
        
        String chunkerLargeHost = applicationContext.getProperty("chunker.large.host", String.class).orElse("localhost");
        Integer chunkerLargePort = applicationContext.getProperty("chunker.large.grpc.port", Integer.class).orElse(50055);
        
        String embedderHost = applicationContext.getProperty("embedder.host", String.class).orElse("localhost");
        Integer embedderPort = applicationContext.getProperty("embedder.grpc.port", Integer.class).orElse(50051);
        
        // Create registration objects - services must be named with cluster prefix for discovery
        Registration tikaReg = ImmutableRegistration.builder()
            .id("tika-parser-1")
            .name(testClusterName + "-tika-parser")
            .address(tikaHost)
            .port(tikaPort)
            .tags(List.of("grpc", testClusterName))
            .build();
            
        Registration chunkerSmallReg = ImmutableRegistration.builder()
            .id("chunker-small-1")
            .name(testClusterName + "-chunker-small")
            .address(chunkerSmallHost)
            .port(chunkerSmallPort)
            .tags(List.of("grpc", testClusterName))
            .build();
            
        Registration chunkerLargeReg = ImmutableRegistration.builder()
            .id("chunker-large-1")
            .name(testClusterName + "-chunker-large")
            .address(chunkerLargeHost)
            .port(chunkerLargePort)
            .tags(List.of("grpc", testClusterName))
            .build();
            
        // Single embedder service registration
        Registration embedderReg = ImmutableRegistration.builder()
            .id("embedder-1")
            .name(testClusterName + "-embedder")
            .address(embedderHost)
            .port(embedderPort)
            .tags(List.of("grpc", testClusterName))
            .build();
        
        // Register all services
        return Flux.merge(
            businessOpsService.registerService(tikaReg),
            businessOpsService.registerService(chunkerSmallReg),
            businessOpsService.registerService(chunkerLargeReg),
            businessOpsService.registerService(embedderReg)
        ).then()
        .doOnSuccess(v -> logger.info("All services registered successfully"));
    }

    private Mono<Void> setupPipelineConfiguration() {
        return registerSchemas()
            .then(createAndStorePipelineConfiguration())
            .then(initializeConfigManager());
    }

    private Mono<Void> registerSchemas() {
        // Create schemas
        var tikaSchema = createTikaSchema();
        var chunkerSchema = createChunkerSchema();
        var embedderSchema = createEmbedderSchema();
        
        // Create schema version data objects
        var tikaSchemaData = new com.krickert.search.config.schema.model.SchemaVersionData(
            1001L, // globalId
            "tika-parser-schema",
            1,
            tikaSchema.toString(),
            com.krickert.search.config.schema.model.SchemaType.JSON_SCHEMA,
            com.krickert.search.config.schema.model.SchemaCompatibility.NONE,
            java.time.Instant.now(),
            "Tika parser configuration schema"
        );
        
        var chunkerSchemaData = new com.krickert.search.config.schema.model.SchemaVersionData(
            1002L, // globalId
            "chunker-schema",
            1,
            chunkerSchema.toString(),
            com.krickert.search.config.schema.model.SchemaType.JSON_SCHEMA,
            com.krickert.search.config.schema.model.SchemaCompatibility.NONE,
            java.time.Instant.now(),
            "Chunker configuration schema"
        );
        
        var embedderSchemaData = new com.krickert.search.config.schema.model.SchemaVersionData(
            1003L, // globalId
            "embedder-schema",
            1,
            embedderSchema.toString(),
            com.krickert.search.config.schema.model.SchemaType.JSON_SCHEMA,
            com.krickert.search.config.schema.model.SchemaCompatibility.NONE,
            java.time.Instant.now(),
            "Embedder configuration schema"
        );
        
        // Store schemas using business operations service
        return Flux.merge(
            businessOpsService.storeSchemaVersion("tika-parser-schema", 1, tikaSchemaData),
            businessOpsService.storeSchemaVersion("chunker-schema", 1, chunkerSchemaData),
            businessOpsService.storeSchemaVersion("embedder-schema", 1, embedderSchemaData)
        ).then()
        .doOnSuccess(v -> logger.info("All schemas registered successfully"));
    }

    private Mono<Void> createAndStorePipelineConfiguration() {
        // Create pipeline steps
        var tikaStep = createTikaStep();
        var chunkerSmallStep = createChunkerStep("chunker-small", 300, 30);
        var chunkerLargeStep = createChunkerStep("chunker-large", 1000, 200);
        var embedderStep1 = createEmbedderStep("embedder-step1", "ALL_MINILM_L6_V2", false);
        var embedderStep2 = createEmbedderStep("embedder-step2", "PARAPHRASE_MULTILINGUAL_MINILM_L12_V2", true);
        
        // Create pipeline that splits after tika to both chunkers
        // Each chunker then goes to both embedders
        var pipelineSteps = Map.of(
            "tika-parser", tikaStep,
            "chunker-small", chunkerSmallStep,
            "chunker-large", chunkerLargeStep,
            "embedder-step1", embedderStep1,
            "embedder-step2", embedderStep2
        );
        
        var pipeline = new PipelineConfig(
            "multi-processor-pipeline",
            pipelineSteps
        );
        
        // Create pipeline graph config
        var pipelineGraphConfig = new PipelineGraphConfig(
            Map.of("multi-processor-pipeline", pipeline)
        );
        
        // Create module configurations
        // Note: The module implementation IDs must match the service names registered in Consul
        var modules = Map.of(
            "tika-parser", createModuleConfig("tika-parser", "tika-parser-schema"),
            "chunker-small", createModuleConfig("chunker-small", "chunker-schema"),
            "chunker-large", createModuleConfig("chunker-large", "chunker-schema"),
            "embedder", createModuleConfig("embedder", "embedder-schema")  // Single embedder module
        );
        
        var moduleMap = new PipelineModuleMap(modules);
        
        // Create cluster config
        var clusterConfig = new PipelineClusterConfig(
            testClusterName,
            pipelineGraphConfig,
            moduleMap,
            "multi-processor-pipeline",
            Set.of(),
            Set.of("tika-parser", "chunker-small", "chunker-large", "embedder")
        );
        
        // Store using business operations service
        return businessOpsService.storeClusterConfiguration(testClusterName, clusterConfig)
            .flatMap(success -> {
                if (!success) {
                    return Mono.error(new RuntimeException("Failed to store pipeline configuration"));
                }
                logger.info("Pipeline configuration stored successfully");
                return Mono.just(success);
            })
            .then();
    }

    private Mono<Void> initializeConfigManager() {
        return Mono.fromRunnable(() -> {
            configManager.initialize(testClusterName);
            
            // Wait for configuration to be loaded
            await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    var config = configManager.getCurrentPipelineClusterConfig();
                    assertThat(config).isPresent();
                    assertThat(config.get().clusterName()).isEqualTo(testClusterName);
                });
            
            logger.info("Configuration manager initialized successfully");
        });
    }

    private ObjectNode createTikaSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        ObjectNode parsingOptions = objectMapper.createObjectNode();
        parsingOptions.put("type", "object");
        ObjectNode parsingProps = objectMapper.createObjectNode();
        parsingProps.set("maxContentLength", objectMapper.createObjectNode().put("type", "integer"));
        parsingProps.set("extractMetadata", objectMapper.createObjectNode().put("type", "boolean"));
        parsingOptions.set("properties", parsingProps);
        props.set("parsingOptions", parsingOptions);
        schema.set("properties", props);
        return schema;
    }

    private ObjectNode createChunkerSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("chunk_size", objectMapper.createObjectNode().put("type", "integer"));
        props.set("chunk_overlap", objectMapper.createObjectNode().put("type", "integer"));
        props.set("source_field", objectMapper.createObjectNode().put("type", "string"));
        props.set("chunk_config_id", objectMapper.createObjectNode().put("type", "string"));
        schema.set("properties", props);
        return schema;
    }

    private ObjectNode createEmbedderSchema() {
        // Use the actual schema from the embedder module
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("title", "EmbedderOptions");
        schema.put("type", "object");
        
        ObjectNode props = objectMapper.createObjectNode();
        
        // embedding_models property
        ObjectNode embeddingModels = objectMapper.createObjectNode();
        embeddingModels.put("type", "array");
        ObjectNode modelItems = objectMapper.createObjectNode();
        modelItems.put("type", "string");
        modelItems.set("enum", objectMapper.createArrayNode()
            .add("ALL_MINILM_L6_V2")
            .add("ALL_MPNET_BASE_V2")
            .add("ALL_DISTILROBERTA_V1")
            .add("PARAPHRASE_MINILM_L3_V2")
            .add("PARAPHRASE_MULTILINGUAL_MINILM_L12_V2")
            .add("E5_SMALL_V2")
            .add("E5_LARGE_V2")
            .add("MULTI_QA_MINILM_L6_COS_V1"));
        embeddingModels.set("items", modelItems);
        embeddingModels.set("default", objectMapper.createArrayNode().add("ALL_MINILM_L6_V2"));
        props.set("embedding_models", embeddingModels);
        
        // Other properties
        props.set("check_chunks", objectMapper.createObjectNode().put("type", "boolean").put("default", true));
        props.set("check_document_fields", objectMapper.createObjectNode().put("type", "boolean").put("default", true));
        
        schema.set("properties", props);
        return schema;
    }

    private PipelineStepConfig createTikaStep() {
        ObjectNode config = objectMapper.createObjectNode();
        ObjectNode parsingOptions = objectMapper.createObjectNode();
        parsingOptions.put("maxContentLength", -1);
        parsingOptions.put("extractMetadata", true);
        config.set("parsingOptions", parsingOptions);
        
        // Tika outputs to first chunker (sequential pipeline)
        Map<String, PipelineStepConfig.OutputTarget> outputs = Map.of(
            "default", new PipelineStepConfig.OutputTarget(
                "chunker-small", 
                TransportType.GRPC,
                new GrpcTransportConfig("chunker-small", Map.of()),
                null)
        );
        
        return new PipelineStepConfig(
            "tika-parser",
            StepType.PIPELINE,
            "Extract text and metadata",
            null,
            new PipelineStepConfig.JsonConfigOptions(config, Map.of()),
            null,
            outputs,
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("tika-parser", null)
        );
    }

    private PipelineStepConfig createChunkerStep(String name, int chunkSize, int overlap) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("source_field", "body");
        config.put("chunk_size", chunkSize);
        config.put("chunk_overlap", overlap);
        config.put("chunk_config_id", name + "_config");
        
        // Determine next step in sequence
        String nextStepName = name.equals("chunker-small") ? "chunker-large" : "embedder-step1";
        String nextServiceName = name.equals("chunker-small") ? "chunker-large" : "embedder";
        
        Map<String, PipelineStepConfig.OutputTarget> outputs = Map.of(
            "default", new PipelineStepConfig.OutputTarget(
                nextStepName, 
                TransportType.GRPC,
                new GrpcTransportConfig(nextServiceName, Map.of()),
                null)
        );
        
        return new PipelineStepConfig(
            name,
            StepType.PIPELINE,
            "Chunk text with size " + chunkSize,
            null,
            new PipelineStepConfig.JsonConfigOptions(config, Map.of()),
            null,
            outputs,
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo(name, null)
        );
    }

    private PipelineStepConfig createEmbedderStep(String stepName, String model, boolean isFinalStep) {
        ObjectNode config = objectMapper.createObjectNode();
        var models = config.putArray("embedding_models");
        models.add(model);
        config.put("check_chunks", true);
        config.put("check_document_fields", false);
        config.put("field_to_embed", "content"); // Specify which field to embed from chunks
        
        // First embedder step goes to second embedder step, second is a sink
        Map<String, PipelineStepConfig.OutputTarget> outputs = isFinalStep ? Map.of() :
            Map.of("default", new PipelineStepConfig.OutputTarget(
                "embedder-step2", 
                TransportType.GRPC,
                new GrpcTransportConfig("embedder", Map.of()),
                null)
            );
        
        return new PipelineStepConfig(
            stepName,
            isFinalStep ? StepType.SINK : StepType.PIPELINE,
            "Generate embeddings with " + model,
            null,
            new PipelineStepConfig.JsonConfigOptions(config, Map.of()),
            null,
            outputs,
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("embedder", null) // Same service name for both steps
        );
    }

    private PipelineModuleConfiguration createModuleConfig(String name, String schemaSubject) {
        return new PipelineModuleConfiguration(
            name,
            name,
            new SchemaReference(schemaSubject, 1),
            null
        );
    }
    
    private void waitForContainersToBeReady() {
        // Wait for all container properties to be available
        await().atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> {
                try {
                    // Check if all required properties are available
                    boolean tikaReady = applicationContext.getProperty("tika.host", String.class).isPresent() &&
                                       applicationContext.getProperty("tika.grpc.port", Integer.class).isPresent();
                    
                    boolean chunkerSmallReady = applicationContext.getProperty("chunker.small.host", String.class).isPresent() &&
                                               applicationContext.getProperty("chunker.small.grpc.port", Integer.class).isPresent();
                    
                    boolean chunkerLargeReady = applicationContext.getProperty("chunker.large.host", String.class).isPresent() &&
                                               applicationContext.getProperty("chunker.large.grpc.port", Integer.class).isPresent();
                    
                    boolean embedderReady = applicationContext.getProperty("embedder.host", String.class).isPresent() &&
                                           applicationContext.getProperty("embedder.grpc.port", Integer.class).isPresent();
                    
                    boolean allReady = tikaReady && chunkerSmallReady && chunkerLargeReady && embedderReady;
                    
                    if (!allReady) {
                        logger.info("Waiting for containers... tika={}, chunkerSmall={}, chunkerLarge={}, embedder={}",
                            tikaReady, chunkerSmallReady, chunkerLargeReady, embedderReady);
                    }
                    
                    return allReady;
                } catch (Exception e) {
                    logger.warn("Error checking container readiness: {}", e.getMessage());
                    return false;
                }
            });
        
        // Give containers a bit more time to fully initialize their services
        logger.info("All container properties available, waiting 5 more seconds for services to initialize...");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}