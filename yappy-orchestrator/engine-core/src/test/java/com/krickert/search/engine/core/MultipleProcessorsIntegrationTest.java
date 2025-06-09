package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import io.micronaut.context.annotation.Property;
import com.krickert.yappy.registration.RegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Timestamp;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test demonstrating multiple processors with different configurations.
 * This test runs real Docker containers for tika-parser, two chunkers, and two embedders.
 */
@MicronautTest(environments = "multiprocessor")
@Tag("integration")
public class MultipleProcessorsIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultipleProcessorsIntegrationTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);


    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    ConsulClient consulClient;
    
    @Property(name = "grpc.server.port")
    Integer engineGrpcPort;
    
    private PipelineEngineImpl pipelineEngine;

    
    @Property(name = "app.config.cluster-name")
    String clusterName;
    
    @BeforeEach
    void setup() throws Exception {
        // Services should already be running thanks to Test Resources
        
        // Use the cluster name from configuration
        
        // Register all modules using the engine's registration service
        registerModulesWithEngine();
        
        // Setup pipeline configuration in Consul
        setupPipelineConfiguration();
        
        // Create pipeline engine
        pipelineEngine = new PipelineEngineImpl(
            consulClient, 
            configManager,
            clusterName,
            true,  // Enable buffer
            100,   // Capacity
            3,     // Precision
            1.0    // Sample everything
        );
    }

    @AfterEach
    void cleanup() {
        if (pipelineEngine != null) {
            pipelineEngine.shutdown();
        }
        
        if (consulClient != null && clusterName != null) {
            TestClusterHelper.cleanupTestCluster(consulClient, clusterName);
        }
    }

    @Test
    void testMultipleChunkersAndEmbedders() {
        // Create a test document with substantial content
        PipeDoc document = PipeDoc.newBuilder()
            .setId("test-doc-multi-001")
            .setTitle("Multi-Processor Test Document")
            .setBody("""
                This is a comprehensive test document designed to demonstrate the power of the Yappy 
                pipeline processing system with multiple processors. The document contains enough text 
                to be meaningfully chunked at different sizes.
                
                In the first section, we explore how different chunking strategies can affect the 
                downstream processing. Smaller chunks (300 tokens) provide more granular semantic 
                understanding, while larger chunks (1000 tokens) maintain more context.
                
                The second section discusses embedding models. The ALL_MINILM_L6_V2 model is optimized 
                for English text and provides fast, efficient embeddings. The multilingual model 
                supports multiple languages and is better for international content.
                
                By processing this document through two different chunking strategies and two different 
                embedding models, we can demonstrate that the pipeline correctly routes documents 
                through multiple processing paths, maintaining data integrity and producing the expected 
                four different embedding sets in the final output.
                
                This test validates that our Consul-based service discovery and configuration management 
                correctly handles multiple instances of the same service type with different configurations.
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
            .verify(Duration.ofSeconds(30));
        
        // Get the final processed document
        // In a real test, we would retrieve this from the pipeline's output
        // For now, we verify the pipeline completed successfully
        
        logger.info("Multi-processor pipeline test completed successfully");
    }

    private void registerModulesWithEngine() {
        // Create registration service
        RegistrationService registrationService = new RegistrationService();
        
        // Engine endpoint
        String engineEndpoint = "localhost:" + engineGrpcPort;
        logger.info("Registering modules with engine at {}", engineEndpoint);
        
        // Get container host/port information from test resources
        String tikaHost = applicationContext.getProperty("tika-parser.host", String.class).orElse("localhost");
        Integer tikaPort = applicationContext.getProperty("tika-parser.grpc.port", Integer.class).orElse(50053);
        registrationService.registerModule(tikaHost, tikaPort, engineEndpoint, "tika-parser-1", "GRPC", "grpc.health.v1.Health/Check", "1.0.0");
        
        String chunkerSmallHost = applicationContext.getProperty("chunker-small.host", String.class).orElse("localhost");
        Integer chunkerSmallPort = applicationContext.getProperty("chunker-small.grpc.port", Integer.class).orElse(50054);
        registrationService.registerModule(chunkerSmallHost, chunkerSmallPort, engineEndpoint, "chunker-small-1", "GRPC", "grpc.health.v1.Health/Check", "1.0.0");
        
        String chunkerLargeHost = applicationContext.getProperty("chunker-large.host", String.class).orElse("localhost");
        Integer chunkerLargePort = applicationContext.getProperty("chunker-large.grpc.port", Integer.class).orElse(50055);
        registrationService.registerModule(chunkerLargeHost, chunkerLargePort, engineEndpoint, "chunker-large-1", "GRPC", "grpc.health.v1.Health/Check", "1.0.0");
        
        String embedderMinilmHost = applicationContext.getProperty("embedder-minilm.host", String.class).orElse("localhost");
        Integer embedderMinilmPort = applicationContext.getProperty("embedder-minilm.grpc.port", Integer.class).orElse(50056);
        registrationService.registerModule(embedderMinilmHost, embedderMinilmPort, engineEndpoint, "embedder-minilm-1", "GRPC", "grpc.health.v1.Health/Check", "1.0.0");
        
        String embedderMultilingualHost = applicationContext.getProperty("embedder-multilingual.host", String.class).orElse("localhost");
        Integer embedderMultilingualPort = applicationContext.getProperty("embedder-multilingual.grpc.port", Integer.class).orElse(50057);
        registrationService.registerModule(embedderMultilingualHost, embedderMultilingualPort, engineEndpoint, "embedder-multilingual-1", "GRPC", "grpc.health.v1.Health/Check", "1.0.0");
        
        logger.info("All modules registered with engine successfully");
    }

    private void setupPipelineConfiguration() throws Exception {
        // Register schemas
        registerSchemasInConsul();
        
        // Create pipeline steps
        var tikaStep = createTikaStep();
        var chunkerSmallStep = createChunkerStep("chunker-small", 300, 30);
        var chunkerLargeStep = createChunkerStep("chunker-large", 1000, 200);
        var embedderMinilmStep = createEmbedderStep("embedder-minilm", "ALL_MINILM_L6_V2");
        var embedderMultilingualStep = createEmbedderStep("embedder-multilingual", "PARAPHRASE_MULTILINGUAL_MINILM_L12_V2");
        
        // Create pipeline that splits after tika to both chunkers
        // Each chunker then goes to both embedders
        var pipelineSteps = Map.of(
            "tika-parser", tikaStep,
            "chunker-small", chunkerSmallStep,
            "chunker-large", chunkerLargeStep,
            "embedder-minilm", embedderMinilmStep,
            "embedder-multilingual", embedderMultilingualStep
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
        var modules = Map.of(
            "tika-parser", createModuleConfig("tika-parser", "tika-parser-schema"),
            "chunker-small", createModuleConfig("chunker-small", "chunker-schema"),
            "chunker-large", createModuleConfig("chunker-large", "chunker-schema"),
            "embedder-minilm", createModuleConfig("embedder-minilm", "embedder-schema"),
            "embedder-multilingual", createModuleConfig("embedder-multilingual", "embedder-schema")
        );
        
        var moduleMap = new PipelineModuleMap(modules);
        
        // Create cluster config
        var clusterConfig = new PipelineClusterConfig(
            clusterName,
            pipelineGraphConfig,
            moduleMap,
            "multi-processor-pipeline",
            Set.of(),
            Set.of("tika-parser", "chunker-small", "chunker-large", "embedder-minilm", "embedder-multilingual")
        );
        
        // Store in Consul
        String key = String.format("configs/%s", clusterName);
        String configJson = objectMapper.writeValueAsString(clusterConfig);
        consulClient.setKVValue(key, configJson);
        
        // Initialize config manager
        configManager.initialize(clusterName);
        
        // Wait for configuration to be loaded
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                var config = configManager.getCurrentPipelineClusterConfig();
                assertThat(config).isPresent();
                assertThat(config.get().clusterName()).isEqualTo(clusterName);
            });
        
        logger.info("Pipeline configuration setup complete");
    }

    private void registerSchemasInConsul() throws Exception {
        // Create schemas for each service type
        var tikaSchema = createTikaSchema();
        var chunkerSchema = createChunkerSchema();
        var embedderSchema = createEmbedderSchema();
        
        // Store schemas
        storeSchema("tika-parser-schema", 1, tikaSchema);
        storeSchema("chunker-schema", 1, chunkerSchema);
        storeSchema("embedder-schema", 1, embedderSchema);
        
        logger.info("Schemas registered in Consul");
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
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        ObjectNode embeddingModels = objectMapper.createObjectNode();
        embeddingModels.put("type", "array");
        ObjectNode items = objectMapper.createObjectNode();
        items.put("type", "object");
        ObjectNode itemProps = objectMapper.createObjectNode();
        itemProps.set("name", objectMapper.createObjectNode().put("type", "string"));
        itemProps.set("dimension", objectMapper.createObjectNode().put("type", "integer"));
        items.set("properties", itemProps);
        embeddingModels.set("items", items);
        props.set("embeddingModels", embeddingModels);
        props.set("checkChunks", objectMapper.createObjectNode().put("type", "boolean"));
        props.set("checkDocumentFields", objectMapper.createObjectNode().put("type", "boolean"));
        schema.set("properties", props);
        return schema;
    }

    private void storeSchema(String subject, int version, ObjectNode schema) throws Exception {
        var schemaData = new com.krickert.search.config.schema.model.SchemaVersionData(
            (long) subject.hashCode(), // globalId
            subject,
            version,
            schema.toString(),
            com.krickert.search.config.schema.model.SchemaType.JSON_SCHEMA,
            com.krickert.search.config.schema.model.SchemaCompatibility.NONE,
            java.time.Instant.now(),
            "Test schema for " + subject
        );
        
        String key = String.format("schema-versions/%s/%d", subject, version);
        consulClient.setKVValue(key, objectMapper.writeValueAsString(schemaData));
    }

    private PipelineStepConfig createTikaStep() {
        ObjectNode config = objectMapper.createObjectNode();
        ObjectNode parsingOptions = objectMapper.createObjectNode();
        parsingOptions.put("maxContentLength", -1);
        parsingOptions.put("extractMetadata", true);
        config.set("parsingOptions", parsingOptions);
        
        // Tika splits output to both chunkers
        Map<String, PipelineStepConfig.OutputTarget> outputs = Map.of(
            "small-chunks", new PipelineStepConfig.OutputTarget(
                "chunker-small", 
                TransportType.GRPC,
                new GrpcTransportConfig("chunker-small", Map.of()),
                null),
            "large-chunks", new PipelineStepConfig.OutputTarget(
                "chunker-large", 
                TransportType.GRPC,
                new GrpcTransportConfig("chunker-large", Map.of()),
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
        
        // Each chunker splits output to both embedders
        Map<String, PipelineStepConfig.OutputTarget> outputs = Map.of(
            "minilm", new PipelineStepConfig.OutputTarget(
                "embedder-minilm", 
                TransportType.GRPC,
                new GrpcTransportConfig("embedder-minilm", Map.of()),
                null),
            "multilingual", new PipelineStepConfig.OutputTarget(
                "embedder-multilingual", 
                TransportType.GRPC,
                new GrpcTransportConfig("embedder-multilingual", Map.of()),
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

    private PipelineStepConfig createEmbedderStep(String name, String model) {
        ObjectNode config = objectMapper.createObjectNode();
        var models = config.putArray("embeddingModels");
        var modelObj = models.addObject();
        modelObj.put("name", model);
        config.put("checkChunks", true);
        config.put("checkDocumentFields", false);
        
        return new PipelineStepConfig(
            name,
            StepType.SINK,
            "Generate embeddings with " + model,
            null,
            new PipelineStepConfig.JsonConfigOptions(config, Map.of()),
            null,
            Map.of(), // No outputs - this is a sink
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo(name, null)
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
}