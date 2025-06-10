package com.krickert.search.engine.core.routing;

import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultRouterTest {
    
    @Mock
    private MessageForwarder grpcForwarder;
    
    @Mock
    private MessageForwarder kafkaForwarder;
    
    @Mock
    private MessageForwarder internalForwarder;
    
    @Mock
    private RoutingStrategy routingStrategy;
    
    private DefaultRouter router;
    private AutoCloseable mocks;
    
    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        
        // Configure mock forwarders
        when(grpcForwarder.getTransportType()).thenReturn(RouteData.TransportType.GRPC);
        when(grpcForwarder.canHandle(RouteData.TransportType.GRPC)).thenReturn(true);
        
        when(kafkaForwarder.getTransportType()).thenReturn(RouteData.TransportType.KAFKA);
        when(kafkaForwarder.canHandle(RouteData.TransportType.KAFKA)).thenReturn(true);
        
        when(internalForwarder.getTransportType()).thenReturn(RouteData.TransportType.INTERNAL);
        when(internalForwarder.canHandle(RouteData.TransportType.INTERNAL)).thenReturn(true);
        
        List<MessageForwarder> forwarders = Arrays.asList(grpcForwarder, kafkaForwarder, internalForwarder);
        
        router = new DefaultRouter(forwarders, routingStrategy);
    }
    
    @Test
    void testRouteWithGrpc() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        RouteData routeData = new RouteData(
                "target-pipeline",
                "chunker",
                "chunker-service",
                RouteData.TransportType.GRPC,
                streamId
        );
        
        when(grpcForwarder.forward(eq(pipeStream), eq(routeData)))
                .thenReturn(Mono.empty());
        
        // When
        Mono<Void> result = router.route(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .verifyComplete();
        
        verify(grpcForwarder).forward(pipeStream, routeData);
        verify(kafkaForwarder, never()).forward(any(), any());
        verify(internalForwarder, never()).forward(any(), any());
    }
    
    @Test
    void testRouteWithKafka() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        RouteData routeData = new RouteData(
                "pipeline",
                "embedder",
                "embedder-topic",
                RouteData.TransportType.KAFKA,
                streamId
        );
        
        when(kafkaForwarder.forward(eq(pipeStream), eq(routeData)))
                .thenReturn(Mono.empty());
        
        // When
        Mono<Void> result = router.route(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .verifyComplete();
        
        verify(kafkaForwarder).forward(pipeStream, routeData);
        verify(grpcForwarder, never()).forward(any(), any());
        verify(internalForwarder, never()).forward(any(), any());
    }
    
    @Test
    void testRouteWithInternal() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        RouteData routeData = new RouteData(
                null, // Same pipeline
                "internal-step",
                "internal",
                RouteData.TransportType.INTERNAL,
                streamId
        );
        
        when(internalForwarder.forward(eq(pipeStream), eq(routeData)))
                .thenReturn(Mono.empty());
        
        // When
        Mono<Void> result = router.route(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .verifyComplete();
        
        verify(internalForwarder).forward(pipeStream, routeData);
        verify(grpcForwarder, never()).forward(any(), any());
        verify(kafkaForwarder, never()).forward(any(), any());
    }
    
    @Test
    void testRouteWithNoForwarderAvailable() {
        // Given - router with only gRPC forwarder
        router = new DefaultRouter(List.of(grpcForwarder), routingStrategy);
        
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        RouteData routeData = new RouteData(
                "pipeline",
                "step",
                "kafka-topic",
                RouteData.TransportType.KAFKA, // No Kafka forwarder available
                streamId
        );
        
        // When
        Mono<Void> result = router.route(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> 
                    e instanceof IllegalStateException &&
                    e.getMessage().contains("No forwarder available for transport type: KAFKA"))
                .verify();
    }
    
    @Test
    void testRouteWithForwarderError() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        RouteData routeData = new RouteData(
                "pipeline",
                "step",
                "service",
                RouteData.TransportType.GRPC,
                streamId
        );
        
        when(grpcForwarder.forward(eq(pipeStream), eq(routeData)))
                .thenReturn(Mono.error(new RuntimeException("Connection failed")));
        
        // When
        Mono<Void> result = router.route(pipeStream, routeData);
        
        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> 
                    e instanceof RuntimeException &&
                    e.getMessage().equals("Connection failed"))
                .verify();
    }
    
    @Test
    void testRouteWithStrategy() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        RouteData routeData = new RouteData(
                "pipeline",
                "step",
                "service",
                RouteData.TransportType.GRPC,
                streamId
        );
        
        when(routingStrategy.determineRoute(pipeStream))
                .thenReturn(Mono.just(routeData));
        
        when(grpcForwarder.forward(eq(pipeStream), eq(routeData)))
                .thenReturn(Mono.empty());
        
        // When
        Mono<Void> result = router.route(pipeStream);
        
        // Then
        StepVerifier.create(result)
                .verifyComplete();
        
        verify(routingStrategy).determineRoute(pipeStream);
        verify(grpcForwarder).forward(pipeStream, routeData);
    }
    
    @Test
    void testRouteWithStrategyError() {
        // Given
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        
        when(routingStrategy.determineRoute(pipeStream))
                .thenReturn(Mono.error(new IllegalStateException("No route found")));
        
        // When
        Mono<Void> result = router.route(pipeStream);
        
        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> 
                    e instanceof IllegalStateException &&
                    e.getMessage().equals("No route found"))
                .verify();
    }
    
    @Test
    void testConstructorWithDuplicateTransportTypes() {
        // Given - two forwarders for the same transport type
        MessageForwarder grpcForwarder2 = mock(MessageForwarder.class);
        when(grpcForwarder2.getTransportType()).thenReturn(RouteData.TransportType.GRPC);
        
        List<MessageForwarder> forwarders = Arrays.asList(grpcForwarder, grpcForwarder2);
        
        // When/Then - should use the last one in the list
        router = new DefaultRouter(forwarders, routingStrategy);
        
        // The constructor should complete without error
        // In a real scenario, you might want to log a warning about duplicate forwarders
    }
    
    @Test
    void testMultipleForwardersInitialization() {
        // This test verifies the router initializes correctly with multiple forwarders
        assertThat(router).isNotNull();
        
        // Verify all transport types are supported
        String streamId = UUID.randomUUID().toString();
        PipeStream pipeStream = createTestPipeStream(streamId);
        
        // Test each transport type
        for (RouteData.TransportType transportType : RouteData.TransportType.values()) {
            RouteData routeData = new RouteData(
                    "pipeline",
                    "step",
                    "destination",
                    transportType,
                    streamId
            );
            
            // Mock the appropriate forwarder
            MessageForwarder forwarder = switch (transportType) {
                case GRPC -> grpcForwarder;
                case KAFKA -> kafkaForwarder;
                case INTERNAL -> internalForwarder;
            };
            
            when(forwarder.forward(any(), any())).thenReturn(Mono.empty());
            
            StepVerifier.create(router.route(pipeStream, routeData))
                    .verifyComplete();
        }
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
}