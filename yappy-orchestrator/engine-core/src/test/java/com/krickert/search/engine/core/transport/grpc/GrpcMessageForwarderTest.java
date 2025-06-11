package com.krickert.search.engine.core.transport.grpc;

import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.krickert.search.engine.core.routing.RouteData;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.kiwiproject.consul.model.health.ImmutableServiceHealth;
import org.kiwiproject.consul.model.health.Service;
import org.kiwiproject.consul.model.health.ImmutableService;
import org.kiwiproject.consul.model.health.Node;
import org.kiwiproject.consul.model.health.ImmutableNode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Disabled("Needs to be rewritten using proper Micronaut gRPC testing patterns")
class GrpcMessageForwarderTest {

    @Mock
    private BusinessOperationsService businessOpsService;
    
    private GrpcMessageForwarder forwarder;
    
    private Server testServer;
    private TestPipeStepProcessor testProcessor;
    private AutoCloseable mocks;
    
    @BeforeEach
    void setUp() throws IOException {
        mocks = MockitoAnnotations.openMocks(this);
        forwarder = new GrpcMessageForwarder(businessOpsService, "test-cluster", false);
        
        // Create test gRPC server
        testProcessor = new TestPipeStepProcessor();
        testServer = ServerBuilder.forPort(0) // Random port
                .addService(testProcessor)
                .build()
                .start();
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (testServer != null) {
            testServer.shutdown();
            testServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        forwarder.shutdown();
        mocks.close();
    }
    
    @Test
    void testForwardSuccess() {
        // Given
        String serviceName = "chunker-service";
        String clusterServiceName = "test-cluster-" + serviceName;
        String streamId = UUID.randomUUID().toString();
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-123")
                        .setTitle("Test Document")
                        .setBody("Test content")
                        .build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setCurrentHopNumber(1)
                .build();
        
        RouteData routeData = new RouteData(
                "target-pipeline",
                "chunker",
                serviceName,
                RouteData.TransportType.GRPC,
                streamId
        );
        
        // Mock service discovery
        ServiceHealth serviceHealth = createMockServiceHealth(
                "localhost", 
                testServer.getPort()
        );
        
        when(businessOpsService.getHealthyServiceInstances(clusterServiceName))
                .thenReturn(Mono.just(List.of(serviceHealth)));
        
        // Set expected response
        testProcessor.setResponse(ProcessResponse.newBuilder()
                .setSuccess(true)
                .build());
        
        // When
        Mono<Optional<PipeStream>> result = forwarder.forward(pipeStream, routeData);
        
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
        assertThat(receivedRequest.getMetadata().getCurrentHopNumber()).isEqualTo(2);
    }
    
    @Test
    void testForwardWithNullTargetPipeline() {
        // Given - RouteData with null target pipeline (use current pipeline)
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-456")
                        .setTitle("Test Document")
                        .build())
                .setCurrentPipelineName("current-pipeline")
                .setTargetStepName("test-step")
                .setCurrentHopNumber(1)
                .build();
        
        RouteData routeData = new RouteData(
                null, // null target pipeline
                "chunker",
                "chunker-service",
                RouteData.TransportType.GRPC,
                streamId
        );
        
        // Mock service discovery
        ServiceHealth serviceHealth = createMockServiceHealth(
                "localhost", 
                testServer.getPort()
        );
        
        when(businessOpsService.getHealthyServiceInstances("test-cluster-chunker-service"))
                .thenReturn(Mono.just(List.of(serviceHealth)));
        
        testProcessor.setResponse(ProcessResponse.newBuilder().setSuccess(true).build());
        
        // When
        Mono<Optional<PipeStream>> result = forwarder.forward(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        
        // Verify current pipeline was used
        ProcessRequest receivedRequest = testProcessor.getLastRequest();
        assertThat(receivedRequest.getMetadata().getPipelineName()).isEqualTo("current-pipeline");
    }
    
    @Test
    void testForwardServiceNotFound() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("doc-empty").build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .build();
        
        RouteData routeData = new RouteData(
                "pipeline",
                "step",
                "missing-service",
                RouteData.TransportType.GRPC,
                streamId
        );
        
        // Mock service discovery returning empty list
        when(businessOpsService.getHealthyServiceInstances("test-cluster-missing-service"))
                .thenReturn(Mono.just(Collections.emptyList()));
        
        // When
        Mono<Optional<PipeStream>> result = forwarder.forward(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> 
                    e instanceof IllegalStateException &&
                    e.getMessage().contains("No healthy instances found"))
                .verify();
    }
    
