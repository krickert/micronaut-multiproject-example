package com.krickert.search.pipeline.engine.core;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.grpc.PipeStreamGrpcForwarder;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultPipeStreamEngineLogicImplTest {

    @Mock
    private PipeStepExecutorFactory executorFactory;
    
    @Mock
    private PipeStreamGrpcForwarder grpcForwarder;
    
    @Mock
    private KafkaForwarder kafkaForwarder;
    
    @Mock
    private DynamicConfigurationManager configManager;
    
    @Mock
    private PipeStepExecutor mockExecutor;
    
    private DefaultPipeStreamEngineLogicImpl engineLogic;
    
    private static final String TEST_PIPELINE = "test-pipeline";
    private static final String TEST_STEP = "test-step";
    private static final String NEXT_STEP = "next-step";
    private static final String TEST_STREAM_ID = "test-stream-123";
    
    @BeforeEach
    void setUp() {
        engineLogic = new DefaultPipeStreamEngineLogicImpl(
                executorFactory, grpcForwarder, kafkaForwarder, configManager);
    }
    
    @Test
    void testProcessStream_SuccessfulExecutionWithKafkaRouting() throws Exception {
        // Given
        PipeStream inputStream = createTestPipeStream();
        PipeStream processedStream = createProcessedPipeStream();
        
        // Mock configuration
        PipelineConfig pipelineConfig = createPipelineConfigWithKafkaOutput();
        when(configManager.getPipelineConfig(TEST_PIPELINE)).thenReturn(Optional.of(pipelineConfig));
        
        // Mock executor
        when(executorFactory.getExecutor(TEST_PIPELINE, TEST_STEP)).thenReturn(mockExecutor);
        when(mockExecutor.execute(any(PipeStream.class))).thenReturn(processedStream);
        
        // Mock Kafka forwarding
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test-topic", 0), 0, 0, 0L, 0, 0);
        CompletableFuture<RecordMetadata> kafkaFuture = CompletableFuture.completedFuture(metadata);
        when(kafkaForwarder.forwardToKafka(any(PipeStream.class), anyString()))
                .thenReturn(kafkaFuture);
        
        // When
        engineLogic.processStream(inputStream);
        
        // Then
        verify(executorFactory).getExecutor(TEST_PIPELINE, TEST_STEP);
        verify(mockExecutor).execute(any(PipeStream.class));
        
        // Verify Kafka forwarding
        ArgumentCaptor<PipeStream> kafkaStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaForwarder).forwardToKafka(kafkaStreamCaptor.capture(), topicCaptor.capture());
        
        PipeStream forwardedStream = kafkaStreamCaptor.getValue();
        assertEquals(NEXT_STEP, forwardedStream.getTargetStepName());
        assertEquals("pipeline.test-pipeline.step.next-step.input", topicCaptor.getValue());
    }
    
    @Test
    void testProcessStream_SuccessfulExecutionWithGrpcRouting() throws Exception {
        // Given
        PipeStream inputStream = createTestPipeStream();
        PipeStream processedStream = createProcessedPipeStream();
        
        // Mock configuration
        PipelineConfig pipelineConfig = createPipelineConfigWithGrpcOutput();
        when(configManager.getPipelineConfig(TEST_PIPELINE)).thenReturn(Optional.of(pipelineConfig));
        
        // Mock executor
        when(executorFactory.getExecutor(TEST_PIPELINE, TEST_STEP)).thenReturn(mockExecutor);
        when(mockExecutor.execute(any(PipeStream.class))).thenReturn(processedStream);
        
        // Mock gRPC forwarding
        CompletableFuture<Void> grpcFuture = CompletableFuture.completedFuture(null);
        when(grpcForwarder.forwardToGrpc(any(PipeStream.Builder.class), any(PipeStreamGrpcForwarder.RouteData.class)))
                .thenReturn(grpcFuture);
        
        // When
        engineLogic.processStream(inputStream);
        
        // Then
        verify(executorFactory).getExecutor(TEST_PIPELINE, TEST_STEP);
        verify(mockExecutor).execute(any(PipeStream.class));
        
        // Verify gRPC forwarding
        ArgumentCaptor<PipeStream.Builder> grpcBuilderCaptor = ArgumentCaptor.forClass(PipeStream.Builder.class);
        ArgumentCaptor<PipeStreamGrpcForwarder.RouteData> routeCaptor = 
                ArgumentCaptor.forClass(PipeStreamGrpcForwarder.RouteData.class);
        verify(grpcForwarder).forwardToGrpc(grpcBuilderCaptor.capture(), routeCaptor.capture());
        
        PipeStreamGrpcForwarder.RouteData route = routeCaptor.getValue();
        assertEquals(NEXT_STEP, route.nextTargetStep());
        assertEquals("embedder-service", route.destination());
    }
    
    @Test
    void testProcessStream_ExecutionFailureWithRetry() throws Exception {
        // Given
        PipeStream inputStream = createTestPipeStream();
        
        // Mock configuration with retry settings
        PipelineConfig pipelineConfig = createPipelineConfigWithRetries();
        when(configManager.getPipelineConfig(TEST_PIPELINE)).thenReturn(Optional.of(pipelineConfig));
        
        // Mock executor that fails once then succeeds
        when(executorFactory.getExecutor(TEST_PIPELINE, TEST_STEP)).thenReturn(mockExecutor);
        when(mockExecutor.execute(any(PipeStream.class)))
                .thenThrow(new PipeStepExecutionException("Temporary failure", true))
                .thenReturn(createProcessedPipeStream());
        
        // Mock Kafka error forwarding
        CompletableFuture<RecordMetadata> errorFuture = CompletableFuture.completedFuture(null);
        when(kafkaForwarder.forwardToErrorTopic(any(PipeStream.class), anyString()))
                .thenReturn(errorFuture);
        
        // When
        engineLogic.processStream(inputStream);
        
        // Then
        verify(executorFactory).getExecutor(TEST_PIPELINE, TEST_STEP);
        verify(mockExecutor, times(2)).execute(any(PipeStream.class)); // Called twice due to retry
    }
    
    @Test
    void testProcessStream_ExecutionFailureNonRetryable() throws Exception {
        // Given
        PipeStream inputStream = createTestPipeStream();
        
        // Mock configuration
        PipelineConfig pipelineConfig = createPipelineConfigWithRetries();
        when(configManager.getPipelineConfig(TEST_PIPELINE)).thenReturn(Optional.of(pipelineConfig));
        
        // Mock executor that fails with non-retryable error
        when(executorFactory.getExecutor(TEST_PIPELINE, TEST_STEP)).thenReturn(mockExecutor);
        when(mockExecutor.execute(any(PipeStream.class)))
                .thenThrow(new PipeStepExecutionException("Permanent failure", false));
        
        // Mock Kafka error forwarding
        CompletableFuture<RecordMetadata> errorFuture = CompletableFuture.completedFuture(null);
        when(kafkaForwarder.forwardToErrorTopic(any(PipeStream.class), anyString()))
                .thenReturn(errorFuture);
        
        // When
        engineLogic.processStream(inputStream);
        
        // Then
        verify(executorFactory).getExecutor(TEST_PIPELINE, TEST_STEP);
        verify(mockExecutor, times(1)).execute(any(PipeStream.class)); // Only called once
        
        // Verify error forwarding
        ArgumentCaptor<PipeStream> errorStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        ArgumentCaptor<String> errorTopicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaForwarder).forwardToErrorTopic(errorStreamCaptor.capture(), errorTopicCaptor.capture());
        
        assertEquals("pipeline.errors.test-pipeline", errorTopicCaptor.getValue());
        assertTrue(errorStreamCaptor.getValue().hasStreamErrorData());
    }
    
    @Test
    void testProcessStream_NoOutputsConfigured() throws Exception {
        // Given
        PipeStream inputStream = createTestPipeStream();
        PipeStream processedStream = createProcessedPipeStream();
        
        // Mock configuration with no outputs (terminal step)
        PipelineConfig pipelineConfig = createPipelineConfigWithNoOutputs();
        when(configManager.getPipelineConfig(TEST_PIPELINE)).thenReturn(Optional.of(pipelineConfig));
        
        // Mock executor
        when(executorFactory.getExecutor(TEST_PIPELINE, TEST_STEP)).thenReturn(mockExecutor);
        when(mockExecutor.execute(any(PipeStream.class))).thenReturn(processedStream);
        
        // When
        engineLogic.processStream(inputStream);
        
        // Then
        verify(executorFactory).getExecutor(TEST_PIPELINE, TEST_STEP);
        verify(mockExecutor).execute(any(PipeStream.class));
        
        // Verify no forwarding occurs
        verifyNoInteractions(kafkaForwarder);
        verifyNoInteractions(grpcForwarder);
    }
    
    @Test
    void testProcessStream_MultipleOutputs() throws Exception {
        // Given
        PipeStream inputStream = createTestPipeStream();
        PipeStream processedStream = createProcessedPipeStream();
        
        // Mock configuration with multiple outputs
        PipelineConfig pipelineConfig = createPipelineConfigWithMultipleOutputs();
        when(configManager.getPipelineConfig(TEST_PIPELINE)).thenReturn(Optional.of(pipelineConfig));
        
        // Mock executor
        when(executorFactory.getExecutor(TEST_PIPELINE, TEST_STEP)).thenReturn(mockExecutor);
        when(mockExecutor.execute(any(PipeStream.class))).thenReturn(processedStream);
        
        // Mock forwarding
        CompletableFuture<RecordMetadata> kafkaFuture = CompletableFuture.completedFuture(null);
        when(kafkaForwarder.forwardToKafka(any(PipeStream.class), anyString()))
                .thenReturn(kafkaFuture);
        
        CompletableFuture<Void> grpcFuture = CompletableFuture.completedFuture(null);
        when(grpcForwarder.forwardToGrpc(any(PipeStream.Builder.class), any(PipeStreamGrpcForwarder.RouteData.class)))
                .thenReturn(grpcFuture);
        
        // When
        engineLogic.processStream(inputStream);
        
        // Then
        verify(kafkaForwarder, times(1)).forwardToKafka(any(PipeStream.class), anyString());
        verify(grpcForwarder, times(1)).forwardToGrpc(any(PipeStream.Builder.class), any(PipeStreamGrpcForwarder.RouteData.class));
    }
    
    // Helper methods
    
    private PipeStream createTestPipeStream() {
        return PipeStream.newBuilder()
                .setStreamId(TEST_STREAM_ID)
                .setCurrentPipelineName(TEST_PIPELINE)
                .setTargetStepName(TEST_STEP)
                .setCurrentHopNumber(0)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-123")
                        .setTitle("Test Document")
                        .setBody("Test content")
                        .build())
                .build();
    }
    
    private PipeStream createProcessedPipeStream() {
        return createTestPipeStream().toBuilder()
                .setCurrentHopNumber(1)
                .addHistory(com.krickert.search.model.StepExecutionRecord.newBuilder()
                        .setHopNumber(1)
                        .setStepName(TEST_STEP)
                        .setStatus("SUCCESS")
                        .build())
                .build();
    }
    
    private PipelineConfig createPipelineConfigWithKafkaOutput() {
        return new PipelineConfig(
                TEST_PIPELINE,
                Map.of(TEST_STEP, PipelineStepConfig.builder()
                        .stepName(TEST_STEP)
                        .stepType(StepType.PIPELINE)
                        .processorInfo(ProcessorInfo.builder()
                                .grpcServiceName("test-service")
                                .build())
                        .outputs(Map.of("output1", 
                                PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName(NEXT_STEP)
                                        .transportType(TransportType.KAFKA)
                                        .kafkaTransport(KafkaTransportConfig.builder()
                                                .topic("pipeline.test-pipeline.step.next-step.input")
                                                .build())
                                        .build()))
                        .build())
        );
    }
    
    private PipelineConfig createPipelineConfigWithGrpcOutput() {
        return new PipelineConfig(
                TEST_PIPELINE,
                Map.of(TEST_STEP, PipelineStepConfig.builder()
                        .stepName(TEST_STEP)
                        .stepType(StepType.PIPELINE)
                        .processorInfo(ProcessorInfo.builder()
                                .grpcServiceName("test-service")
                                .build())
                        .outputs(Map.of("output1", 
                                PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName(NEXT_STEP)
                                        .transportType(TransportType.GRPC)
                                        .grpcTransport(GrpcTransportConfig.builder()
                                                .serviceName("embedder-service")
                                                .build())
                                        .build()))
                        .build())
        );
    }
    
    private PipelineConfig createPipelineConfigWithRetries() {
        return new PipelineConfig(
                TEST_PIPELINE,
                Map.of(TEST_STEP, PipelineStepConfig.builder()
                        .stepName(TEST_STEP)
                        .stepType(StepType.PIPELINE)
                        .processorInfo(ProcessorInfo.builder()
                                .grpcServiceName("test-service")
                                .build())
                        .maxRetries(2)
                        .retryBackoffMs(100L)
                        .outputs(Map.of())
                        .build())
        );
    }
    
    private PipelineConfig createPipelineConfigWithNoOutputs() {
        return new PipelineConfig(
                TEST_PIPELINE,
                Map.of(TEST_STEP, PipelineStepConfig.builder()
                        .stepName(TEST_STEP)
                        .stepType(StepType.SINK)
                        .processorInfo(ProcessorInfo.builder()
                                .grpcServiceName("sink-service")
                                .build())
                        .outputs(Map.of())
                        .build())
        );
    }
    
    private PipelineConfig createPipelineConfigWithMultipleOutputs() {
        return new PipelineConfig(
                TEST_PIPELINE,
                Map.of(TEST_STEP, PipelineStepConfig.builder()
                        .stepName(TEST_STEP)
                        .stepType(StepType.PIPELINE)
                        .processorInfo(ProcessorInfo.builder()
                                .grpcServiceName("test-service")
                                .build())
                        .outputs(Map.of(
                                "kafka-output", PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName("kafka-step")
                                        .transportType(TransportType.KAFKA)
                                        .kafkaTransport(KafkaTransportConfig.builder()
                                                .topic("pipeline.test-pipeline.step.kafka-step.input")
                                                .build())
                                        .build(),
                                "grpc-output", PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName("grpc-step")
                                        .transportType(TransportType.GRPC)
                                        .grpcTransport(GrpcTransportConfig.builder()
                                                .serviceName("another-service")
                                                .build())
                                        .build()
                        ))
                        .build())
        );
    }
}