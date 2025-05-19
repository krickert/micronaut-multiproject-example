package com.krickert.search.pipeline.step.impl;

import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor;
import com.krickert.search.sdk.ProcessResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrpcPipeStepExecutorTest {

    @Mock
    private PipelineStepGrpcProcessor mockGrpcProcessor;

    private GrpcPipeStepExecutor executor;
    private final String stepName = "test-step";
    private final StepType stepType = StepType.PIPELINE;

    @BeforeEach
    void setUp() {
        executor = new GrpcPipeStepExecutor(mockGrpcProcessor, stepName, stepType);
    }

    @Test
    void execute_shouldCallProcessorAndTransformResponse() {
        // Arrange
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId("test-stream")
                .setCurrentPipelineName("test-pipeline")
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

        when(mockGrpcProcessor.processStep(any(PipeStream.class), eq(stepName)))
                .thenReturn(mockResponse);

        // Act
        PipeStream result = executor.execute(inputStream);

        // Assert
        verify(mockGrpcProcessor).processStep(inputStream, stepName);
        assertNotNull(result);
        assertEquals(inputStream.getStreamId(), result.getStreamId());
        assertEquals(inputStream.getCurrentPipelineName(), result.getCurrentPipelineName());
        assertEquals(inputStream.getTargetStepName(), result.getTargetStepName());
        assertEquals(outputDoc, result.getDocument());
    }

    @Test
    void execute_shouldThrowExceptionWhenProcessorFails() {
        // Arrange
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId("test-stream")
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName(stepName)
                .setDocument(PipeDoc.newBuilder().setId("test-doc").build())
                .build();

        when(mockGrpcProcessor.processStep(any(PipeStream.class), eq(stepName)))
                .thenThrow(new RuntimeException("Processor failed"));

        // Act & Assert
        assertThrows(PipeStepExecutionException.class, () -> executor.execute(inputStream));
        verify(mockGrpcProcessor).processStep(inputStream, stepName);
    }

    @Test
    void getStepName_shouldReturnCorrectName() {
        assertEquals(stepName, executor.getStepName());
    }

    @Test
    void getStepType_shouldReturnCorrectType() {
        assertEquals(stepType, executor.getStepType());
    }
}