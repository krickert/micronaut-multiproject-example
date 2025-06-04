package com.krickert.yappy.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.time.Duration;

/**
 * Base class for container integration tests.
 * Manages the lifecycle of the docker-compose environment.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseContainerIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(BaseContainerIntegrationTest.class);
    
    protected static final String CONSUL_SERVICE = "consul";
    protected static final String KAFKA_SERVICE = "kafka";
    protected static final String APICURIO_SERVICE = "apicurio";
    protected static final String ENGINE_TIKA_SERVICE = "engine-tika";
    protected static final String ENGINE_CHUNKER_SERVICE = "engine-chunker";
    
    protected static final int CONSUL_PORT = 8500;
    protected static final int KAFKA_PORT = 9092;
    protected static final int APICURIO_PORT = 8080;
    protected static final int ENGINE_HTTP_PORT = 8080;
    protected static final int ENGINE_GRPC_PORT = 50051;
    protected static final int TIKA_GRPC_PORT = 50052;
    protected static final int CHUNKER_GRPC_PORT = 50053;
    
    @Container
    protected static ComposeContainer environment = new ComposeContainer(
            new File("docker-compose.test.yml"))
            .withExposedService(CONSUL_SERVICE, CONSUL_PORT,
                    Wait.forHttp("/v1/status/leader").forStatusCode(200))
            .withExposedService(KAFKA_SERVICE, KAFKA_PORT,
                    Wait.forListeningPort())
            .withExposedService(APICURIO_SERVICE, APICURIO_PORT,
                    Wait.forHttp("/health/ready").forStatusCode(200))
            .withExposedService(ENGINE_TIKA_SERVICE, ENGINE_HTTP_PORT,
                    Wait.forHttp("/health").forStatusCode(200))
            .withExposedService(ENGINE_TIKA_SERVICE, ENGINE_GRPC_PORT,
                    Wait.forListeningPort())
            .withExposedService(ENGINE_TIKA_SERVICE, TIKA_GRPC_PORT,
                    Wait.forListeningPort())
            .withExposedService(ENGINE_CHUNKER_SERVICE, ENGINE_HTTP_PORT,
                    Wait.forHttp("/health").forStatusCode(200))
            .withExposedService(ENGINE_CHUNKER_SERVICE, ENGINE_GRPC_PORT,
                    Wait.forListeningPort())
            .withExposedService(ENGINE_CHUNKER_SERVICE, CHUNKER_GRPC_PORT,
                    Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(5));
    
    @BeforeAll
    public void setupEnvironment() {
        LOG.info("Starting integration test environment...");
        
        // Log service endpoints for debugging
        LOG.info("Consul endpoint: {}:{}", getConsulHost(), getConsulPort());
        LOG.info("Kafka endpoint: {}:{}", getKafkaHost(), getKafkaPort());
        LOG.info("Apicurio endpoint: {}:{}", getApicurioHost(), getApicurioPort());
        
        // Engine-Tika endpoints
        LOG.info("Engine-Tika HTTP endpoint: {}:{}", getEngineTikaHost(), getEngineTikaHttpPort());
        LOG.info("Engine-Tika gRPC endpoint: {}:{}", getEngineTikaHost(), getEngineTikaGrpcPort());
        LOG.info("Tika module gRPC endpoint: {}:{}", getEngineTikaHost(), getTikaGrpcPort());
        
        // Engine-Chunker endpoints
        LOG.info("Engine-Chunker HTTP endpoint: {}:{}", getEngineChunkerHost(), getEngineChunkerHttpPort());
        LOG.info("Engine-Chunker gRPC endpoint: {}:{}", getEngineChunkerHost(), getEngineChunkerGrpcPort());
        LOG.info("Chunker module gRPC endpoint: {}:{}", getEngineChunkerHost(), getChunkerGrpcPort());
    }
    
    // Helper methods to get service endpoints
    protected String getConsulHost() {
        return environment.getServiceHost(CONSUL_SERVICE, CONSUL_PORT);
    }
    
    protected Integer getConsulPort() {
        return environment.getServicePort(CONSUL_SERVICE, CONSUL_PORT);
    }
    
    protected String getKafkaHost() {
        return environment.getServiceHost(KAFKA_SERVICE, KAFKA_PORT);
    }
    
    protected Integer getKafkaPort() {
        return environment.getServicePort(KAFKA_SERVICE, KAFKA_PORT);
    }
    
    protected String getApicurioHost() {
        return environment.getServiceHost(APICURIO_SERVICE, APICURIO_PORT);
    }
    
    protected Integer getApicurioPort() {
        return environment.getServicePort(APICURIO_SERVICE, APICURIO_PORT);
    }
    
    // Engine-Tika endpoints
    protected String getEngineTikaHost() {
        return environment.getServiceHost(ENGINE_TIKA_SERVICE, ENGINE_HTTP_PORT);
    }
    
    protected Integer getEngineTikaHttpPort() {
        return environment.getServicePort(ENGINE_TIKA_SERVICE, ENGINE_HTTP_PORT);
    }
    
    protected Integer getEngineTikaGrpcPort() {
        return environment.getServicePort(ENGINE_TIKA_SERVICE, ENGINE_GRPC_PORT);
    }
    
    protected Integer getTikaGrpcPort() {
        return environment.getServicePort(ENGINE_TIKA_SERVICE, TIKA_GRPC_PORT);
    }
    
    // Engine-Chunker endpoints
    protected String getEngineChunkerHost() {
        return environment.getServiceHost(ENGINE_CHUNKER_SERVICE, ENGINE_HTTP_PORT);
    }
    
    protected Integer getEngineChunkerHttpPort() {
        return environment.getServicePort(ENGINE_CHUNKER_SERVICE, ENGINE_HTTP_PORT);
    }
    
    protected Integer getEngineChunkerGrpcPort() {
        return environment.getServicePort(ENGINE_CHUNKER_SERVICE, ENGINE_GRPC_PORT);
    }
    
    protected Integer getChunkerGrpcPort() {
        return environment.getServicePort(ENGINE_CHUNKER_SERVICE, CHUNKER_GRPC_PORT);
    }
    
    // Deprecated - use getEngineTikaHost() instead
    @Deprecated
    protected String getEngineHost() {
        return getEngineTikaHost();
    }
    
    @Deprecated
    protected Integer getEngineHttpPort() {
        return getEngineTikaHttpPort();
    }
    
    @Deprecated  
    protected Integer getEngineGrpcPort() {
        return getEngineTikaGrpcPort();
    }
    
    protected String getKafkaBootstrapServers() {
        return getKafkaHost() + ":" + getKafkaPort();
    }
    
    protected String getConsulUrl() {
        return "http://" + getConsulHost() + ":" + getConsulPort();
    }
    
    protected String getApicurioUrl() {
        return "http://" + getApicurioHost() + ":" + getApicurioPort();
    }
}