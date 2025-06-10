package com.krickert.search.engine.core;

import com.krickert.search.engine.core.routing.RouteData;
import com.krickert.search.engine.core.routing.Router;
import com.krickert.search.engine.core.routing.RoutingStrategy;
import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test that demonstrates multi-step pipeline processing without containers.
 * Shows how Router handles different transport types in a pipeline flow.
 */
class MultiStepPipelineTest {
    
    @Mock
    private MessageForwarder grpcForwarder;
    
    @Mock
    private MessageForwarder kafkaForwarder;
    
    @Mock
    private MessageForwarder internalForwarder;
    
    @Mock
    private RoutingStrategy routingStrategy;
    
    private Router router;
    private PipelineEngine pipelineEngine;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Set up mock forwarders
        when(grpcForwarder.getTransportType()).thenReturn(RouteData.TransportType.GRPC);
        when(grpcForwarder.canHandle(RouteData.TransportType.GRPC)).thenReturn(true);
        when(grpcForwarder.forward(any(), any())).thenReturn(Mono.empty());
        
        when(kafkaForwarder.getTransportType()).thenReturn(RouteData.TransportType.KAFKA);
        when(kafkaForwarder.canHandle(RouteData.TransportType.KAFKA)).thenReturn(true);
        when(kafkaForwarder.forward(any(), any())).thenReturn(Mono.empty());
        
        when(internalForwarder.getTransportType()).thenReturn(RouteData.TransportType.INTERNAL);
        when(internalForwarder.canHandle(RouteData.TransportType.INTERNAL)).thenReturn(true);
        when(internalForwarder.forward(any(), any())).thenReturn(Mono.empty());
        
        // Create router with mock dependencies
        router = new com.krickert.search.engine.core.routing.DefaultRouter(
            List.of(grpcForwarder, kafkaForwarder, internalForwarder),
            routingStrategy
        );
        
