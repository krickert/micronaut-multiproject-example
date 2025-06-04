package com.krickert.yappy.integration;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.grpc.PipeStreamEngineGrpc;
import com.krickert.search.grpc.PipeStepProcessorGrpc;
import com.krickert.search.model.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration test for containerized engines with modules.
 * Tests the supervisor-based multi-process container approach.
 */
@MicronautTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EngineContainerTest implements TestPropertyProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineContainerTest.class);
    
    private static final Network NETWORK = Network.newNetwork();
    
    // Engine + Tika container with incremental ports
    @Container
    private static final GenericContainer<?> ENGINE_TIKA = new GenericContainer<>("localhost:5000/yappy/engine-tika-parser:latest")
            .withNetwork(NETWORK)
            .withNetworkAliases("engine-tika")
            .withExposedPorts(8080, 50051, 50052)  // HTTP, Engine gRPC, Tika gRPC
            .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
            .withEnv("CONSUL_HOST", "consul-testresources")
            .withEnv("CONSUL_PORT", "8500")
            .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka-testresources:9092")
            .withEnv("APICURIO_REGISTRY_URL", "http://apicurio-testresources:8080")
            .withEnv("YAPPY_ENGINE_NAME", "engine-tika-test")
            .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));
    
    @Inject
    ConsulBusinessOperationsService consulService;
    
    private ManagedChannel engineChannel;
    private ManagedChannel tikaChannel;
    private PipeStreamEngineGrpc.PipeStreamEngineBlockingStub engineStub;
    private PipeStepProcessorGrpc.PipeStepProcessorBlockingStub tikaStub;
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "consul.client.host", "localhost",
                "consul.client.port", String.valueOf(getConsulPort()),
                "kafka.bootstrap.servers", "localhost:" + getKafkaPort()
        );
    }
    
    @BeforeAll
    void setup() {
        // Create gRPC channels with proper port mapping
        engineChannel = ManagedChannelBuilder
                .forAddress("localhost", ENGINE_TIKA.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        tikaChannel = ManagedChannelBuilder
                .forAddress("localhost", ENGINE_TIKA.getMappedPort(50052))
                .usePlaintext()
                .build();
        
        engineStub = PipeStreamEngineGrpc.newBlockingStub(engineChannel);
        tikaStub = PipeStepProcessorGrpc.newBlockingStub(tikaChannel);
    }
    
    @AfterAll
    void tearDown() {
        if (engineChannel != null) {
            engineChannel.shutdown();
        }
        if (tikaChannel != null) {
            tikaChannel.shutdown();
        }
    }
    
    @Test
    @DisplayName("Container should start with both engine and module running")
    void testContainerStartup() {
        // Check HTTP health
        assertThat(ENGINE_TIKA.isRunning()).isTrue();
        
        // Log the port mappings for debugging
        LOG.info("Engine HTTP port: {}", ENGINE_TIKA.getMappedPort(8080));
        LOG.info("Engine gRPC port: {}", ENGINE_TIKA.getMappedPort(50051));
        LOG.info("Tika gRPC port: {}", ENGINE_TIKA.getMappedPort(50052));
        
        // Verify both processes are running inside the container
        var processes = ENGINE_TIKA.execInContainer("ps", "aux").getStdout();
        assertThat(processes).contains("supervisord");
        assertThat(processes).contains("engine.jar");
        assertThat(processes).contains("tika-parser.jar");
    }
    
    @Test
    @DisplayName("Engine should register with Consul and report co-located module")
    void testConsulRegistration() throws Exception {
        // Wait for registration
        await().atMost(30, SECONDS)
                .pollInterval(1, SECONDS)
                .untilAsserted(() -> {
                    var services = consulService.getAllServicesFromCatalog();
                    assertThat(services).containsKey("engine-tika-test");
                    assertThat(services).containsKey("tika-parser");
                });
        
        // Check engine service details
        var engineServices = consulService.getHealthyServiceInstances("engine-tika-test");
        assertThat(engineServices).hasSize(1);
        
        var engineService = engineServices.get(0);
        assertThat(engineService.getTags()).contains("yappy-engine-module=tika-parser");
        
        // Check module service registration
        var tikaServices = consulService.getHealthyServiceInstances("tika-parser");
        assertThat(tikaServices).isNotEmpty();
    }
    
    @Test
    @DisplayName("Should seed and process document through containerized pipeline")
    void testPipelineProcessing() throws Exception {
        // Create a simple pipeline configuration
        PipelineStepConfig tikaStep = PipelineStepConfig.builder()
                .stepName("tika-parser")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("tika-parser")
                        .build())
                .outputs(Map.of("parsed", PipelineStepConfig.OutputTarget.builder()
                        .targetStepName("output")
                        .transportType(TransportType.KAFKA)
                        .kafkaTransport(KafkaTransportConfig.builder()
                                .topic("test.parsed.documents")
                                .build())
                        .build()))
                .build();
        
        PipelineStepConfig outputStep = PipelineStepConfig.builder()
                .stepName("output")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        PipelineConfig pipelineConfig = new PipelineConfig(
                "test-pipeline",
                Map.of("tika-parser", tikaStep, "output", outputStep)
        );
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("test-pipeline", pipelineConfig))
                .build();
        
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(
                "tika-parser", new PipelineModuleConfiguration(
                        "Tika Parser Module",
                        "tika-parser",
                        null,
                        null
                ),
                "dummy-sink", new PipelineModuleConfiguration(
                        "Dummy Sink",
                        "dummy-sink",
                        null,
                        null
                )
        ));
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of("test.parsed.documents"))
                .allowedGrpcServices(Set.of("tika-parser", "dummy-sink"))
                .build();
        
        // Store configuration
        consulService.seedClusterConfiguration("test-cluster", clusterConfig);
        
        // Wait for engine to load configuration
        Thread.sleep(2000);
        
        // Create and send a test document
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-" + UUID.randomUUID())
                .setTitle("Test Document")
                .setBody("This is a test document for containerized processing.")
                .putMetadata("source", "integration-test")
                .build();
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId("test-stream-" + UUID.randomUUID())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("tika-parser")
                .setCurrentHopNumber(0)
                .setDocument(document)
                .build();
        
        // Process through engine
        var response = engineStub.processPipeAsync(pipeStream);
        assertThat(response).isNotNull();
        
        LOG.info("Successfully processed document through containerized pipeline");
    }
    
    @Test
    @DisplayName("Should process document directly through Tika module on port 50052")
    void testDirectModuleProcessing() {
        // Create a test document
        PipeDoc document = PipeDoc.newBuilder()
                .setId("direct-test-" + UUID.randomUUID())
                .setTitle("Direct Tika Test")
                .setBody("Testing direct module communication on incremental port.")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setStreamId("direct-stream-" + UUID.randomUUID())
                .setPipelineName("test-pipeline")
                .setStepName("tika-parser")
                .setDocument(document)
                .build();
        
        // Process directly through Tika module
        ProcessResponse response = tikaStub.processData(request);
        
        assertThat(response).isNotNull();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.hasDocument()).isTrue();
        
        // Verify Tika added metadata
        PipeDoc processedDoc = response.getDocument();
        assertThat(processedDoc.getMetadataMap()).containsKey("tika_parsed");
        
        LOG.info("Successfully processed document directly through Tika module on port 50052");
    }
    
    @Test
    @DisplayName("Supervisor should restart failed processes")
    void testProcessResilience() throws Exception {
        // Kill the tika process inside the container
        ENGINE_TIKA.execInContainer("pkill", "-f", "tika-parser.jar");
        
        // Wait a bit for supervisor to restart it
        Thread.sleep(5000);
        
        // Verify the process is running again
        var processes = ENGINE_TIKA.execInContainer("ps", "aux").getStdout();
        assertThat(processes).contains("tika-parser.jar");
        
        // Verify we can still communicate with it
        var healthCheck = tikaStub.withDeadlineAfter(5, SECONDS)
                .processData(ProcessRequest.newBuilder()
                        .setStreamId("health-check")
                        .setPipelineName("test")
                        .setStepName("test")
                        .setDocument(PipeDoc.newBuilder().setId("health").build())
                        .build());
        
        assertThat(healthCheck).isNotNull();
    }
    
    // Helper methods for test resources
    private Integer getConsulPort() {
        return Integer.parseInt(System.getProperty("consul.port", "8500"));
    }
    
    private Integer getKafkaPort() {
        return Integer.parseInt(System.getProperty("kafka.port", "9092"));
    }
}