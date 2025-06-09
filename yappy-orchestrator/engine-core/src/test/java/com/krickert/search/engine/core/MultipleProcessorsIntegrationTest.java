package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
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
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.io.File;
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
@MicronautTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
public class MultipleProcessorsIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultipleProcessorsIntegrationTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Container
    private static final DockerComposeContainer<?> environment = 
        new DockerComposeContainer<>(new File("docker-compose-integration-test.yml"))
            .withExposedService("consul", 8500, Wait.forHealthcheck())
            .withExposedService("tika-parser", 50053, Wait.forHealthcheck())
            .withExposedService("chunker-small", 50054, Wait.forHealthcheck())
            .withExposedService("chunker-large", 50055, Wait.forHealthcheck())
            .withExposedService("embedder-minilm", 50056, Wait.forHealthcheck())
            .withExposedService("embedder-multilingual", 50057, Wait.forHealthcheck())
            .withLocalCompose(true)
            .withOptions("--compatibility");

    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    private ConsulClient consulClient;
    private String testClusterName;
    private PipelineEngineImpl pipelineEngine;

    @BeforeAll
    void setup() throws Exception {
        // Wait for all services to be healthy
        Thread.sleep(10000); // Give services time to start
        
        // Get Consul connection details
        String consulHost = environment.getServiceHost("consul", 8500);
        Integer consulPort = environment.getServicePort("consul", 8500);
        consulClient = new ConsulClient(consulHost, consulPort);
        
        // Create test cluster
        testClusterName = TestClusterHelper.createTestCluster("multi-processor-test");
        
        // Wait for services to register themselves in Consul
        waitForServicesToRegister();
        
        // Setup pipeline configuration in Consul
        setupPipelineConfiguration();
        
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
        
        if (consulClient != null && testClusterName != null) {
            TestClusterHelper.cleanupTestCluster(consulClient, testClusterName);
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

    private void waitForServicesToRegister() {
        await().atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                var services = consulClient.getAgentServices().getValue();
                
                // Check for all required services
                assertThat(services.values().stream()
                    .anyMatch(s -> s.getService().equals("tika-parser")))
                    .isTrue();
                assertThat(services.values().stream()
                    .anyMatch(s -> s.getService().equals("chunker-small")))
                    .isTrue();
                assertThat(services.values().stream()
                    .anyMatch(s -> s.getService().equals("chunker-large")))
                    .isTrue();
                assertThat(services.values().stream()
                    .anyMatch(s -> s.getService().equals("embedder-minilm")))
                    .isTrue();
                assertThat(services.values().stream()
                    .anyMatch(s -> s.getService().equals("embedder-multilingual")))
                    .isTrue();
            });
        
        logger.info("All services registered in Consul");
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
            testClusterName,
            pipelineGraphConfig,
            moduleMap,
            "multi-processor-pipeline",
            Set.of(),
            Set.of("tika-parser", "chunker-small", "chunker-large", "embedder-minilm", "embedder-multilingual")
        );
        
        // Store in Consul
        String key = String.format("configs/%s", testClusterName);
        String configJson = objectMapper.writeValueAsString(clusterConfig);
        consulClient.setKVValue(key, configJson);
        
        // Initialize config manager
        configManager.initialize(testClusterName);
        
        // Wait for configuration to be loaded
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                var config = configManager.getCurrentPipelineClusterConfig();
                assertThat(config).isPresent();
                assertThat(config.get().clusterName()).isEqualTo(testClusterName);
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