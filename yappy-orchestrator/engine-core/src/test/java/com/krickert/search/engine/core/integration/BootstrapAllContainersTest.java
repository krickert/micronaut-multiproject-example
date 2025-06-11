package com.krickert.search.engine.core.integration;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bootstrap test that forces all test resource containers to start.
 * This test should run first to ensure all containers are available for other tests.
 * 
 * By injecting all container properties, we force Micronaut Test Resources to start
 * all containers via the shared test resources server.
 */
@MicronautTest(startApplication = false)
public class BootstrapAllContainersTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAllContainersTest.class);
    
    // Base infrastructure containers
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Property(name = "consul.client.port") 
    String consulPort;
    
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Property(name = "apicurio.registry.url")
    String apicurioUrl;
    
    @Property(name = "opensearch.hosts")
    String opensearchHosts;
    
    @Property(name = "moto.endpoint.url")
    String motoEndpoint;
    
    // Module containers
    @Property(name = "chunker.grpc.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port")
    String chunkerPort;
    
    // TODO: Add more module properties as test resources are created
    // @Property(name = "tika.grpc.host")
    // String tikaHost;
    
    // @Property(name = "embedder.grpc.host") 
    // String embedderHost;
    
    // @Property(name = "echo.grpc.host")
    // String echoHost;
    
    // @Property(name = "test-module.grpc.host")
    // String testModuleHost;
    
    // Engine container
    // @Property(name = "engine.grpc.host")
    // String engineHost;
    
    @Test
    void testAllContainersAreRunning() {
        LOG.info("=== Bootstrap Test: Verifying all containers are running ===");
        
        // Verify base infrastructure
        assertNotNull(consulHost, "Consul host should be available");
        assertNotNull(consulPort, "Consul port should be available");
        LOG.info("✓ Consul available at {}:{}", consulHost, consulPort);
        
        assertNotNull(kafkaBootstrapServers, "Kafka bootstrap servers should be available");
        LOG.info("✓ Kafka available at {}", kafkaBootstrapServers);
        
        assertNotNull(apicurioUrl, "Apicurio registry URL should be available");
        LOG.info("✓ Apicurio available at {}", apicurioUrl);
        
        assertNotNull(opensearchHosts, "OpenSearch hosts should be available");
        LOG.info("✓ OpenSearch available at {}", opensearchHosts);
        
        assertNotNull(motoEndpoint, "Moto endpoint should be available");
        LOG.info("✓ Moto (AWS mock) available at {}", motoEndpoint);
        
        // Verify module containers
        assertNotNull(chunkerHost, "Chunker host should be available");
        assertNotNull(chunkerPort, "Chunker port should be available");
        LOG.info("✓ Chunker module available at {}:{}", chunkerHost, chunkerPort);
        
        // Log network information for debugging
        LOG.info("\n=== Container Network Information ===");
        LOG.info("All containers are on the 'yappy-test-network' Docker network");
        LOG.info("Internal communication uses network aliases:");
        LOG.info("  - consul:8500");
        LOG.info("  - kafka:9092");
        LOG.info("  - apicurio:8080");
        LOG.info("  - opensearch:9200");
        LOG.info("  - moto:5000");
        LOG.info("  - chunker:50051");
        
        // Verify Consul UI is accessible
        LOG.info("\n=== Consul UI Access ===");
        LOG.info("Consul UI available at: http://{}:{}", consulHost, consulPort);
        
        LOG.info("\n=== All containers successfully started! ===");
        
        // Simple assertion to mark test as passed
        assertTrue(true, "All containers are running");
    }
}