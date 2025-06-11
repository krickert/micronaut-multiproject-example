package com.krickert.search.engine.core.integration;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify all containers (base infrastructure + modules) start successfully.
 * This test uses the Micronaut Test Resources framework to automatically start all required containers.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AllModulesBootstrapTest implements TestPropertyProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(AllModulesBootstrapTest.class);
    
    // Base infrastructure properties
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Property(name = "consul.client.port")
    Integer consulPort;
    
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Property(name = "apicurio.registry.url")
    String apicurioUrl;
    
    @Property(name = "opensearch.url")
    String opensearchUrl;
    
    @Property(name = "aws.endpoint")
    String motoEndpoint;
    
    // Module properties
    @Property(name = "chunker.grpc.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port")
    Integer chunkerPort;
    
    @Property(name = "tika.grpc.host")
    String tikaHost;
    
    @Property(name = "tika.grpc.port")
    Integer tikaPort;
    
    @Property(name = "embedder.grpc.host")
    String embedderHost;
    
    @Property(name = "embedder.grpc.port")
    Integer embedderPort;
    
    @Property(name = "echo.grpc.host")
    String echoHost;
    
    @Property(name = "echo.grpc.port")
    Integer echoPort;
    
    @Property(name = "test-module.grpc.host")
    String testModuleHost;
    
    @Property(name = "test-module.grpc.port")
    Integer testModulePort;
    
    @Test
    void testAllContainersAreRunning() {
        LOG.info("\n=== Base Infrastructure Containers ===");
        
        // Verify Consul
        assertThat(consulHost).isNotNull();
        assertThat(consulPort).isNotNull();
        LOG.info("✓ Consul: {}:{}", consulHost, consulPort);
        
        // Verify Kafka
        assertThat(kafkaBootstrapServers).isNotNull();
        LOG.info("✓ Kafka: {}", kafkaBootstrapServers);
        
        // Verify Apicurio
        assertThat(apicurioUrl).isNotNull();
        LOG.info("✓ Apicurio: {}", apicurioUrl);
        
        // Verify OpenSearch
        assertThat(opensearchUrl).isNotNull();
        LOG.info("✓ OpenSearch: {}", opensearchUrl);
        
        // Verify Moto (AWS mock)
        assertThat(motoEndpoint).isNotNull();
        LOG.info("✓ Moto (AWS mock): {}", motoEndpoint);
        
        LOG.info("\n=== Module Containers ===");
        
        // Verify Chunker module
        assertThat(chunkerHost).isNotNull();
        assertThat(chunkerPort).isNotNull();
        LOG.info("✓ Chunker: {}:{}", chunkerHost, chunkerPort);
        
        // Verify Tika module
        assertThat(tikaHost).isNotNull();
        assertThat(tikaPort).isNotNull();
        LOG.info("✓ Tika Parser: {}:{}", tikaHost, tikaPort);
        
        // Verify Embedder module
        assertThat(embedderHost).isNotNull();
        assertThat(embedderPort).isNotNull();
        LOG.info("✓ Embedder: {}:{}", embedderHost, embedderPort);
        
        // Verify Echo module
        assertThat(echoHost).isNotNull();
        assertThat(echoPort).isNotNull();
        LOG.info("✓ Echo: {}:{}", echoHost, echoPort);
        
        // Verify Test Module
        assertThat(testModuleHost).isNotNull();
        assertThat(testModulePort).isNotNull();
        LOG.info("✓ Test Module: {}:{}", testModuleHost, testModulePort);
        
        LOG.info("\n=== All containers started successfully! ===");
    }
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
            "grpc.server.enabled", "false",  // Disable gRPC server for this test
            "kafka.producer.bootstrap.servers", "${kafka.bootstrap.servers}",
            "aws.endpoint-override-enabled", "true",
            "aws.services.s3.endpoint-override", "${aws.endpoint}",
            "aws.region", "us-east-1",
            "aws.access-key-id", "test",
            "aws.secret-key", "test"
        );
    }
}