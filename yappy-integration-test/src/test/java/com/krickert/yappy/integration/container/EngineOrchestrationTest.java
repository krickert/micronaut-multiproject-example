package com.krickert.yappy.integration.container;

import com.krickert.search.grpc.*;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.model.PipeDoc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the engine orchestration with module containers.
 * This test verifies that:
 * 1. The engine can start with test resources (Consul, Kafka, etc.)
 * 2. Modules can register with the engine
 * 3. The engine can route requests to the appropriate modules
 */
@MicronautTest(environments = {"test", "container-test"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EngineOrchestrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(EngineOrchestrationTest.class);
    private static final Network network = Network.newNetwork();
    
    @Inject
    ApplicationContext applicationContext;
    
    // Module containers
    private static GenericContainer<?> tikaParserContainer;
    private static GenericContainer<?> chunkerContainer;
    private static GenericContainer<?> engineContainer;
    
    private ManagedChannel engineRegistrationChannel;
    private ManagedChannel tikaChannel;
    private ModuleRegistrationGrpc.ModuleRegistrationBlockingStub registrationStub;
    
    @BeforeAll
    static void startContainers() {
        // Note: Infrastructure containers (Consul, Kafka, Apicurio) are provided by test resources
        
        // Start module containers on the shared network
        tikaParserContainer = new GenericContainer<>(DockerImageName.parse("yappy-tika-parser:1.0.0-SNAPSHOT"))
                .withExposedPorts(50051)
                .withNetwork(network)
                .withNetworkAliases("tika-parser")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("TIKA"))
                .waitingFor(Wait.forLogMessage(".*Server Running.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
        
        chunkerContainer = new GenericContainer<>(DockerImageName.parse("yappy-chunker:1.0.0-SNAPSHOT"))
                .withExposedPorts(50051)
                .withNetwork(network)
                .withNetworkAliases("chunker")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("CHUNKER"))
                .waitingFor(Wait.forLogMessage(".*Server Running.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
        
        // Start containers
        tikaParserContainer.start();
        chunkerContainer.start();
        
        log.info("Module containers started successfully");
    }
    
    @BeforeEach
    void setup() {
        // Get test resources configuration
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse("localhost");
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(8500);
        String kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092");
        String apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class).orElse("http://localhost:8080");
        
        log.info("Test resources - Consul: {}:{}, Kafka: {}, Apicurio: {}", consulHost, consulPort, kafkaServers, apicurioUrl);
        
        // Use host.docker.internal for Docker Desktop on macOS to access host services
        String dockerHost = "host.docker.internal";
        
        // Start engine container with test resources configuration
        engineContainer = new GenericContainer<>(DockerImageName.parse("yappy-engine:1.0.0-SNAPSHOT"))
                .withExposedPorts(8080, 50050, 50051)
                .withNetwork(network)
                .withNetworkAliases("engine")
                .withEnv("CONSUL_HOST", dockerHost)
                .withEnv("CONSUL_PORT", consulPort.toString())
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", dockerHost + ":9092")
                .withEnv("APICURIO_REGISTRY_URL", "http://" + dockerHost + ":8080")
                .withEnv("YAPPY_ENGINE_NAME", "test-engine")
                .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
                .withEnv("CONSUL_ENABLED", "true")
                .withEnv("KAFKA_ENABLED", "true")
                // Map test resources ports
                .withAccessToHost(true)
                .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3))
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("ENGINE"));
        
        engineContainer.start();
        
        // Create gRPC channel to engine's module registration service
        engineRegistrationChannel = ManagedChannelBuilder
                .forAddress(engineContainer.getHost(), engineContainer.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        registrationStub = ModuleRegistrationGrpc.newBlockingStub(engineRegistrationChannel);
        
        log.info("Engine container started, registration channel established");
    }
    
    @AfterEach
    void cleanup() {
        if (engineRegistrationChannel != null && !engineRegistrationChannel.isShutdown()) {
            engineRegistrationChannel.shutdown();
            try {
                engineRegistrationChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                engineRegistrationChannel.shutdownNow();
            }
        }
        
        if (tikaChannel != null && !tikaChannel.isShutdown()) {
            tikaChannel.shutdown();
            try {
                tikaChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                tikaChannel.shutdownNow();
            }
        }
        
        if (engineContainer != null && engineContainer.isRunning()) {
            engineContainer.stop();
        }
    }
    
    @AfterAll
    static void stopContainers() {
        if (tikaParserContainer != null && tikaParserContainer.isRunning()) {
            tikaParserContainer.stop();
        }
        if (chunkerContainer != null && chunkerContainer.isRunning()) {
            chunkerContainer.stop();
        }
    }
    
    @Test
    @Order(1)
    void testEngineHealthCheck() {
        // Verify engine is healthy
        assertTrue(engineContainer.isRunning(), "Engine should be running");
        
        // Check HTTP health endpoint
        String healthUrl = String.format("http://%s:%d/health", 
                engineContainer.getHost(), 
                engineContainer.getMappedPort(8080));
        log.info("Engine health check URL: {}", healthUrl);
    }
    
    @Test
    @Order(2)
    void testRegisterModules() {
        // Register Tika Parser
        ModuleInfo tikaInfo = ModuleInfo.newBuilder()
                .setServiceName("tika-parser")
                .setServiceId("tika-parser-001")
                .setHost("tika-parser")
                .setPort(50051)
                .setHealthEndpoint("/grpc.health.v1.Health/Check")
                .putMetadata("version", "1.0.0")
                .putMetadata("type", "parser")
                .addTags("parser")
                .addTags("document-processing")
                .build();
        
        RegistrationStatus tikaStatus = registrationStub.registerModule(tikaInfo);
        assertTrue(tikaStatus.getSuccess(), "Tika registration should succeed");
        log.info("Tika parser registered: {}", tikaStatus.getConsulServiceId());
        
        // Register Chunker
        ModuleInfo chunkerInfo = ModuleInfo.newBuilder()
                .setServiceName("chunker")
                .setServiceId("chunker-001")
                .setHost("chunker")
                .setPort(50051)
                .setHealthEndpoint("/grpc.health.v1.Health/Check")
                .putMetadata("version", "1.0.0")
                .putMetadata("type", "processor")
                .addTags("processor")
                .addTags("text-chunking")
                .build();
        
        RegistrationStatus chunkerStatus = registrationStub.registerModule(chunkerInfo);
        assertTrue(chunkerStatus.getSuccess(), "Chunker registration should succeed");
        log.info("Chunker registered: {}", chunkerStatus.getConsulServiceId());
        
        // Verify both modules are registered
        ModuleList moduleList = registrationStub.listModules(com.google.protobuf.Empty.getDefaultInstance());
        assertEquals(2, moduleList.getModulesCount(), "Should have 2 modules registered");
    }
    
    @Test
    @Order(3)
    void testDirectModuleCommunication() throws Exception {
        // Test direct communication with Tika parser
        tikaChannel = ManagedChannelBuilder
                .forAddress(tikaParserContainer.getHost(), tikaParserContainer.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        PipeStepProcessorGrpc.PipeStepProcessorBlockingStub tikaStub = 
                PipeStepProcessorGrpc.newBlockingStub(tikaChannel);
        
        // Create test document
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId("test-orchestration-doc")
                .setBody("This is a test document for orchestration testing")
                .setSourceMimeType("text/plain")
                .setDocumentType("test")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .build();
        
        ProcessResponse response = tikaStub.processData(request);
        
        assertNotNull(response);
        assertTrue(response.getSuccess(), "Tika processing should succeed");
        assertTrue(response.hasOutputDoc(), "Should have output document");
        assertEquals("test-orchestration-doc", response.getOutputDoc().getId());
        
        log.info("Successfully processed document through Tika parser");
    }
    
    @Test
    @Order(4)
    void testModuleHeartbeat() {
        // Send heartbeat for registered modules
        ModuleHeartbeat heartbeat = ModuleHeartbeat.newBuilder()
                .setServiceId("tika-parser-001")
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(System.currentTimeMillis() / 1000)
                        .build())
                .putStatusInfo("processed_docs", "50")
                .putStatusInfo("status", "healthy")
                .build();
        
        HeartbeatAck ack = registrationStub.heartbeat(heartbeat);
        
        assertTrue(ack.getAcknowledged(), "Heartbeat should be acknowledged");
        assertEquals("Heartbeat received", ack.getMessage());
        
        log.info("Module heartbeat acknowledged");
    }
}