        // Create pipeline engine - we'll mock the services it needs
        pipelineEngine = new PipelineEngineImpl(
            mock(com.krickert.search.config.consul.service.BusinessOperationsService.class),
            router,
            "test-cluster",
            false, // No buffering for tests
            100,
            3,
            0.0
        );
    }
    
    @Test
    void shouldDemonstrateMultiStepPipeline() {
        // Given: A document that will go through tika -> chunker -> embedder
        PipeDoc document = PipeDoc.newBuilder()
            .setId("test-doc-001")
            .setTitle("Multi-Step Test")
            .setBody("This document will be processed through multiple steps: parsing, chunking, and embedding.")
            .setSourceMimeType("text/plain")
            .build();
        
        // Step 1: Start with tika-parser (gRPC)
        PipeStream tikaInput = PipeStream.newBuilder()
            .setStreamId("stream-001")
            .setDocument(document)
            .setCurrentPipelineName("multi-step-pipeline")
            .setTargetStepName("tika-parser")
            .setCurrentHopNumber(0)
            .build();
        
        RouteData tikaRoute = new RouteData(
            null, 
            "tika-parser", 
            "tika-parser", 
            RouteData.TransportType.GRPC, 
            "stream-001"
        );
        
        when(routingStrategy.determineRoute(tikaInput)).thenReturn(Mono.just(tikaRoute));
        
        // When: Process the message
        StepVerifier.create(pipelineEngine.processMessage(tikaInput))
            .expectComplete()
            .verify();
        
        // Then: Verify tika-parser was called via gRPC
        verify(grpcForwarder).forward(eq(tikaInput), eq(tikaRoute));
        verify(kafkaForwarder, never()).forward(any(), any());
        verify(internalForwarder, never()).forward(any(), any());
    }
    
    @Test
    void shouldRouteToKafkaForPublishing() {
        // Given: A processed document ready for publishing to Kafka
        PipeDoc processedDoc = PipeDoc.newBuilder()
            .setId("processed-doc-001")
            .setTitle("Processed Document")
            .setBody("This document has been processed and is ready for publishing.")
            .build();
        
        PipeStream kafkaInput = PipeStream.newBuilder()
            .setStreamId("stream-002")
            .setDocument(processedDoc)
            .setCurrentPipelineName("publish-pipeline")
            .setTargetStepName("publish-step")
            .setCurrentHopNumber(3)
            .build();
        
        RouteData kafkaRoute = new RouteData(
            null, 
            "publish-step", 
            "processed-documents-topic", 
            RouteData.TransportType.KAFKA, 
            "stream-002"
        );
        
        when(routingStrategy.determineRoute(kafkaInput)).thenReturn(Mono.just(kafkaRoute));
        
        // When: Process the message
        StepVerifier.create(pipelineEngine.processMessage(kafkaInput))
            .expectComplete()
            .verify();
        
        // Then: Verify message was routed to Kafka
        verify(kafkaForwarder).forward(eq(kafkaInput), eq(kafkaRoute));
        verify(grpcForwarder, never()).forward(any(), any());
        verify(internalForwarder, never()).forward(any(), any());
    }
    
    @Test
    void shouldHandleInternalProcessing() {
        // Given: A document that needs internal processing
        PipeDoc document = PipeDoc.newBuilder()
            .setId("internal-doc-001")
            .setTitle("Internal Processing Test")
            .setBody("This document will be processed internally.")
            .build();
        
        PipeStream internalInput = PipeStream.newBuilder()
            .setStreamId("stream-003")
            .setDocument(document)
            .setCurrentPipelineName("internal-pipeline")
            .setTargetStepName("internal-processor")
            .setCurrentHopNumber(1)
            .build();
        
        RouteData internalRoute = new RouteData(
            null, 
            "internal-processor", 
            "textNormalizerBean", 
            RouteData.TransportType.INTERNAL, 
            "stream-003"
        );
        
        when(routingStrategy.determineRoute(internalInput)).thenReturn(Mono.just(internalRoute));
        
        // When: Process the message
        StepVerifier.create(pipelineEngine.processMessage(internalInput))
            .expectComplete()
            .verify();
        
        // Then: Verify internal forwarder was used
        verify(internalForwarder).forward(eq(internalInput), eq(internalRoute));
        verify(grpcForwarder, never()).forward(any(), any());
        verify(kafkaForwarder, never()).forward(any(), any());
    }
    
    @Test
    void shouldVerifyCorrectTransportTypeSelection() {
        // Given: Three different steps requiring different transports
        PipeDoc document = PipeDoc.newBuilder()
            .setId("transport-test-001")
            .setTitle("Transport Selection Test")
            .setBody("Testing transport type selection.")
            .build();
        
        // Test each transport type
        RouteData[] routes = {
            new RouteData(null, "grpc-step", "grpc-service", RouteData.TransportType.GRPC, "stream-001"),
            new RouteData(null, "kafka-step", "kafka-topic", RouteData.TransportType.KAFKA, "stream-002"),
            new RouteData(null, "internal-step", "internal-bean", RouteData.TransportType.INTERNAL, "stream-003")
        };
        
        for (RouteData route : routes) {
            PipeStream input = PipeStream.newBuilder()
                .setStreamId(route.streamId())
                .setDocument(document)
                .setCurrentPipelineName("transport-test-pipeline")
                .setTargetStepName(route.targetStepName())
                .setCurrentHopNumber(0)
                .build();
            
            when(routingStrategy.determineRoute(input)).thenReturn(Mono.just(route));
            
            // When: Process each message
            StepVerifier.create(pipelineEngine.processMessage(input))
                .expectComplete()
                .verify();
        }
        
        // Then: Verify each transport type was used exactly once
        verify(grpcForwarder, times(1)).forward(any(), any());
        verify(kafkaForwarder, times(1)).forward(any(), any());
        verify(internalForwarder, times(1)).forward(any(), any());
    }
    
    @Test
    void shouldCaptureCorrectRouteDataForGrpcStep() {
        // Given: A step that should route to gRPC service
        PipeDoc document = PipeDoc.newBuilder()
            .setId("capture-test-001")
            .setBody("Test document for route data capture")
            .build();
        
        PipeStream input = PipeStream.newBuilder()
            .setStreamId("capture-stream-001")
            .setDocument(document)
            .setCurrentPipelineName("capture-test-pipeline")
            .setTargetStepName("chunker")
            .setCurrentHopNumber(1)
            .build();
        
        RouteData expectedRoute = new RouteData(
            null, 
            "chunker", 
            "chunker-service", 
            RouteData.TransportType.GRPC, 
            "capture-stream-001"
        );
        
        when(routingStrategy.determineRoute(input)).thenReturn(Mono.just(expectedRoute));
        
        // When: Process the message
        StepVerifier.create(pipelineEngine.processMessage(input))
            .expectComplete()
            .verify();
        
        // Then: Capture and verify the exact route data passed to forwarder
        ArgumentCaptor<RouteData> routeCaptor = ArgumentCaptor.forClass(RouteData.class);
        verify(grpcForwarder).forward(eq(input), routeCaptor.capture());
        
        RouteData capturedRoute = routeCaptor.getValue();
        assertThat(capturedRoute.targetStepName()).isEqualTo("chunker");
        assertThat(capturedRoute.destinationService()).isEqualTo("chunker-service");
        assertThat(capturedRoute.transportType()).isEqualTo(RouteData.TransportType.GRPC);
        assertThat(capturedRoute.streamId()).isEqualTo("capture-stream-001");
    }
    
    @Test
    void shouldHandleEmptyTargetStepGracefully() {
        // Given: A PipeStream with no target step (pipeline completion scenario)
        PipeDoc document = PipeDoc.newBuilder()
            .setId("completion-test-001")
            .setBody("Test document for pipeline completion")
            .build();
        
        PipeStream input = PipeStream.newBuilder()
            .setStreamId("completion-stream-001")
            .setDocument(document)
            .setCurrentPipelineName("test-pipeline")
            .setTargetStepName("") // Empty target step
            .setCurrentHopNumber(3)
            .build();
        
        // When: Process the message
        StepVerifier.create(pipelineEngine.processMessage(input))
            .expectComplete()
            .verify();
        
        // Then: No forwarders should be called, pipeline should complete
        verify(grpcForwarder, never()).forward(any(), any());
        verify(kafkaForwarder, never()).forward(any(), any());
        verify(internalForwarder, never()).forward(any(), any());
        verify(routingStrategy, never()).determineRoute(any());
    }
}