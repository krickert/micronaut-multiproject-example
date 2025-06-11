package com.krickert.search.engine.core.integration;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive test to verify all containers (base infrastructure + modules) are starting properly.
 * This test forces all test resources to start by requesting their properties.
 * 
 * NOTE: Module containers require Docker images to be built first.
 * Run: ./gradlew :yappy-modules:chunker:dockerBuild (etc.) to build module images.
 */
@MicronautTest(startApplication = false)
public class AllContainersVerificationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(AllContainersVerificationTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void testAllInfrastructureContainersAreAvailable() {
        LOG.info("\n=== Verifying Base Infrastructure Containers ===");
        
        // Consul
        var consulHost = applicationContext.getProperty("consul.client.host", String.class);
        var consulPort = applicationContext.getProperty("consul.client.port", Integer.class);
        assertThat(consulHost).isPresent();
        assertThat(consulPort).isPresent();
        LOG.info("✓ Consul: {}:{}", consulHost.get(), consulPort.get());
        
        // Kafka
        var kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class);
        assertThat(kafkaServers).isPresent();
        LOG.info("✓ Kafka: {}", kafkaServers.get());
        
        // Apicurio Registry
        var apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class);
        assertThat(apicurioUrl).isPresent();
        LOG.info("✓ Apicurio Registry: {}", apicurioUrl.get());
        
        // OpenSearch
        var opensearchUrl = applicationContext.getProperty("opensearch.url", String.class);
        assertThat(opensearchUrl).isPresent();
        LOG.info("✓ OpenSearch: {}", opensearchUrl.get());
        
        // Moto (AWS Mock)
        var awsEndpoint = applicationContext.getProperty("aws.endpoint", String.class);
        assertThat(awsEndpoint).isPresent();
        LOG.info("✓ Moto (AWS Mock): {}", awsEndpoint.get());
    }
    
    @Test
    void testAllModuleContainersAreAvailable() {
        LOG.info("\n=== Verifying Module Containers ===");
        
        // Chunker Module
        var chunkerHost = applicationContext.getProperty("chunker.grpc.host", String.class);
        var chunkerPort = applicationContext.getProperty("chunker.grpc.port", Integer.class);
        assertThat(chunkerHost).isPresent();
        assertThat(chunkerPort).isPresent();
        LOG.info("✓ Chunker: {}:{}", chunkerHost.get(), chunkerPort.get());
        
        // Tika Parser Module
        var tikaHost = applicationContext.getProperty("tika.grpc.host", String.class);
        var tikaPort = applicationContext.getProperty("tika.grpc.port", Integer.class);
        assertThat(tikaHost).isPresent();
        assertThat(tikaPort).isPresent();
        LOG.info("✓ Tika Parser: {}:{}", tikaHost.get(), tikaPort.get());
        
        // Embedder Module
        var embedderHost = applicationContext.getProperty("embedder.grpc.host", String.class);
        var embedderPort = applicationContext.getProperty("embedder.grpc.port", Integer.class);
        assertThat(embedderHost).isPresent();
        assertThat(embedderPort).isPresent();
        LOG.info("✓ Embedder: {}:{}", embedderHost.get(), embedderPort.get());
        
        // Echo Module
        var echoHost = applicationContext.getProperty("echo.grpc.host", String.class);
        var echoPort = applicationContext.getProperty("echo.grpc.port", Integer.class);
        assertThat(echoHost).isPresent();
        assertThat(echoPort).isPresent();
        LOG.info("✓ Echo: {}:{}", echoHost.get(), echoPort.get());
        
        // Test Module
        var testModuleHost = applicationContext.getProperty("test-module.grpc.host", String.class);
        var testModulePort = applicationContext.getProperty("test-module.grpc.port", Integer.class);
        assertThat(testModuleHost).isPresent();
        assertThat(testModulePort).isPresent();
        LOG.info("✓ Test Module: {}:{}", testModuleHost.get(), testModulePort.get());
    }
    
    @Test
    void testInternalNetworkAliasesAreAvailable() {
        LOG.info("\n=== Verifying Internal Network Aliases ===");
        
        // These properties demonstrate that containers can communicate internally
        var chunkerInternal = applicationContext.getProperty("chunker.internal.host", String.class);
        assertThat(chunkerInternal).isPresent();
        assertThat(chunkerInternal.get()).isEqualTo("chunker");
        LOG.info("✓ Chunker internal alias: {}", chunkerInternal.get());
        
        var tikaInternal = applicationContext.getProperty("tika.internal.host", String.class);
        assertThat(tikaInternal).isPresent();
        assertThat(tikaInternal.get()).isEqualTo("tika");
        LOG.info("✓ Tika internal alias: {}", tikaInternal.get());
        
        LOG.info("\nAll containers are running on the shared 'yappy-test-network' and can communicate internally");
    }
}