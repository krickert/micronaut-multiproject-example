package com.krickert.yappy.integration.container;

import com.krickert.search.grpc.*;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.model.PipeDoc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test demonstrating engine orchestration without external dependencies.
 * This test shows:
 * 1. Engine can run without Consul/Kafka
 * 2. Modules can register with the engine
 * 3. Basic module communication works
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SimpleEngineOrchestrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleEngineOrchestrationTest.class);
    private static final Network network = Network.newNetwork();
    
    @Container
    static GenericContainer<?> tikaParserContainer = new GenericContainer<>(DockerImageName.parse("yappy-tika-parser:1.0.0-SNAPSHOT"))
            .withExposedPorts(50051)
            .withNetwork(network)
            .withNetworkAliases("tika-parser")
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("TIKA"))
            .waitingFor(Wait.forLogMessage(".*Server Running.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));
    
    @Container
    static GenericContainer<?> engineContainer = new GenericContainer<>(DockerImageName.parse("yappy-engine:1.0.0-SNAPSHOT"))
            .withExposedPorts(8080, 50050, 50051)
            .withNetwork(network)
            .withNetworkAliases("engine")
            .withEnv("YAPPY_ENGINE_NAME", "test-engine")
            .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
            .withEnv("CONSUL_ENABLED", "false")
            .withEnv("KAFKA_ENABLED", "false")
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("ENGINE"))
            .waitingFor(Wait.forLogMessage(".*Server Running.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));
    
    private ManagedChannel engineRegistrationChannel;
    private ManagedChannel tikaChannel;
    private ModuleRegistrationGrpc.ModuleRegistrationBlockingStub registrationStub;
    
    @BeforeEach
    void setup() {
        // Create gRPC channel to engine's module registration service
        engineRegistrationChannel = ManagedChannelBuilder
                .forAddress(engineContainer.getHost(), engineContainer.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        registrationStub = ModuleRegistrationGrpc.newBlockingStub(engineRegistrationChannel);
        
        // Create channel to Tika parser
        tikaChannel = ManagedChannelBuilder
                .forAddress(tikaParserContainer.getHost(), tikaParserContainer.getMappedPort(50051))
                .usePlaintext()
                .build();
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
    }
    
    @Test
    @Order(1)
    void testEngineStartsWithoutConsul() {
        assertTrue(engineContainer.isRunning(), "Engine should be running");
        assertTrue(tikaParserContainer.isRunning(), "Tika parser should be running");
        log.info("Both containers started successfully without Consul");
    }
    
    @Test
    @Order(2)
    void testModuleRegistration() {
        // Register Tika Parser with the engine
        ModuleInfo tikaInfo = ModuleInfo.newBuilder()
                .setServiceName("tika-parser")
                .setServiceId("tika-parser-test-001")
                .setHost(tikaParserContainer.getNetworkAliases().stream().findFirst().orElse("tika-parser"))
                .setPort(50051)
                .setHealthEndpoint("/grpc.health.v1.Health/Check")
                .putMetadata("version", "1.0.0")
                .putMetadata("type", "parser")
                .addTags("parser")
                .addTags("document-processing")
                .build();
        
        RegistrationStatus status = registrationStub.registerModule(tikaInfo);
        
        assertTrue(status.getSuccess(), "Module registration should succeed");
        assertNotNull(status.getConsulServiceId(), "Should have a service ID");
        log.info("Tika parser registered successfully: {}", status.getConsulServiceId());
        
        // Verify module is in the list
        ModuleList moduleList = registrationStub.listModules(com.google.protobuf.Empty.getDefaultInstance());
        assertEquals(1, moduleList.getModulesCount(), "Should have 1 module registered");
        assertEquals("tika-parser", moduleList.getModules(0).getServiceName());
    }
    
    @Test
    @Order(3)
    void testDirectModuleCommunication() {
        // Test direct communication with Tika parser
        PipeStepProcessorGrpc.PipeStepProcessorBlockingStub tikaStub = 
                PipeStepProcessorGrpc.newBlockingStub(tikaChannel);
        
        // Create test document
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId("simple-test-doc")
                .setBody("This is a simple test document")
                .setSourceMimeType("text/plain")
                .setDocumentType("test")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .build();
        
        ProcessResponse response = tikaStub.processData(request);
        
        assertNotNull(response);
        assertTrue(response.getSuccess(), "Processing should succeed");
        assertTrue(response.hasOutputDoc(), "Should have output document");
        assertEquals("simple-test-doc", response.getOutputDoc().getId());
        
        log.info("Successfully processed document through Tika parser");
    }
    
    @Test
    @Order(4)
    void testModuleUnregistration() {
        // First register a module
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                .setServiceName("test-module")
                .setServiceId("test-module-001")
                .setHost("localhost")
                .setPort(12345)
                .setHealthEndpoint("/health")
                .build();
        
        RegistrationStatus regStatus = registrationStub.registerModule(moduleInfo);
        assertTrue(regStatus.getSuccess(), "Registration should succeed");
        
        // Now unregister it
        ModuleId moduleId = ModuleId.newBuilder()
                .setServiceId("test-module-001")
                .build();
        
        UnregistrationStatus unregStatus = registrationStub.unregisterModule(moduleId);
        assertTrue(unregStatus.getSuccess(), "Unregistration should succeed");
        
        log.info("Successfully unregistered module");
    }
}