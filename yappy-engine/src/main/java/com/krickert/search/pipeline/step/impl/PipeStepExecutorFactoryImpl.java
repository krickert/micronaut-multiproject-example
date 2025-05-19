package com.krickert.search.pipeline.step.impl;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import com.krickert.search.pipeline.step.exception.PipeStepExecutorNotFoundException;
import com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * Implementation of PipeStepExecutorFactory that creates executors based on pipeline configuration.
 */
@Singleton
public class PipeStepExecutorFactoryImpl implements PipeStepExecutorFactory {
    private final DynamicConfigurationManager configManager;
    private final PipelineStepGrpcProcessor grpcProcessor;

    @Inject
    public PipeStepExecutorFactoryImpl(DynamicConfigurationManager configManager,
                                      PipelineStepGrpcProcessor grpcProcessor) {
        this.configManager = configManager;
        this.grpcProcessor = grpcProcessor;
    }

    @Override
    public PipeStepExecutor getExecutor(String pipelineName, String stepName) throws PipeStepExecutorNotFoundException {
        // Get the pipeline configuration
        Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipelineName);
        if (pipelineConfig.isEmpty()) {
            throw new PipeStepExecutorNotFoundException("Pipeline not found: " + pipelineName);
        }

        // Get the step configuration
        PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(stepName);
        if (stepConfig == null) {
            throw new PipeStepExecutorNotFoundException("Step not found: " + stepName + " in pipeline: " + pipelineName);
        }

        // Create the appropriate executor based on step type
        if (stepConfig.processorInfo().grpcServiceName() != null && !stepConfig.processorInfo().grpcServiceName().isBlank()) {
            return new GrpcPipeStepExecutor(grpcProcessor, stepName, stepConfig.stepType());
        } else if (stepConfig.processorInfo().internalProcessorBeanName() != null && !stepConfig.processorInfo().internalProcessorBeanName().isBlank()) {
            // For internal processors, we would use a different executor implementation
            // This would be implemented in a future task
            throw new PipeStepExecutorNotFoundException("Internal processors not yet implemented");
        } else {
            throw new PipeStepExecutorNotFoundException("No processor info found for step: " + stepName);
        }
    }
}