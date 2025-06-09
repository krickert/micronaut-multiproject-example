package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
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
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Simplified integration test using individual testcontainers.
 */
@MicronautTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
public class SimpleMultiModuleTest implements TestPropertyProvider {

    private static final Logger logger = LoggerFactory.getLogger(SimpleMultiModuleTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Container
    static GenericContainer<?> tikaContainer = new GenericContainer<>("tika-parser:latest")
        .withExposedPorts(50053, 8080)
        .waitingFor(Wait.forListeningPort());

    @Container
    static GenericContainer<?> chunkerContainer = new GenericContainer<>("chunker:latest")
        .withExposedPorts(50054, 8080)
        .withEnv("CHUNKER_DEFAULT_SIZE", "500")
        .withEnv("CHUNKER_DEFAULT_OVERLAP", "50")
        .waitingFor(Wait.forListeningPort());

    @Container
    static GenericContainer<?> embedderContainer = new GenericContainer<>("embedder:latest")
        .withExposedPorts(50051, 8080)
        .withEnv("VECTORIZER_DEFAULT_MODEL", "ALL_MINILM_L6_V2")
        .waitingFor(Wait.forListeningPort()
            .withStartupTimeout(Duration.ofMinutes(3))); // Embedder may take longer

    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    ConsulBusinessOperationsService businessOpsService;
    
    @Inject
    ConsulClient consulClient;
    
    private String testClusterName;
    private PipelineEngineImpl pipelineEngine;

    @BeforeAll
    void setup() throws Exception {
        // Create test cluster
        testClusterName = TestClusterHelper.createTestCluster("simple-multi-test");
        
        // Create cluster with metadata
        Map<String, String> clusterMetadata = Map.of(
            "testType", "simple-multi-module",
            "createdBy", "SimpleMultiModuleTest"
        );
        businessOpsService.createCluster(testClusterName, clusterMetadata)
            .block(Duration.ofSeconds(5));
        
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
            List.of("tika-parser-1", "chunker-1", "embedder-1")
        ).block(Duration.ofSeconds(10));
    }

    @Test
    void testSimplePipelineWithRealContainers() {
        // Create a test document
        PipeDoc document = PipeDoc.newBuilder()
            .setId("simple-test-doc-001")
            .setTitle("Simple Multi-Module Test")
            .setBody("""
                This is a test document for the simple multi-module integration test.
                It will be processed by real Docker containers:
                1. Tika parser will extract the text
                2. Chunker will split it into chunks
                3. Embedder will generate embeddings
                
                This validates that our pipeline can work with real module implementations.
                """)
            .setSourceMimeType("text/plain")
            .setSourceUri("test://documents/simple-test-001.txt")
            .setCreationDate(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build())
            .build();
        
        // Create PipeStream
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("stream-simple-" + System.currentTimeMillis())
            .setDocument(document)
            .setCurrentPipelineName("simple-pipeline")
            .setCurrentHopNumber(0)
            .build();
        
        // Process through pipeline
        StepVerifier.create(pipelineEngine.processMessage(pipeStream))
            .expectComplete()
            .verify(Duration.ofSeconds(30));
        
        logger.info("Simple pipeline test with real containers completed successfully");
    }

