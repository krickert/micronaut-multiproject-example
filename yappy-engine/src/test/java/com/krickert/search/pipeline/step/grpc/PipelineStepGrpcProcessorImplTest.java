package com.krickert.search.pipeline.step.grpc;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.step.exception.PipeStepProcessingException;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import io.grpc.ManagedChannel;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineStepGrpcProcessorImplTest {

    @Mock
    private DiscoveryClient mockDiscoveryClient;

    @Mock
    private DynamicConfigurationManager mockConfigManager;

    @Mock
    private ManagedChannel mockChannel;

    @Mock
    private PipeStepProcessorGrpc.PipeStepProcessorBlockingStub mockStub;

    @Mock
    private ServiceInstance mockServiceInstance;

    @Captor
    private ArgumentCaptor<ProcessRequest> requestCaptor;

    private PipelineStepGrpcProcessorImpl processor;

    private final String pipelineName = "test-pipeline";
    private final String stepName = "test-step";
    private final String grpcServiceName = "test-grpc-service";

    @BeforeEach
    void setUp() {
        processor = new PipelineStepGrpcProcessorImpl(mockDiscoveryClient, mockConfigManager);
    }

    @Test
    void processStep_shouldCallGrpcServiceAndReturnResponse() {
        // Arrange
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId("test-stream")
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .setDocument(PipeDoc.newBuilder().setId("test-doc").build())
                .build();

        PipeDoc outputDoc = PipeDoc.newBuilder()
                .setId("test-doc")
                .setTitle("Processed Document")
                .build();

        ProcessResponse mockResponse = ProcessResponse.newBuilder()
                .setSuccess(true)
                .setOutputDoc(outputDoc)
                .addProcessorLogs("Processing successful")
                .build();

        // Setup pipeline configuration
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                grpcServiceName, null);

        PipelineStepConfig.JsonConfigOptions jsonConfigOptions = new PipelineStepConfig.JsonConfigOptions(
                null, Collections.singletonMap("param1", "value1"));

        PipelineStepConfig stepConfig = new PipelineStepConfig(
                stepName,
                StepType.PIPELINE,
                "Test Step",
                null,
                jsonConfigOptions,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                processorInfo
        );

        HashMap<String, PipelineStepConfig> stepsMap = new HashMap<>();
        stepsMap.put(stepName, stepConfig);

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(stepsMap)
                .build();

        // Setup discovery client
        when(mockDiscoveryClient.getInstances(grpcServiceName))
                .thenReturn(Mono.just(List.of(mockServiceInstance)));
        when(mockServiceInstance.getHost()).thenReturn("localhost");
        when(mockServiceInstance.getPort()).thenReturn(50051);

        // Setup config manager
        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        // Setup gRPC stub
        try (var mockedStatic = mockStatic(PipeStepProcessorGrpc.class)) {
            mockedStatic.when(() -> PipeStepProcessorGrpc.newBlockingStub(any(ManagedChannel.class)))
                    .thenReturn(mockStub);

            when(mockStub.processData(any(ProcessRequest.class))).thenReturn(mockResponse);

            // Act
            ProcessResponse result = processor.processStep(inputStream, stepName);

            // Assert
            verify(mockConfigManager).getPipelineConfig(pipelineName);
            verify(mockDiscoveryClient).getInstances(grpcServiceName);
            verify(mockStub).processData(requestCaptor.capture());

            ProcessRequest capturedRequest = requestCaptor.getValue();
            assertEquals(inputStream.getDocument(), capturedRequest.getDocument());
            assertEquals(pipelineName, capturedRequest.getMetadata().getPipelineName());
            assertEquals(stepName, capturedRequest.getMetadata().getPipeStepName());
            assertEquals(inputStream.getStreamId(), capturedRequest.getMetadata().getStreamId());

            assertNotNull(result);
            assertTrue(result.getSuccess());
            assertEquals(outputDoc, result.getOutputDoc());
            assertEquals("Processing successful", result.getProcessorLogs(0));
        }
    }

    @Test
    void processStep_shouldThrowExceptionWhenPipelineNotFound() {
        // Arrange
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId("test-stream")
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .setDocument(PipeDoc.newBuilder().setId("test-doc").build())
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(PipeStepProcessingException.class, () -> processor.processStep(inputStream, stepName));
        verify(mockConfigManager).getPipelineConfig(pipelineName);
        verifyNoInteractions(mockDiscoveryClient);
    }

    @Test
    void processStep_shouldThrowExceptionWhenStepNotFound() {
        // Arrange
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId("test-stream")
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .setDocument(PipeDoc.newBuilder().setId("test-doc").build())
                .build();

        HashMap<String, PipelineStepConfig> stepsMap = new HashMap<>();
        // No step with the name "test-step"

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(stepsMap)
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        // Act & Assert
        assertThrows(PipeStepProcessingException.class, () -> processor.processStep(inputStream, stepName));
        verify(mockConfigManager).getPipelineConfig(pipelineName);
        verifyNoInteractions(mockDiscoveryClient);
    }

    @Test
    void processStep_shouldThrowExceptionWhenServiceNotFound() {
        // Arrange
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId("test-stream")
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .setDocument(PipeDoc.newBuilder().setId("test-doc").build())
                .build();

        // Setup pipeline configuration
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                grpcServiceName, null);

        PipelineStepConfig stepConfig = new PipelineStepConfig(
                stepName,
                StepType.PIPELINE,
                "Test Step",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                processorInfo
        );

        HashMap<String, PipelineStepConfig> stepsMap = new HashMap<>();
        stepsMap.put(stepName, stepConfig);

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(stepsMap)
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));
        when(mockDiscoveryClient.getInstances(grpcServiceName)).thenReturn(Mono.just(Collections.emptyList()));

        // Act & Assert
        assertThrows(PipeStepProcessingException.class, () -> processor.processStep(inputStream, stepName));
        verify(mockConfigManager).getPipelineConfig(pipelineName);
        verify(mockDiscoveryClient).getInstances(grpcServiceName);
    }
}
