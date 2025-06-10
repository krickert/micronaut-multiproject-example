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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Simple unit test for routing functionality using mocks.
 * Demonstrates clean separation of concerns with interface abstractions.
 */
class SimpleRoutingTest {
    
    @Mock
    private MessageForwarder grpcForwarder;
    
    @Mock
    private MessageForwarder kafkaForwarder;
    
    @Mock
    private MessageForwarder internalForwarder;
    
    @Mock
    private RoutingStrategy routingStrategy;
    
    private DefaultRouter router;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Set up mock forwarders
        when(grpcForwarder.getTransportType()).thenReturn(RouteData.TransportType.GRPC);
        when(grpcForwarder.canHandle(RouteData.TransportType.GRPC)).thenReturn(true);
        
        when(kafkaForwarder.getTransportType()).thenReturn(RouteData.TransportType.KAFKA);
        when(kafkaForwarder.canHandle(RouteData.TransportType.KAFKA)).thenReturn(true);
        
        when(internalForwarder.getTransportType()).thenReturn(RouteData.TransportType.INTERNAL);
        when(internalForwarder.canHandle(RouteData.TransportType.INTERNAL)).thenReturn(true);
        
        // Create router with mock dependencies
        router = new DefaultRouter(
            List.of(grpcForwarder, kafkaForwarder, internalForwarder),
            routingStrategy
        );
    }
    
    @Test
    void shouldRouteToGrpcService() {
        // Given
        PipeStream pipeStream = createTestPipeStream("test-stream", "chunker");
        RouteData routeData = new RouteData(
            null, 
            "chunker", 
            "chunker-service", 
            RouteData.TransportType.GRPC, 
            "test-stream"
        );
        
        when(routingStrategy.determineRoute(pipeStream)).thenReturn(Mono.just(routeData));
        when(grpcForwarder.forward(pipeStream, routeData)).thenReturn(Mono.empty());
        
        // When & Then
        StepVerifier.create(router.route(pipeStream))
            .expectComplete()
            .verify();
    }
    
    @Test
    void shouldRouteToKafkaTopic() {
        // Given
        PipeStream pipeStream = createTestPipeStream("test-stream", "publish-step");
        RouteData routeData = new RouteData(
            null, 
            "publish-step", 
            "processed-documents", 
            RouteData.TransportType.KAFKA, 
            "test-stream"
        );
        
        when(routingStrategy.determineRoute(pipeStream)).thenReturn(Mono.just(routeData));
        when(kafkaForwarder.forward(pipeStream, routeData)).thenReturn(Mono.empty());
        
        // When & Then
        StepVerifier.create(router.route(pipeStream))
            .expectComplete()
            .verify();
    }
    
    @Test
    void shouldRouteInternally() {
        // Given
        PipeStream pipeStream = createTestPipeStream("test-stream", "internal-step");
        RouteData routeData = new RouteData(
            null, 
            "internal-step", 
            "internalProcessor", 
            RouteData.TransportType.INTERNAL, 
            "test-stream"
        );
        
        when(routingStrategy.determineRoute(pipeStream)).thenReturn(Mono.just(routeData));
        when(internalForwarder.forward(pipeStream, routeData)).thenReturn(Mono.empty());
        
        // When & Then
        StepVerifier.create(router.route(pipeStream))
            .expectComplete()
            .verify();
    }
    
    @Test
    void shouldHandleRoutingFailure() {
        // Given
        PipeStream pipeStream = createTestPipeStream("test-stream", "unknown-step");
        
        when(routingStrategy.determineRoute(pipeStream))
            .thenReturn(Mono.error(new IllegalArgumentException("Unknown step")));
        
        // When & Then
        StepVerifier.create(router.route(pipeStream))
            .expectError(IllegalArgumentException.class)
            .verify();
    }
    
    @Test
    void shouldHandleUnsupportedTransportType() {
        // Given
        PipeStream pipeStream = createTestPipeStream("test-stream", "test-step");
        RouteData routeData = new RouteData(
            null, 
            "test-step", 
            "some-service", 
            RouteData.TransportType.GRPC, 
            "test-stream"
        );
        
        // Create router without GRPC forwarder
        DefaultRouter limitedRouter = new DefaultRouter(
            List.of(kafkaForwarder, internalForwarder),
            routingStrategy
        );
        
        when(routingStrategy.determineRoute(pipeStream)).thenReturn(Mono.just(routeData));
        
        // When & Then
        StepVerifier.create(limitedRouter.route(pipeStream, routeData))
            .expectError(IllegalStateException.class)
            .verify();
    }
    
    private PipeStream createTestPipeStream(String streamId, String targetStep) {
        PipeDoc doc = PipeDoc.newBuilder()
            .setId("test-doc")
            .setBody("Test content")
            .build();
            
        return PipeStream.newBuilder()
            .setStreamId(streamId)
            .setDocument(doc)
            .setCurrentPipelineName("test-pipeline")
            .setTargetStepName(targetStep)
            .setCurrentHopNumber(0)
            .build();
    }
}