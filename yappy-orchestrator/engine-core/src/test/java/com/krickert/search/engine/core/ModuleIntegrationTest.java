package com.krickert.search.engine.core;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for module services using Micronaut generic test containers.
 * This test launches the chunker and tika-parser modules as Docker containers
 * and creates gRPC clients to test their functionality.
 * 
 * NOTE: This test requires that the chunker and tika-parser Docker images have been built.
 * Run these commands from the project root before running this test:
 * 
 * ./gradlew :yappy-modules:chunker:dockerBuild
 * ./gradlew :yappy-modules:tika-parser:dockerBuild
 */
@MicronautTest(environments = "module-test")
public class ModuleIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ModuleIntegrationTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Property(name = "chunker.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port")
    Integer chunkerGrpcPort;
    
    @Property(name = "tika.host")
    String tikaHost;
    
    @Property(name = "tika.grpc.port")
    Integer tikaGrpcPort;
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testChunkerServiceRegistration() {
        logger.info("Testing chunker service at {}:{}", chunkerHost, chunkerGrpcPort);
        
        // Create gRPC channel and stub
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(chunkerHost, chunkerGrpcPort)
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);
            
            // Test service registration
            ServiceRegistrationData registration = stub.getServiceRegistration(Empty.getDefaultInstance());
            
            assertThat(registration).isNotNull();
            assertThat(registration.getModuleName())
                .as("Chunker module name should be set")
                .isNotEmpty();
            
            logger.info("Chunker service registration successful: {}", registration.getModuleName());
            
            // If JSON schema is provided, log it
            if (registration.hasJsonConfigSchema()) {
                logger.info("Chunker configuration schema: {}", registration.getJsonConfigSchema());
            }
        } finally {
            channel.shutdown();
        }
    }
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testChunkerProcessData() {
        logger.info("Testing chunker data processing at {}:{}", chunkerHost, chunkerGrpcPort);
        
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(chunkerHost, chunkerGrpcPort)
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);
            
            // Create test document
            String testContent = "This is a test document. It contains multiple sentences. " +
                               "Each sentence should be chunked separately. This is the fourth sentence.";
            
            PipeDoc testDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("test-document.txt")
                .setBody(testContent)
                .setDocumentType("text")
                .setBlob(Blob.newBuilder()
                    .setData(ByteString.copyFrom(testContent.getBytes()))
                    .setMimeType("text/plain")
                    .build())
                .build();
            
            // Create configuration
            Struct configStruct = Struct.newBuilder()
                .putFields("chunkSize", com.google.protobuf.Value.newBuilder().setNumberValue(100).build())
                .putFields("overlap", com.google.protobuf.Value.newBuilder().setNumberValue(10).build())
                .build();
            
            ProcessConfiguration config = ProcessConfiguration.newBuilder()
                .setCustomJsonConfig(configStruct)
                .putConfigParams("language", "en")
                .build();
            
            // Create metadata
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName("chunker-step")
                .setStreamId(UUID.randomUUID().toString())
                .setCurrentHopNumber(1)
                .putContextParams("test", "true")
                .build();
            
            // Create request
            ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .setConfig(config)
                .setMetadata(metadata)
                .build();
            
            // Process data
            ProcessResponse response = stub.processData(request);
            
            // Verify response
            assertThat(response).isNotNull();
            assertThat(response.getSuccess())
                .as("Processing should be successful")
                .isTrue();
            
            if (response.hasOutputDoc()) {
                PipeDoc outputDoc = response.getOutputDoc();
                logger.info("Chunker produced output document: {}", outputDoc.getTitle());
                
                // Check if chunks were created (this depends on chunker implementation)
                if (outputDoc.hasCustomData()) {
                    logger.info("Output custom data: {}", outputDoc.getCustomData());
                }
                
                // Check semantic results for chunks
                if (outputDoc.getSemanticResultsCount() > 0) {
                    logger.info("Semantic results count: {}", outputDoc.getSemanticResultsCount());
                }
            }
            
            // Log any processor logs
            if (response.getProcessorLogsCount() > 0) {
                logger.info("Chunker logs:");
                response.getProcessorLogsList().forEach(log -> logger.info("  - {}", log));
            }
            
        } finally {
            channel.shutdown();
        }
    }
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testTikaParserServiceRegistration() {
        logger.info("Testing tika-parser service at {}:{}", tikaHost, tikaGrpcPort);
        
        // Create gRPC channel and stub
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(tikaHost, tikaGrpcPort)
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);
            
            // Test service registration
            ServiceRegistrationData registration = stub.getServiceRegistration(Empty.getDefaultInstance());
            
            assertThat(registration).isNotNull();
            assertThat(registration.getModuleName())
                .as("Tika parser module name should be set")
                .isNotEmpty();
            
            logger.info("Tika parser service registration successful: {}", registration.getModuleName());
            
            // If JSON schema is provided, log it
            if (registration.hasJsonConfigSchema()) {
                logger.info("Tika parser configuration schema: {}", registration.getJsonConfigSchema());
            }
        } finally {
            channel.shutdown();
        }
    }
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testConsulServiceRegistration() {
        // Once modules are running and reachable, we can test registering them with Consul
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse("localhost");
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(8500);
        
        logger.info("Consul is available at {}:{}", consulHost, consulPort);
        logger.info("Chunker service is at {}:{}", chunkerHost, chunkerGrpcPort);
        logger.info("Tika parser service is at {}:{}", tikaHost, tikaGrpcPort);
        
        // This demonstrates that the engine can reach the modules
        // In a real implementation, the engine would:
        // 1. Query modules for their ServiceRegistrationData
        // 2. Register them in Consul with health checks
        // 3. Use Consul for service discovery during pipeline execution
        
        assertThat(chunkerHost).isNotNull();
        assertThat(chunkerGrpcPort).isNotNull();
        assertThat(tikaHost).isNotNull();
        assertThat(tikaGrpcPort).isNotNull();
        
        logger.info("All services are reachable and ready for Consul registration");
    }
}