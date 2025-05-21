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
        PipelineConfig pipelineConfig = configManager.getPipelineConfig(pipelineName)
                .orElseThrow(() -> new PipeStepExecutorNotFoundException("Pipeline not found: " + pipelineName));

        // Get the step configuration
        PipelineStepConfig stepConfig = Optional.ofNullable(pipelineConfig.pipelineSteps().get(stepName))
                .orElseThrow(() -> new PipeStepExecutorNotFoundException("Step not found: " + stepName + " in pipeline: " + pipelineName));

        PipelineStepConfig.ProcessorInfo processorInfo = stepConfig.processorInfo();
        if (processorInfo == null) {
            throw new PipeStepExecutorNotFoundException("ProcessorInfo is missing for step: " + stepName + " in pipeline: " + pipelineName);
        }

        // Create the appropriate executor based on processor type
        String grpcServiceName = processorInfo.grpcServiceName();
        if (grpcServiceName != null && !grpcServiceName.isBlank()) {
            return new GrpcPipeStepExecutor(grpcProcessor, stepName, stepConfig.stepType());
        }

        String internalProcessorBeanName = processorInfo.internalProcessorBeanName();
        if (internalProcessorBeanName != null && !internalProcessorBeanName.isBlank()) {
            // For internal processors, we would use a different executor implementation
            // This would be implemented in a future task
            // Example: return applicationContext.getBean(internalProcessorBeanName, PipeStepExecutor.class);
            // Or: return new InternalBeanPipeStepExecutor(applicationContext, internalProcessorBeanName, stepName, stepConfig.stepType());
            throw new PipeStepExecutorNotFoundException("Internal processors not yet implemented for bean: " + internalProcessorBeanName + " in step: " + stepName);
        }

        throw new PipeStepExecutorNotFoundException("No valid processor (gRPC service or internal bean) configured for step: " + stepName + " in pipeline: " + pipelineName);
    }
}