    private Mono<Void> registerServices() {
        // Get container connection info
        String tikaHost = tikaContainer.getHost();
        Integer tikaPort = tikaContainer.getMappedPort(50053);
        
        String chunkerHost = chunkerContainer.getHost();
        Integer chunkerPort = chunkerContainer.getMappedPort(50054);
        
        String embedderHost = embedderContainer.getHost();
        Integer embedderPort = embedderContainer.getMappedPort(50051);
        
        // Create registration objects
        Registration tikaReg = ImmutableRegistration.builder()
            .id("tika-parser-1")
            .name("tika-parser")
            .address(tikaHost)
            .port(tikaPort)
            .tags(List.of("grpc", testClusterName))
            .build();
            
        Registration chunkerReg = ImmutableRegistration.builder()
            .id("chunker-1")
            .name("chunker")
            .address(chunkerHost)
            .port(chunkerPort)
            .tags(List.of("grpc", testClusterName))
            .build();
            
        Registration embedderReg = ImmutableRegistration.builder()
            .id("embedder-1")
            .name("embedder")
            .address(embedderHost)
            .port(embedderPort)
            .tags(List.of("grpc", testClusterName))
            .build();
        
        // Register all services
        return Flux.merge(
            businessOpsService.registerService(tikaReg),
            businessOpsService.registerService(chunkerReg),
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
        var chunkerStep = createChunkerStep();
        var embedderStep = createEmbedderStep();
        
        // Create simple pipeline: tika -> chunker -> embedder
        var pipelineSteps = Map.of(
            "tika-parser", tikaStep,
            "chunker", chunkerStep,
            "embedder", embedderStep
        );
        
        var pipeline = new PipelineConfig(
            "simple-pipeline",
            pipelineSteps
        );
        
        // Create pipeline graph config
        var pipelineGraphConfig = new PipelineGraphConfig(
            Map.of("simple-pipeline", pipeline)
        );
        
        // Create module configurations
        var modules = Map.of(
            "tika-parser", createModuleConfig("tika-parser", "tika-parser-schema"),
            "chunker", createModuleConfig("chunker", "chunker-schema"),
            "embedder", createModuleConfig("embedder", "embedder-schema")
        );
        
        var moduleMap = new PipelineModuleMap(modules);
        
        // Create cluster config
        var clusterConfig = new PipelineClusterConfig(
            testClusterName,
            pipelineGraphConfig,
            moduleMap,
            "simple-pipeline",
            Set.of(),
            Set.of("tika-parser", "chunker", "embedder")
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
            .add("PARAPHRASE_MULTILINGUAL_MINILM_L12_V2"));
        embeddingModels.set("items", modelItems);
        embeddingModels.set("default", objectMapper.createArrayNode().add("ALL_MINILM_L6_V2"));
        props.set("embedding_models", embeddingModels);
        
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
        
        Map<String, PipelineStepConfig.OutputTarget> outputs = Map.of(
            "default", new PipelineStepConfig.OutputTarget(
                "chunker", 
                TransportType.GRPC,
                new GrpcTransportConfig("chunker", Map.of()),
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

    private PipelineStepConfig createChunkerStep() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("source_field", "body");
        config.put("chunk_size", 500);
        config.put("chunk_overlap", 50);
        config.put("chunk_config_id", "default");
        
        Map<String, PipelineStepConfig.OutputTarget> outputs = Map.of(
            "default", new PipelineStepConfig.OutputTarget(
                "embedder", 
                TransportType.GRPC,
                new GrpcTransportConfig("embedder", Map.of()),
                null)
        );
        
        return new PipelineStepConfig(
            "chunker",
            StepType.PIPELINE,
            "Chunk text into smaller pieces",
            null,
            new PipelineStepConfig.JsonConfigOptions(config, Map.of()),
            null,
            outputs,
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("chunker", null)
        );
    }

    private PipelineStepConfig createEmbedderStep() {
        ObjectNode config = objectMapper.createObjectNode();
        var models = config.putArray("embedding_models");
        models.add("ALL_MINILM_L6_V2");
        config.put("check_chunks", true);
        config.put("check_document_fields", false);
        
        return new PipelineStepConfig(
            "embedder",
            StepType.SINK,
            "Generate embeddings",
            null,
            new PipelineStepConfig.JsonConfigOptions(config, Map.of()),
            null,
            Map.of(), // No outputs - this is a sink
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("embedder", null)
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
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
            "micronaut.test.resources.scope", "shared",
            "consul.client.host", "localhost",
            "consul.client.port", "${consul.port}",
            "kafka.bootstrap.servers", "${kafka.bootstrap.servers}",
            "apicurio.registry.url", "${apicurio.registry.url}"
        );
    }
}