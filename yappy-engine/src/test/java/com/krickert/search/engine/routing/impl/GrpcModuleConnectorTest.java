package com.krickert.search.engine.routing.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.model.*;
import com.krickert.search.sdk.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class GrpcModuleConnectorTest {
    
    @Inject
    private ObjectMapper objectMapper;
    
    private GrpcModuleConnector moduleConnector;
    private static Server grpcServer;
    private static int grpcPort;
    private static MockPipeStepProcessor mockProcessor;
    
    @BeforeAll
    static void setupGrpcServer() throws IOException {
        // Find a free port
        try (ServerSocket socket = new ServerSocket(0)) {
            grpcPort = socket.getLocalPort();
        }
        
        mockProcessor = new MockPipeStepProcessor();
        grpcServer = ServerBuilder.forPort(grpcPort)
                .addService(mockProcessor)
                .build()
                .start();
    }
    
    @AfterAll
    static void teardownGrpcServer() {
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                grpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                grpcServer.shutdownNow();
            }
        }
    }
    
    @BeforeEach
    void setup() {
        moduleConnector = new GrpcModuleConnector(objectMapper);
        mockProcessor.reset();
    }
    
    @Test
    @DisplayName("Should process document successfully")
    void testProcessDocumentSuccess() {
        // Setup
        ModuleInfo moduleInfo = createModuleInfo("test-module", "localhost", grpcPort);
        PipeStream pipeStream = createTestPipeStream();
        Map<String, Object> stepConfig = Map.of("key", "value");
        
        // Module connector will create its own channel
        
        // Configure mock processor to return success
        ProcessResponse expectedResponse = ProcessResponse.newBuilder()
                .setSuccess(true)
                .setOutputDoc(pipeStream.getDocument().toBuilder()
                        .setTitle("Processed: " + pipeStream.getDocument().getTitle())
                        .build())
                .addProcessorLogs("Processing successful")
                .build();
        
        mockProcessor.setResponse(expectedResponse);
        
        // Execute
        StepVerifier.create(moduleConnector.processDocument(
                    moduleInfo, pipeStream, "test-step", stepConfig))
                .assertNext(response -> {
                    assertTrue(response.getSuccess());
                    assertTrue(response.hasOutputDoc());
                    assertEquals("Processed: Test Document", response.getOutputDoc().getTitle());
                    assertEquals(1, response.getProcessorLogsCount());
                })
                .verifyComplete();
        
        // Verify the request was received correctly
        ProcessRequest capturedRequest = mockProcessor.getLastRequest();
        assertNotNull(capturedRequest);
        assertEquals("test-step", capturedRequest.getMetadata().getPipeStepName());
        assertEquals("test-stream-123", capturedRequest.getMetadata().getStreamId());
        
        // Cleanup
        moduleConnector.disconnectModule(moduleInfo.getServiceId());
    }
    
    @Test
    @DisplayName("Should handle processing failure")
    void testProcessDocumentFailure() {
        // Setup
        ModuleInfo moduleInfo = createModuleInfo("test-module", "localhost", grpcPort);
        PipeStream pipeStream = createTestPipeStream();
        Map<String, Object> stepConfig = Map.of();
        
        // Module connector will create its own channel
        
        // Configure mock processor to return failure
        Struct errorDetails = Struct.newBuilder()
                .putFields("error", com.google.protobuf.Value.newBuilder()
                        .setStringValue("Processing failed")
                        .build())
                .build();
        
        ProcessResponse failureResponse = ProcessResponse.newBuilder()
                .setSuccess(false)
                .setErrorDetails(errorDetails)
                .addProcessorLogs("Error occurred during processing")
                .build();
        
        mockProcessor.setResponse(failureResponse);
        
        // Execute
        StepVerifier.create(moduleConnector.processDocument(
                    moduleInfo, pipeStream, "test-step", stepConfig))
                .assertNext(response -> {
                    assertFalse(response.getSuccess());
                    assertTrue(response.hasErrorDetails());
                    assertEquals(1, response.getProcessorLogsCount());
                })
                .verifyComplete();
        
        // Cleanup
        moduleConnector.disconnectModule(moduleInfo.getServiceId());
    }
    
    @Test
    @DisplayName("Should check module availability")
    void testIsModuleAvailable() {
        // Setup
        ModuleInfo moduleInfo = createModuleInfo("test-module", "localhost", grpcPort);
        
        // Module connector will create its own channel
        
        // Execute
        StepVerifier.create(moduleConnector.isModuleAvailable(moduleInfo))
                .expectNext(true)
                .verifyComplete();
        
        // Cleanup
        moduleConnector.disconnectModule(moduleInfo.getServiceId());
    }
    
    @Test
    @DisplayName("Should get service registration")
    void testGetServiceRegistration() {
        // Setup
        ModuleInfo moduleInfo = createModuleInfo("test-module", "localhost", grpcPort);
        
        // Module connector will create its own channel
        
        // Execute
        StepVerifier.create(moduleConnector.getServiceRegistration(moduleInfo))
                .assertNext(registration -> {
                    assertEquals("MockPipeStepProcessor", registration.moduleName());
                    assertNotNull(registration.jsonConfigSchema());
                    assertTrue(registration.jsonConfigSchema().contains("schema"));
                })
                .verifyComplete();
        
        // Cleanup
        moduleConnector.disconnectModule(moduleInfo.getServiceId());
    }
    
    @Test
    @DisplayName("Should handle connection failure")
    void testConnectionFailure() {
        // Setup with invalid port
        ModuleInfo moduleInfo = createModuleInfo("test-module", "localhost", 12345); // Invalid port
        PipeStream pipeStream = createTestPipeStream();
        
        // Execute
        StepVerifier.create(moduleConnector.processDocument(
                    moduleInfo, pipeStream, "test-step", Map.of()))
                .expectErrorMatches(throwable -> 
                    throwable instanceof RuntimeException &&
                    throwable.getMessage().contains("Failed to process document"))
                .verify();
    }
    
    @Test
    @DisplayName("Should handle timeout")
    void testProcessDocumentTimeout() {
        // Setup
        ModuleInfo moduleInfo = createModuleInfo("test-module", "localhost", grpcPort);
        PipeStream pipeStream = createTestPipeStream();
        
        // Module connector will create its own channel
        
        // Configure mock processor to delay response
        mockProcessor.setResponseDelay(Duration.ofSeconds(35)); // Longer than timeout
        
        // Execute - should timeout
        StepVerifier.create(moduleConnector.processDocument(
                    moduleInfo, pipeStream, "test-step", Map.of()))
                .expectErrorMatches(throwable -> 
                    throwable.getMessage().contains("Failed to process document"))
                .verify(Duration.ofSeconds(40));
        
        // Cleanup
        moduleConnector.disconnectModule(moduleInfo.getServiceId());
    }
    
    private ModuleInfo createModuleInfo(String serviceId, String host, int port) {
        return ModuleInfo.newBuilder()
                .setServiceId(serviceId)
                .setServiceName("Test Service")
                .setHost(host)
                .setPort(port)
                .build();
    }
    
    private PipeStream createTestPipeStream() {
        return PipeStream.newBuilder()
                .setStreamId("test-stream-123")
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-123")
                        .setTitle("Test Document")
                        .setBody("Test content")
                        .build())
                .setCurrentPipelineName("test-pipeline")
                .setCurrentHopNumber(0)
                .putContextParams("user", "test-user")
                .build();
    }
    
    /**
     * Mock gRPC service for testing.
     */
    static class MockPipeStepProcessor extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        private ProcessResponse response;
        private ProcessRequest lastRequest;
        private Duration responseDelay = Duration.ZERO;
        
        void setResponse(ProcessResponse response) {
            this.response = response;
        }
        
        void setResponseDelay(Duration delay) {
            this.responseDelay = delay;
        }
        
        ProcessRequest getLastRequest() {
            return lastRequest;
        }
        
        void reset() {
            this.response = null;
            this.lastRequest = null;
            this.responseDelay = Duration.ZERO;
        }
        
        @Override
        public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
            lastRequest = request;
            
            if (responseDelay.isZero()) {
                sendResponse(responseObserver);
            } else {
                // Simulate delay
                new Thread(() -> {
                    try {
                        Thread.sleep(responseDelay.toMillis());
                        sendResponse(responseObserver);
                    } catch (InterruptedException e) {
                        responseObserver.onError(e);
                    }
                }).start();
            }
        }
        
        private void sendResponse(StreamObserver<ProcessResponse> responseObserver) {
            if (response != null) {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } else {
                // Default response
                responseObserver.onNext(ProcessResponse.newBuilder()
                        .setSuccess(true)
                        .build());
                responseObserver.onCompleted();
            }
        }
        
        @Override
        public void getServiceRegistration(Empty request, 
                                          StreamObserver<ServiceRegistrationData> responseObserver) {
            ServiceRegistrationData registration = ServiceRegistrationData.newBuilder()
                    .setModuleName("MockPipeStepProcessor")
                    .setJsonConfigSchema("{\"type\": \"object\", \"properties\": {\"key\": {\"type\": \"string\"}}}")
                    .build();
            
            responseObserver.onNext(registration);
            responseObserver.onCompleted();
        }
    }
}