    @Test
    void testForwardServiceFailure() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("doc-fail").build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .build();
        
        RouteData routeData = new RouteData(
                "pipeline",
                "step",
                "failing-service",
                RouteData.TransportType.GRPC,
                streamId
        );
        
        // Mock service discovery
        ServiceHealth serviceHealth = createMockServiceHealth(
                "localhost", 
                testServer.getPort()
        );
        
        when(businessOpsService.getHealthyServiceInstances("test-cluster-failing-service"))
                .thenReturn(Mono.just(List.of(serviceHealth)));
        
        // Set failure response  
        testProcessor.setResponse(ProcessResponse.newBuilder()
                .setSuccess(false)
                .build());
        
        // When
        Mono<Optional<PipeStream>> result = forwarder.forward(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> 
                    e instanceof RuntimeException &&
                    e.getMessage().contains("failed to process message"))
                .verify();
    }
    
    @Test
    void testCanHandle() {
        assertThat(forwarder.canHandle(RouteData.TransportType.GRPC)).isTrue();
        assertThat(forwarder.canHandle(RouteData.TransportType.KAFKA)).isFalse();
        assertThat(forwarder.canHandle(RouteData.TransportType.INTERNAL)).isFalse();
    }
    
    @Test
    void testGetTransportType() {
        assertThat(forwarder.getTransportType()).isEqualTo(RouteData.TransportType.GRPC);
    }
    
    @Test
    void testChannelReuse() {
        // Given - two requests to the same service
        String serviceName = "chunker-service";
        String streamId1 = UUID.randomUUID().toString();
        String streamId2 = UUID.randomUUID().toString();
        
        PipeStream pipeStream1 = createTestPipeStream(streamId1);
        PipeStream pipeStream2 = createTestPipeStream(streamId2);
        
        RouteData routeData1 = new RouteData(
                "pipeline", "step", serviceName, RouteData.TransportType.GRPC, streamId1
        );
        RouteData routeData2 = new RouteData(
                "pipeline", "step", serviceName, RouteData.TransportType.GRPC, streamId2
        );
        
        // Mock service discovery
        ServiceHealth serviceHealth = createMockServiceHealth(
                "localhost", 
                testServer.getPort()
        );
        
        when(businessOpsService.getHealthyServiceInstances("test-cluster-" + serviceName))
                .thenReturn(Mono.just(List.of(serviceHealth)));
        
        testProcessor.setResponse(ProcessResponse.newBuilder().setSuccess(true).build());
        
        // When - send two messages
        StepVerifier.create(forwarder.forward(pipeStream1, routeData1))
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        
        StepVerifier.create(forwarder.forward(pipeStream2, routeData2))
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        
        // Then - verify both requests were received
        assertThat(testProcessor.getRequestCount()).isEqualTo(2);
    }
    
    private ServiceHealth createMockServiceHealth(String address, int port) {
        Service service = ImmutableService.builder()
                .id("test-service")
                .service("test-service")
                .address(address)
                .port(port)
                .build();
        
        Node node = ImmutableNode.builder()
                .node("test-node")
                .address(address)
                .build();
        
        return ImmutableServiceHealth.builder()
                .node(node)
                .service(service)
                .build();
    }
    
    private PipeStream createTestPipeStream(String streamId) {
        return PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-" + streamId)
                        .setTitle("Test Document")
                        .build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setCurrentHopNumber(1)
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