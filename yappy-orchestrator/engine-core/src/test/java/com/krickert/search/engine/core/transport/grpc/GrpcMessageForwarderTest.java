package com.krickert.search.engine.core.transport.grpc;

import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.krickert.search.engine.core.TestClusterHelper;
import com.krickert.search.engine.core.routing.RouteData;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for GrpcMessageForwarder using Micronaut's testing framework.
 * Demonstrates proper integration testing with gRPC services.
 */
@MicronautTest
@Property(name = "grpc.channels.default.plaintext", value = "true")
@Property(name = "grpc.channels.default.negotiationType", value = "plaintext")
@Property(name = "engine.test-mode", value = "true") // Enable test mode to use getServiceInstances instead of getHealthyServiceInstances
class GrpcMessageForwarderTest {
    
    private static final Logger logger = LoggerFactory.getLogger(GrpcMessageForwarderTest.class);

    @Inject
    private BusinessOperationsService businessOpsService;
    
    @Inject
    private TestClusterHelper testClusterHelper;
    
    private Server testServer;
    private TestPipeStepProcessor testProcessor;
    private int grpcPort;
    private String testClusterName;
    
    @BeforeEach
    void setUp() throws IOException {
        // Create unique test cluster
        testClusterName = testClusterHelper.createTestCluster("grpc-forwarder-test");
        logger.info("Created test cluster: {}", testClusterName);
        
        // Find available port
        try (ServerSocket socket = new ServerSocket(0)) {
            grpcPort = socket.getLocalPort();
        }
        
        // Create test gRPC server
        testProcessor = new TestPipeStepProcessor();
        testServer = ServerBuilder.forPort(grpcPort)
                .addService(testProcessor)
                .build()
                .start();
        logger.info("Started test gRPC server on port {}", grpcPort);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (testServer != null) {
            testServer.shutdown();
            testServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        // Clean up test cluster
        if (testClusterName != null) {
            testClusterHelper.cleanupTestCluster(testClusterName)
                    .block(java.time.Duration.ofSeconds(5));
        }
    }
    
    @Test
    void testForwardSuccess() {
        // Given
        String serviceName = "chunker-service";
        String serviceId = UUID.randomUUID().toString();
        String streamId = UUID.randomUUID().toString();
        
        // Register the test service in Consul
        testClusterHelper.registerServiceInCluster(
                testClusterName,
                serviceName,
                serviceId,
                "localhost",
                grpcPort
        ).block(java.time.Duration.ofSeconds(5));
        
        // Wait a bit for registration to propagate
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-123")
                        .setTitle("Test Document")
                        .setBody("Test content")
                        .build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("chunker")
                .build();
        
        RouteData routeData = new RouteData(
                "target-pipeline",
                "chunker",
                serviceName,
                RouteData.TransportType.GRPC,
                streamId
        );
        
        // Set expected response
        testProcessor.setResponse(ProcessResponse.newBuilder()
                .setSuccess(true)
                .build());
        
        // When - Update forwarder to use our test cluster
        GrpcMessageForwarder testForwarder = new GrpcMessageForwarder(
                businessOpsService,
                testClusterName,
                true // test mode
        );
        
        Mono<Optional<PipeStream>> result = testForwarder.forward(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        
        // Verify the request was received
        ProcessRequest receivedRequest = testProcessor.getLastRequest();
        assertThat(receivedRequest).isNotNull();
        assertThat(receivedRequest.getDocument().getTitle()).isEqualTo("Test Document");
        assertThat(receivedRequest.getMetadata().getPipelineName()).isEqualTo("target-pipeline");
        assertThat(receivedRequest.getMetadata().getPipeStepName()).isEqualTo("chunker");
        assertThat(receivedRequest.getMetadata().getStreamId()).isEqualTo(streamId);
        
        testForwarder.shutdown();
    }
    
    @Test
    void testForwardWithNullTargetPipeline() {
        // Given - RouteData with null target pipeline (use current pipeline)
        String serviceName = "chunker-service";
        String serviceId = UUID.randomUUID().toString();
        String streamId = UUID.randomUUID().toString();
        
        // Register the test service
        testClusterHelper.registerServiceInCluster(
                testClusterName,
                serviceName,
                serviceId,
                "localhost",
                grpcPort
        ).block(java.time.Duration.ofSeconds(5));
        
        // Wait for registration
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-456")
                        .setTitle("Test Document")
                        .build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("chunker")
                .build();
        
        RouteData routeData = new RouteData(
                null, // null target pipeline
                "chunker",
                serviceName,
                RouteData.TransportType.GRPC,
                streamId
        );
        
        testProcessor.setResponse(ProcessResponse.newBuilder().setSuccess(true).build());
        
        // When
        GrpcMessageForwarder testForwarder = new GrpcMessageForwarder(
                businessOpsService,
                testClusterName,
                true
        );
        
        Mono<Optional<PipeStream>> result = testForwarder.forward(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        
        // Verify request was received
        ProcessRequest receivedRequest = testProcessor.getLastRequest();
        assertThat(receivedRequest).isNotNull();
        assertThat(receivedRequest.getDocument().getTitle()).isEqualTo("Test Document");
        
        testForwarder.shutdown();
    }
    
    @Test
    void testForwardServiceNotFound() {
        // Given - no service registered
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("doc-empty").build())
                .setCurrentPipelineName("pipeline")
                .setTargetStepName("step")
                .build();
        
        RouteData routeData = new RouteData(
                "pipeline",
                "step",
                "missing-service",
                RouteData.TransportType.GRPC,
                streamId
        );
        
        // When - no service is registered, should fail
        GrpcMessageForwarder testForwarder = new GrpcMessageForwarder(
                businessOpsService,
                testClusterName,
                true
        );
        
        Mono<Optional<PipeStream>> result = testForwarder.forward(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> 
                    e instanceof IllegalStateException &&
                    e.getMessage().contains("No registered instances found"))
                .verify();
        
        testForwarder.shutdown();
    }
    
    @Test
    void testForwardServiceFailure() {
        // Given
        String serviceName = "failing-service";
        String serviceId = UUID.randomUUID().toString();
        String streamId = UUID.randomUUID().toString();
        
        // Register the test service
        testClusterHelper.registerServiceInCluster(
                testClusterName,
                serviceName,
                serviceId,
                "localhost",
                grpcPort
        ).block(java.time.Duration.ofSeconds(5));
        
        // Wait for registration
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("doc-fail").build())
                .setCurrentPipelineName("pipeline")
                .setTargetStepName("step")
                .build();
        
        RouteData routeData = new RouteData(
                "pipeline",
                "step",
                serviceName,
                RouteData.TransportType.GRPC,
                streamId
        );
        
        // Set failure response  
        testProcessor.setResponse(ProcessResponse.newBuilder()
                .setSuccess(false)
                .build());
        
        // When
        GrpcMessageForwarder testForwarder = new GrpcMessageForwarder(
                businessOpsService,
                testClusterName,
                true
        );
        
        Mono<Optional<PipeStream>> result = testForwarder.forward(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> 
                    e instanceof RuntimeException &&
                    e.getMessage().contains("failed to process message"))
                .verify();
        
        testForwarder.shutdown();
    }
    
    @Test
    void testCanHandle() {
        GrpcMessageForwarder forwarder = new GrpcMessageForwarder(
                businessOpsService,
                testClusterName,
                true
        );
        assertThat(forwarder.canHandle(RouteData.TransportType.GRPC)).isTrue();
        assertThat(forwarder.canHandle(RouteData.TransportType.KAFKA)).isFalse();
        assertThat(forwarder.canHandle(RouteData.TransportType.INTERNAL)).isFalse();
        forwarder.shutdown();
    }
    
    @Test
    void testGetTransportType() {
        GrpcMessageForwarder forwarder = new GrpcMessageForwarder(
                businessOpsService,
                testClusterName,
                true
        );
        assertThat(forwarder.getTransportType()).isEqualTo(RouteData.TransportType.GRPC);
        forwarder.shutdown();
    }
    
    @Test
    void testChannelReuse() {
        // Given - two requests to the same service
        String serviceName = "chunker-service";
        String serviceId = UUID.randomUUID().toString();
        String streamId1 = UUID.randomUUID().toString();
        String streamId2 = UUID.randomUUID().toString();
        
        // Register the test service
        testClusterHelper.registerServiceInCluster(
                testClusterName,
                serviceName,
                serviceId,
                "localhost",
                grpcPort
        ).block(java.time.Duration.ofSeconds(5));
        
        // Wait for registration
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        
        PipeStream pipeStream1 = createTestPipeStream(streamId1);
        PipeStream pipeStream2 = createTestPipeStream(streamId2);
        
        RouteData routeData1 = new RouteData(
                "pipeline", "step", serviceName, RouteData.TransportType.GRPC, streamId1
        );
        RouteData routeData2 = new RouteData(
                "pipeline", "step", serviceName, RouteData.TransportType.GRPC, streamId2
        );
        
        testProcessor.setResponse(ProcessResponse.newBuilder().setSuccess(true).build());
        
        // When - send two messages using same forwarder
        GrpcMessageForwarder testForwarder = new GrpcMessageForwarder(
                businessOpsService,
                testClusterName,
                true
        );
        
        StepVerifier.create(testForwarder.forward(pipeStream1, routeData1))
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        
        StepVerifier.create(testForwarder.forward(pipeStream2, routeData2))
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        
        // Then - verify both requests were received
        assertThat(testProcessor.getRequestCount()).isEqualTo(2);
        
        testForwarder.shutdown();
    }
    
    private PipeStream createTestPipeStream(String streamId) {
        return PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-" + streamId)
                        .setTitle("Test Document")
                        .build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("chunker")
                .build();
    }
    
    /**
     * Test gRPC service implementation
     */
    private static class TestPipeStepProcessor extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        private final AtomicReference<ProcessRequest> lastRequest = new AtomicReference<>();
        private ProcessResponse response = ProcessResponse.newBuilder().setSuccess(true).build();
        private int requestCount = 0;
        
        @Override
        public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
            lastRequest.set(request);
            requestCount++;
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
        
        public void setResponse(ProcessResponse response) {
            this.response = response;
        }
        
        public ProcessRequest getLastRequest() {
            return lastRequest.get();
        }
        
        public int getRequestCount() {
            return requestCount;
        }
    }
}