package com.krickert.yappy.integration.container;

import com.krickert.search.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
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
 * Integration test for module registration functionality.
 * This test starts real containers and verifies the registration flow.
 */
@MicronautTest(environments = "container-test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ModuleRegistrationIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleRegistrationIntegrationTest.class);
    private static final Network network = Network.newNetwork();
    
    @Container
    static GenericContainer<?> consulContainer = new GenericContainer<>(DockerImageName.parse("hashicorp/consul:latest"))
            .withExposedPorts(8500)
            .withNetwork(network)
            .withNetworkAliases("consul")
            .withCommand("agent", "-server", "-bootstrap-expect=1", "-client=0.0.0.0", "-ui")
            .waitingFor(Wait.forHttp("/v1/status/leader").forStatusCode(200))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("CONSUL"));
    
    @Container
    static GenericContainer<?> kafkaContainer = new GenericContainer<>(DockerImageName.parse("confluentinc/cp-kafka:latest"))
            .withExposedPorts(9092)
            .withNetwork(network)
            .withNetworkAliases("kafka")
            .withEnv("KAFKA_BROKER_ID", "1")
            .withEnv("KAFKA_ZOOKEEPER_CONNECT", "zookeeper:2181")
            .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092")
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .dependsOn(createZookeeperContainer())
            .waitingFor(Wait.forLogMessage(".*started.*", 1))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("KAFKA"));
    
    @Container
    static GenericContainer<?> apicurioContainer = new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry:3.0.7"))
            .withExposedPorts(8080)
            .withNetwork(network)
            .withNetworkAliases("apicurio")
            .withEnv("QUARKUS_PROFILE", "prod")
            .waitingFor(Wait.forHttp("/health/ready").forPort(8080).forStatusCode(200))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("APICURIO"));
    
    private static GenericContainer<?> engineContainer;
    private static GenericContainer<?> tikaParserContainer;
    private static GenericContainer<?> chunkerContainer;
    
    private ManagedChannel engineChannel;
    private ModuleRegistrationGrpc.ModuleRegistrationBlockingStub registrationStub;
    
    @BeforeAll
    static void setupContainers() {
        // Start infrastructure containers first
        consulContainer.start();
        kafkaContainer.start();
        apicurioContainer.start();
        
        // Build and start engine container
        engineContainer = new GenericContainer<>(DockerImageName.parse("yappy-engine:1.0.0-SNAPSHOT"))
                .withExposedPorts(8080, 50050, 50051)
                .withNetwork(network)
                .withNetworkAliases("engine")
                .withEnv("CONSUL_HOST", "consul")
                .withEnv("CONSUL_PORT", "8500")
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
                .withEnv("APICURIO_REGISTRY_URL", "http://apicurio:8080")
                .withEnv("YAPPY_ENGINE_NAME", "test-engine")
                .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
                .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2))
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("ENGINE"));
        
        engineContainer.start();
        
        // Build and start module containers
        tikaParserContainer = new GenericContainer<>(DockerImageName.parse("yappy-tika-parser:1.0.0-SNAPSHOT"))
                .withExposedPorts(50053)
                .withNetwork(network)
                .withNetworkAliases("tika-parser")
                .withEnv("GRPC_PORT", "50053")
                .waitingFor(Wait.forLogMessage(".*gRPC server started.*", 1))
                .withStartupTimeout(Duration.ofMinutes(1))
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("TIKA"));
        
        chunkerContainer = new GenericContainer<>(DockerImageName.parse("yappy-chunker:1.0.0-SNAPSHOT"))
                .withExposedPorts(50052)
                .withNetwork(network)
                .withNetworkAliases("chunker")
                .withEnv("GRPC_PORT", "50052")
                .waitingFor(Wait.forLogMessage(".*gRPC server started.*", 1))
                .withStartupTimeout(Duration.ofMinutes(1))
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("CHUNKER"));
        
        tikaParserContainer.start();
        chunkerContainer.start();
    }
    
    @BeforeEach
    void setup() {
        // Create gRPC channel to engine's module registration service
        engineChannel = ManagedChannelBuilder
                .forAddress(engineContainer.getHost(), engineContainer.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        registrationStub = ModuleRegistrationGrpc.newBlockingStub(engineChannel);
    }
    
    @AfterEach
    void cleanup() {
        if (engineChannel != null && !engineChannel.isShutdown()) {
            engineChannel.shutdown();
            try {
                engineChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                engineChannel.shutdownNow();
            }
        }
    }
    
    @Test
    @Order(1)
    void testRegisterTikaParserModule() {
        // Create module info for Tika Parser
        ModuleInfo tikaInfo = ModuleInfo.newBuilder()
                .setServiceName("tika-parser")
                .setServiceId("tika-parser-test-001")
                .setHost("tika-parser")
                .setPort(50053)
                .setHealthEndpoint("/grpc.health.v1.Health/Check")
                .putMetadata("version", "1.0.0")
                .putMetadata("type", "parser")
                .addTags("parser")
                .addTags("document-processing")
                .build();
        
        // Register the module
        RegistrationStatus status = registrationStub.registerModule(tikaInfo);
        
        // Verify registration
        assertTrue(status.getSuccess(), "Registration should succeed");
        assertEquals("Module registered successfully", status.getMessage());
        assertNotNull(status.getConsulServiceId(), "Should have Consul service ID");
        assertTrue(status.hasRegisteredAt(), "Should have registration timestamp");
        
        log.info("Successfully registered Tika Parser module with ID: {}", status.getConsulServiceId());
    }
    
    @Test
    @Order(2)
    void testRegisterChunkerModule() {
        // Create module info for Chunker
        ModuleInfo chunkerInfo = ModuleInfo.newBuilder()
                .setServiceName("chunker")
                .setServiceId("chunker-test-001")
                .setHost("chunker")
                .setPort(50052)
                .setHealthEndpoint("/grpc.health.v1.Health/Check")
                .putMetadata("version", "1.0.0")
                .putMetadata("type", "processor")
                .addTags("processor")
                .addTags("text-chunking")
                .build();
        
        // Register the module
        RegistrationStatus status = registrationStub.registerModule(chunkerInfo);
        
        // Verify registration
        assertTrue(status.getSuccess(), "Registration should succeed");
        assertEquals("Module registered successfully", status.getMessage());
        assertNotNull(status.getConsulServiceId(), "Should have Consul service ID");
        
        log.info("Successfully registered Chunker module with ID: {}", status.getConsulServiceId());
    }
    
    @Test
    @Order(3)
    void testListRegisteredModules() {
        // List all modules
        ModuleList moduleList = registrationStub.listModules(com.google.protobuf.Empty.getDefaultInstance());
        
        // Verify we have both modules registered
        assertNotNull(moduleList);
        assertEquals(2, moduleList.getModulesCount(), "Should have 2 modules registered");
        
        // Check that both modules are in the list
        boolean hasTika = moduleList.getModulesList().stream()
                .anyMatch(m -> "tika-parser".equals(m.getServiceName()));
        boolean hasChunker = moduleList.getModulesList().stream()
                .anyMatch(m -> "chunker".equals(m.getServiceName()));
        
        assertTrue(hasTika, "Should have Tika Parser in module list");
        assertTrue(hasChunker, "Should have Chunker in module list");
        
        moduleList.getModulesList().forEach(module -> {
            log.info("Found registered module: {} ({})", module.getServiceName(), module.getServiceId());
        });
    }
    
    @Test
    @Order(4)
    void testModuleHeartbeat() {
        // Send heartbeat from Tika Parser
        ModuleHeartbeat heartbeat = ModuleHeartbeat.newBuilder()
                .setServiceId("tika-parser-test-001")
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(System.currentTimeMillis() / 1000)
                        .build())
                .putStatusInfo("processed_docs", "100")
                .putStatusInfo("status", "healthy")
                .build();
        
        HeartbeatAck ack = registrationStub.heartbeat(heartbeat);
        
        // Verify acknowledgment
        assertTrue(ack.getAcknowledged(), "Heartbeat should be acknowledged");
        assertEquals("Heartbeat received", ack.getMessage());
        assertTrue(ack.hasServerTime(), "Should have server timestamp");
        
        log.info("Heartbeat acknowledged at server time: {}", ack.getServerTime().getSeconds());
    }
    
    @Test
    @Order(5)
    void testGetModuleHealth() {
        // Get health status for Tika Parser
        ModuleId moduleId = ModuleId.newBuilder()
                .setServiceId("tika-parser-test-001")
                .build();
        
        ModuleHealthStatus health = registrationStub.getModuleHealth(moduleId);
        
        // Verify health status
        assertNotNull(health);
        assertEquals("tika-parser-test-001", health.getServiceId());
        assertEquals("tika-parser", health.getServiceName());
        assertTrue(health.hasLastChecked(), "Should have last checked timestamp");
        
        log.info("Module health: {} - {}", health.getServiceName(), 
                health.getIsHealthy() ? "HEALTHY" : "UNHEALTHY");
    }
    
    @Test
    @Order(6)
    void testUnregisterModule() {
        // Unregister Chunker module
        ModuleId moduleId = ModuleId.newBuilder()
                .setServiceId("chunker-test-001")
                .build();
        
        UnregistrationStatus status = registrationStub.unregisterModule(moduleId);
        
        // Verify unregistration
        assertTrue(status.getSuccess(), "Unregistration should succeed");
        assertEquals("Module unregistered successfully", status.getMessage());
        assertTrue(status.hasUnregisteredAt(), "Should have unregistration timestamp");
        
        log.info("Successfully unregistered Chunker module");
        
        // Verify module is no longer in the list
        ModuleList moduleList = registrationStub.listModules(com.google.protobuf.Empty.getDefaultInstance());
        assertEquals(1, moduleList.getModulesCount(), "Should have only 1 module after unregistration");
        assertEquals("tika-parser", moduleList.getModules(0).getServiceName(), 
                "Only Tika Parser should remain");
    }
    
    @Test
    @Order(7)
    void testRegisterWithInvalidData() {
        // Try to register with missing required fields
        ModuleInfo invalidInfo = ModuleInfo.newBuilder()
                .setServiceName("") // Empty service name
                .setServiceId("invalid-001")
                .setHost("invalid-host")
                .setPort(0) // Invalid port
                .build();
        
        RegistrationStatus status = registrationStub.registerModule(invalidInfo);
        
        // Should fail
        assertFalse(status.getSuccess(), "Registration should fail with invalid data");
        assertTrue(status.getMessage().contains("failed"), "Should have failure message");
    }
    
    private static GenericContainer<?> createZookeeperContainer() {
        return new GenericContainer<>(DockerImageName.parse("confluentinc/cp-zookeeper:latest"))
                .withExposedPorts(2181)
                .withNetwork(network)
                .withNetworkAliases("zookeeper")
                .withEnv("ZOOKEEPER_CLIENT_PORT", "2181")
                .withEnv("ZOOKEEPER_TICK_TIME", "2000");
    }
}