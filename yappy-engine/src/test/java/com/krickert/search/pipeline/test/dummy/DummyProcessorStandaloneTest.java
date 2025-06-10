package com.krickert.search.pipeline.test.dummy;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone test showing how easy it is to test a module in isolation
 * with the new architecture. No need for engine, Consul, or Kafka!
 */
public class DummyProcessorStandaloneTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(DummyProcessorStandaloneTest.class);
    
    private Server grpcServer;
    private ManagedChannel channel;
    private PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub;
    private DummyPipeStepProcessor processor;
    private int port;
    
    @BeforeEach
    void setUp() throws IOException {
        // Find available port
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        
        // Create processor
        processor = new DummyPipeStepProcessor("append", " [TESTED]");
        
        // Start gRPC server
        grpcServer = ServerBuilder.forPort(port)
                .addService(processor)
                .build()
                .start();
        
        LOG.info("Started test gRPC server on port {}", port);
        
        // Create client
        channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        
        stub = PipeStepProcessorGrpc.newBlockingStub(channel);
    }
    
    @Test
    @DisplayName("Module should return its registration info")
    void testGetServiceRegistration() {
        // Call GetServiceRegistration
        ServiceRegistrationData registration = stub.getServiceRegistration(Empty.getDefaultInstance());
        
        // Verify response
        assertNotNull(registration);
        assertEquals("dummy-processor", registration.getModuleName());
        assertTrue(registration.hasJsonConfigSchema());
        
        // Verify schema content
        String schema = registration.getJsonConfigSchema();
        assertTrue(schema.contains("\"$schema\""));
        assertTrue(schema.contains("http://json-schema.org/draft-07/schema#"));
        assertTrue(schema.contains("\"behavior\""));
        assertTrue(schema.contains("\"simulate_error\""));
        assertTrue(schema.contains("\"delay_ms\""));
        
        LOG.info("✅ Module registration info retrieved successfully");
    }
    
    @Test
    @DisplayName("Module should process documents with append behavior")
    void testProcessWithAppendBehavior() {
        // Create test document
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("Test Title")
                .setBody("Test body content")
                .build();
        
        // Create process request
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .setConfig(ProcessConfiguration.newBuilder()
                        .setCustomJsonConfig(Struct.newBuilder()
                                .putFields("behavior", Value.newBuilder().setStringValue("append").build())
                                .build())
                        .build())
                .setMetadata(ServiceMetadata.newBuilder()
                        .setPipelineName("test-pipeline")
                        .setPipeStepName("test-step")
                        .setStreamId("test-stream")
                        .build())
                .build();
        
        // Process
        ProcessResponse response = stub.processData(request);
        
        // Verify response
        assertTrue(response.getSuccess());
        assertNotNull(response.getOutputDoc());
        
        PipeDoc outputDoc = response.getOutputDoc();
        assertEquals("Test Title [TESTED]", outputDoc.getTitle(), "Title should have suffix");
        assertTrue(outputDoc.getBody().contains("[Processed by DummyPipeStepProcessor]"));
        
        // Verify metadata
        assertTrue(outputDoc.hasCustomData());
        assertEquals("true", outputDoc.getCustomData().getFieldsOrThrow("dummy_processed").getStringValue());
        assertEquals("append", outputDoc.getCustomData().getFieldsOrThrow("dummy_behavior").getStringValue());
        
        LOG.info("✅ Document processed successfully with append behavior");
    }
    
    @Test
    @DisplayName("Module should handle uppercase behavior")
    void testProcessWithUppercaseBehavior() {
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("lowercase title")
                .setBody("lowercase body content")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .setConfig(ProcessConfiguration.newBuilder()
                        .setCustomJsonConfig(Struct.newBuilder()
                                .putFields("behavior", Value.newBuilder().setStringValue("uppercase").build())
                                .build())
                        .build())
                .setMetadata(ServiceMetadata.newBuilder()
                        .setPipelineName("test-pipeline")
                        .setPipeStepName("test-step")
                        .setStreamId("test-stream")
                        .build())
                .build();
        
        ProcessResponse response = stub.processData(request);
        
        assertTrue(response.getSuccess());
        assertEquals("LOWERCASE BODY CONTENT", response.getOutputDoc().getBody());
        assertEquals("uppercase", response.getOutputDoc().getCustomData()
                .getFieldsOrThrow("dummy_behavior").getStringValue());
        
        LOG.info("✅ Document processed successfully with uppercase behavior");
    }
    
    @Test
    @DisplayName("Module should simulate errors when configured")
    void testSimulateError() {
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("Test")
                .setBody("Test")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .setConfig(ProcessConfiguration.newBuilder()
                        .setCustomJsonConfig(Struct.newBuilder()
                                .putFields("simulate_error", Value.newBuilder().setBoolValue(true).build())
                                .build())
                        .build())
                .setMetadata(ServiceMetadata.newBuilder()
                        .setPipelineName("test-pipeline")
                        .setPipeStepName("test-step")
                        .setStreamId("test-stream")
                        .build())
                .build();
        
        // Should throw exception due to simulated error
        assertThrows(io.grpc.StatusRuntimeException.class, () -> {
            stub.processData(request);
        });
        
        LOG.info("✅ Error simulation works correctly");
    }
    
    @Test
    @DisplayName("Module should handle processing delays")
    void testProcessingDelay() {
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("Test")
                .setBody("Test")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .setConfig(ProcessConfiguration.newBuilder()
                        .setCustomJsonConfig(Struct.newBuilder()
                                .putFields("delay_ms", Value.newBuilder().setNumberValue(500).build())
                                .build())
                        .build())
                .setMetadata(ServiceMetadata.newBuilder()
                        .setPipelineName("test-pipeline")
                        .setPipeStepName("test-step")
                        .setStreamId("test-stream")
                        .build())
                .build();
        
        long startTime = System.currentTimeMillis();
        ProcessResponse response = stub.processData(request);
        long duration = System.currentTimeMillis() - startTime;
        
        assertTrue(response.getSuccess());
        assertTrue(duration >= 500, "Processing should take at least 500ms");
        
        LOG.info("✅ Processing delay works correctly (took {}ms)", duration);
    }
    
    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        if (grpcServer != null) {
            grpcServer.shutdown();
            grpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        LOG.info("Test cleanup complete");
    }
}