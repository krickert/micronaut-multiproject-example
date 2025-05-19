package com.krickert.search.pipeline.step.impl;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.exception.PipeStepExecutorNotFoundException;
import com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipeStepExecutorFactoryImplTest {

    @Mock
    private DynamicConfigurationManager mockConfigManager;

    @Mock
    private PipelineStepGrpcProcessor mockGrpcProcessor;

    private PipeStepExecutorFactoryImpl factory;

    private final String pipelineName = "test-pipeline";
    private final String stepName = "test-step";

    @BeforeEach
    void setUp() {
        factory = new PipeStepExecutorFactoryImpl(mockConfigManager, mockGrpcProcessor);
    }

    @Test
    void getExecutor_shouldReturnGrpcExecutorForGrpcStep() {
        // Arrange
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                "test-grpc-service", null);

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

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(stepName, stepConfig);

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(steps)
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        // Act
        PipeStepExecutor executor = factory.getExecutor(pipelineName, stepName);

        // Assert
        assertNotNull(executor);
        assertTrue(executor instanceof GrpcPipeStepExecutor);
        assertEquals(stepName, executor.getStepName());
        assertEquals(StepType.PIPELINE, executor.getStepType());
    }

    @Test
    void getExecutor_shouldThrowExceptionForInternalProcessor() {
        // Arrange
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                null, "internal-processor");

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

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(stepName, stepConfig);

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(steps)
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        // Act & Assert
        assertThrows(PipeStepExecutorNotFoundException.class, () -> factory.getExecutor(pipelineName, stepName));
    }

    @Test
    void getExecutor_shouldThrowExceptionWhenPipelineNotFound() {
        // Arrange
        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(PipeStepExecutorNotFoundException.class, () -> factory.getExecutor(pipelineName, stepName));
    }

    @Test
    void getExecutor_shouldThrowExceptionWhenStepNotFound() {
        // Arrange
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        // No step with the name "test-step"

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(steps)
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        // Act & Assert
        assertThrows(PipeStepExecutorNotFoundException.class, () -> factory.getExecutor(pipelineName, stepName));
    }
}