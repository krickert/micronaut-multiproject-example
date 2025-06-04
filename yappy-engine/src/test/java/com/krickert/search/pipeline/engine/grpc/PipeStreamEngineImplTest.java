package com.krickert.search.pipeline.engine.grpc;// In PipeStreamEngineImplTest.java

import com.google.protobuf.Empty;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine; // Your core engine interface
import com.krickert.search.pipeline.engine.grpc.PipeStreamEngineImpl;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.status.ServiceStatusAggregator;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Provider; // 👈 Make sure this import is present
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipeStreamEngineImplTest {

    @Mock
    private PipeStreamEngine mockCoreEngine; // This remains your mock for the core logic
    
    @Mock
    private DynamicConfigurationManager mockDynamicConfigManager;
    
    @Mock
    private GrpcChannelManager mockGrpcChannelManager;
    
    @Mock
    private ServiceStatusAggregator mockServiceStatusAggregator;
    
    @Mock
    private PipeStepExecutorFactory mockExecutorFactory;

    @Mock
    private StreamObserver<Empty> mockProcessPipeAsyncResponseObserver;
    @Mock
    private StreamObserver<PipeStream> mockTestPipeStreamResponseObserver;

    @Captor
    private ArgumentCaptor<PipeStream> pipeStreamCaptor;
    @Captor
    private ArgumentCaptor<IllegalArgumentException> illegalArgumentExceptionCaptor;
    @Captor
    private ArgumentCaptor<StatusRuntimeException> statusRuntimeExceptionCaptor;
    
    @Captor
    private ArgumentCaptor<PipeStream> streamCaptor;

    private PipeStreamEngineImpl grpcService; // SUT

    @BeforeEach
    void setUp() {
        // Create a Provider that returns your mockCoreEngine
        // The lambda () -> mockCoreEngine is a concise way to implement Provider.get()
        Provider<PipeStreamEngine> mockCoreEngineProvider = () -> mockCoreEngine;

        // Instantiate the SUT (grpcService) with all required dependencies
        grpcService = new PipeStreamEngineImpl(
                mockCoreEngineProvider,
                mockDynamicConfigManager,
                mockGrpcChannelManager,
                mockServiceStatusAggregator,
                mockExecutorFactory
        );
    }

    // ... createBasicPipeStream method remains the same ...
    private PipeStream createBasicPipeStream(String targetStepName) {
        PipeStream.Builder builder = PipeStream.newBuilder()
                .setStreamId("test-stream-" + UUID.randomUUID().toString().substring(0, 8))
                .setCurrentPipelineName("test-pipeline")
                .setCurrentHopNumber(0);
        if (targetStepName != null) {
            builder.setTargetStepName(targetStepName);
        } else {
            builder.setTargetStepName("");
        }
        return builder.build();
    }


    @Test
    @DisplayName("processPipeAsync should delegate to coreEngine and complete successfully")
    void processPipeAsync_delegatesToCoreEngine_andCompletes() {
        PipeStream request = createBasicPipeStream("someStep");
        // mockCoreEngine is what coreEngineProvider.get() will return in the SUT
        doNothing().when(mockCoreEngine).processStream(any(PipeStream.class));

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        // Verify interactions on mockCoreEngine
        verify(mockCoreEngine).processStream(pipeStreamCaptor.capture());
        assertEquals(request.getStreamId(), pipeStreamCaptor.getValue().getStreamId());
        assertEquals("someStep", pipeStreamCaptor.getValue().getTargetStepName());

        verify(mockProcessPipeAsyncResponseObserver).onNext(Empty.getDefaultInstance());
        verify(mockProcessPipeAsyncResponseObserver).onCompleted();
        verify(mockProcessPipeAsyncResponseObserver, never()).onError(any());
    }

    @Test
    @DisplayName("processPipeAsync should handle empty targetStepName and call onError")
    void processPipeAsync_emptyTargetStep_callsOnError() {
        PipeStream request = createBasicPipeStream("");

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        verify(mockCoreEngine, never()).processStream(any());

        verify(mockProcessPipeAsyncResponseObserver, never()).onNext(any());
        verify(mockProcessPipeAsyncResponseObserver, never()).onCompleted();
        verify(mockProcessPipeAsyncResponseObserver).onError(illegalArgumentExceptionCaptor.capture());

        IllegalArgumentException exception = illegalArgumentExceptionCaptor.getValue();
        assertNotNull(exception, "Exception should not be null");
        assertEquals("Target step name must be set in the request", exception.getMessage());
    }

    @Test
    @DisplayName("processPipeAsync should handle exception from coreEngine and call onError")
    void processPipeAsync_coreEngineThrowsException_callsOnError() {
        PipeStream request = createBasicPipeStream("someStep");
        RuntimeException coreException = new RuntimeException("Core engine failure");
        // mockCoreEngine is what coreEngineProvider.get() will return in the SUT
        doThrow(coreException).when(mockCoreEngine).processStream(any(PipeStream.class));

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        verify(mockCoreEngine).processStream(request);

        verify(mockProcessPipeAsyncResponseObserver, never()).onNext(any());
        verify(mockProcessPipeAsyncResponseObserver, never()).onCompleted();
        verify(mockProcessPipeAsyncResponseObserver).onError(statusRuntimeExceptionCaptor.capture());

        StatusRuntimeException exception = statusRuntimeExceptionCaptor.getValue();
        assertEquals(Status.Code.INTERNAL, exception.getStatus().getCode());
        assertNotNull(exception.getStatus().getDescription());
        assertEquals("Failed to process pipe: Core engine failure",exception.getStatus().getDescription());
        // assertEquals(coreException, exception.getStatus().getCause()); // This assertion is good too
    }

    @Test
    @DisplayName("testPipeStream should execute step and return result with routing info")
    void testPipeStream_executesStepAndReturnsResult() throws Exception {
        // Given
        PipeStream request = createBasicPipeStream("testStep");
        PipeStream processedStream = request.toBuilder()
                .addHistory(com.krickert.search.model.StepExecutionRecord.newBuilder()
                        .setHopNumber(1)
                        .setStepName("testStep")
                        .setStatus("SUCCESS")
                        .build())
                .build();
        
        // Mock executor
        com.krickert.search.pipeline.step.PipeStepExecutor mockExecutor = mock(com.krickert.search.pipeline.step.PipeStepExecutor.class);
        when(mockExecutorFactory.getExecutor("test-pipeline", "testStep")).thenReturn(mockExecutor);
        when(mockExecutor.execute(any())).thenReturn(processedStream);
        
        // Mock configuration for routing calculation
        com.krickert.search.config.pipeline.model.PipelineConfig pipelineConfig = 
                new com.krickert.search.config.pipeline.model.PipelineConfig(
                        "test-pipeline",
                        java.util.Map.of("testStep", com.krickert.search.config.pipeline.model.PipelineStepConfig.builder()
                                .stepName("testStep")
                                .stepType(com.krickert.search.config.pipeline.model.StepType.PIPELINE)
                                .processorInfo(com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo.builder()
                                        .grpcServiceName("test-service")
                                        .build())
                                .outputs(java.util.Map.of("output1", 
                                        com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget.builder()
                                                .targetStepName("nextStep")
                                                .transportType(com.krickert.search.config.pipeline.model.TransportType.KAFKA)
                                                .kafkaTransport(com.krickert.search.config.pipeline.model.KafkaTransportConfig.builder()
                                                        .topic("test-topic")
                                                        .build())
                                                .build()))
                                .build())
                );
        when(mockDynamicConfigManager.getPipelineConfig("test-pipeline"))
                .thenReturn(java.util.Optional.of(pipelineConfig));

        // When
        grpcService.testPipeStream(request, mockTestPipeStreamResponseObserver);

        // Then
        verify(mockTestPipeStreamResponseObserver).onNext(streamCaptor.capture());
        verify(mockTestPipeStreamResponseObserver).onCompleted();
        verify(mockTestPipeStreamResponseObserver, never()).onError(any());
        
        PipeStream response = streamCaptor.getValue();
        assertNotNull(response);
        assertEquals("1", response.getContextParamsMap().get("test.routing.count"));
        assertEquals("test-topic", response.getContextParamsMap().get("test.routing.0.destination"));
        assertEquals("nextStep", response.getContextParamsMap().get("test.routing.0.nextTargetStep"));
        assertEquals("KAFKA", response.getContextParamsMap().get("test.routing.0.transportType"));
    }
}