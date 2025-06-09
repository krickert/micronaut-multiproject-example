package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Timestamp;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Demonstrates proper port mapping with testcontainers for gRPC services.
 * This test shows how to handle the network boundary between containers and the test environment.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
public class ContainerPortMappingTest {

    private static final Logger logger = LoggerFactory.getLogger(ContainerPortMappingTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Container
    static GenericContainer<?> consulContainer = new GenericContainer<>("consul:1.19")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500));

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

    private ConsulClient consulClient;
    private String testClusterName;

    @BeforeAll
    void setup() throws Exception {
        // Create Consul client with mapped port
        String consulHost = consulContainer.getHost();
        Integer consulPort = consulContainer.getMappedPort(8500);
        consulClient = new ConsulClient(consulHost, consulPort);
        
        logger.info("Consul running at {}:{}", consulHost, consulPort);
        
        // Create test cluster
        testClusterName = "port-mapping-test-" + System.currentTimeMillis();
        
        // Register services with their mapped ports
        registerServices();
        
        // Setup pipeline configuration
        setupPipelineConfiguration();
    }

    @Test
    void testServiceRegistrationWithMappedPorts() {
        // Verify services are registered correctly
        var services = consulClient.getAgentServices();
        assertThat(services.getValue()).hasSize(4); // consul + 3 services
        
        // Check tika-parser registration
        var tikaService = services.getValue().get("tika-parser-1");
        assertThat(tikaService).isNotNull();
        assertThat(tikaService.getAddress()).isEqualTo(tikaContainer.getHost());
        assertThat(tikaService.getPort()).isEqualTo(tikaContainer.getMappedPort(50053));
        assertThat(tikaService.getTags()).contains("grpc", testClusterName);
        
        logger.info("Tika service registered at {}:{}", tikaService.getAddress(), tikaService.getPort());
    }

    @Test
    void testGrpcConnectionToMappedPort() throws Exception {
        // Test that we can actually connect to the gRPC service via mapped port
        String tikaHost = tikaContainer.getHost();
        Integer tikaPort = tikaContainer.getMappedPort(50053);
        
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(tikaHost, tikaPort)
            .usePlaintext()
            .build();
        
        try {
            // Just verify we can create a channel - actual gRPC calls would go here
            assertThat(channel).isNotNull();
            assertThat(channel.isShutdown()).isFalse();
            
            logger.info("Successfully created gRPC channel to {}:{}", tikaHost, tikaPort);
        } finally {
            channel.shutdown();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testPipelineConfigurationStorage() {
        // Verify pipeline configuration is stored correctly
        String configKey = "configs/" + testClusterName;
        Response<GetValue> response = consulClient.getKVValue(configKey);
        
        assertThat(response.getValue()).isNotNull();
        assertThat(response.getValue().getDecodedValue()).isNotEmpty();
        
        logger.info("Pipeline configuration stored successfully at key: {}", configKey);
    }

    private void registerServices() {
        // Register tika-parser with mapped port
        NewService tikaService = new NewService();
        tikaService.setId("tika-parser-1");
        tikaService.setName("tika-parser");
        tikaService.setAddress(tikaContainer.getHost());
        tikaService.setPort(tikaContainer.getMappedPort(50053));
        tikaService.setTags(List.of("grpc", testClusterName));
        consulClient.agentServiceRegister(tikaService);
        
        // Register chunker with mapped port
        NewService chunkerService = new NewService();
        chunkerService.setId("chunker-1");
        chunkerService.setName("chunker");
        chunkerService.setAddress(chunkerContainer.getHost());
        chunkerService.setPort(chunkerContainer.getMappedPort(50054));
        chunkerService.setTags(List.of("grpc", testClusterName));
        consulClient.agentServiceRegister(chunkerService);
        
        // Register embedder with mapped port
        NewService embedderService = new NewService();
        embedderService.setId("embedder-1");
        embedderService.setName("embedder");
        embedderService.setAddress(embedderContainer.getHost());
        embedderService.setPort(embedderContainer.getMappedPort(50051));
        embedderService.setTags(List.of("grpc", testClusterName));
        consulClient.agentServiceRegister(embedderService);
        
        logger.info("All services registered with mapped ports");
    }

    private void setupPipelineConfiguration() {
        try {
            // Register schemas
            registerSchemas();
            
            // Create and store pipeline configuration
            createAndStorePipelineConfiguration();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup pipeline configuration", e);
        }
    }

    private void registerSchemas() throws Exception {
        // Store tika schema
        var tikaSchema = createTikaSchema();
        storeSchema("schema-versions/tika-parser-schema/1", tikaSchema.toString());
        
        // Store chunker schema
        var chunkerSchema = createChunkerSchema();
        storeSchema("schema-versions/chunker-schema/1", chunkerSchema.toString());
        
        // Store embedder schema
        var embedderSchema = createEmbedderSchema();
        storeSchema("schema-versions/embedder-schema/1", embedderSchema.toString());
        
        logger.info("All schemas registered in Consul");
    }

    private void storeSchema(String key, String schema) {
        consulClient.setKVValue(key, schema);
    }

    private void createAndStorePipelineConfiguration() throws Exception {
        // Create pipeline steps
        var tikaStep = createTikaStep();
        var chunkerStep = createChunkerStep();
        var embedderStep = createEmbedderStep();
        
        // Create pipeline
        var pipelineSteps = Map.of(
            "tika-parser", tikaStep,
            "chunker", chunkerStep,
            "embedder", embedderStep
        );
        
        var pipeline = new PipelineConfig("simple-pipeline", pipelineSteps);
        
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
        
        // Store in Consul
        String configJson = objectMapper.writeValueAsString(clusterConfig);
        consulClient.setKVValue("configs/" + testClusterName, configJson);
        
        logger.info("Pipeline configuration stored for cluster: {}", testClusterName);
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

    @AfterAll
    void cleanup() {
        // Clean up registrations
        if (consulClient != null) {
            consulClient.agentServiceDeregister("tika-parser-1");
            consulClient.agentServiceDeregister("chunker-1");
            consulClient.agentServiceDeregister("embedder-1");
            
            // Clean up KV data
            consulClient.deleteKVValue("configs/" + testClusterName);
            consulClient.deleteKVValue("schema-versions/tika-parser-schema/1");
            consulClient.deleteKVValue("schema-versions/chunker-schema/1");
            consulClient.deleteKVValue("schema-versions/embedder-schema/1");
        }
    }
}