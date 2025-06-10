package com.krickert.search.pipeline.step.grpc;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager; // Import the new manager
import com.krickert.search.pipeline.step.exception.PipeStepProcessingException;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ServiceMetadata;
import io.grpc.ManagedChannel;
// Removed ManagedChannelBuilder, DiscoveryClient, ServiceInstance, Mono, Duration, List, Map, ConcurrentHashMap
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Optional;


@Singleton
@Requires(property = "consul.enabled", value = "true", defaultValue = "true")
public class PipelineStepGrpcProcessorImpl implements PipelineStepGrpcProcessor {
    private final DynamicConfigurationManager configManager;
    private final GrpcChannelManager channelManager; // Use the new manager

    @Inject
    public PipelineStepGrpcProcessorImpl(DynamicConfigurationManager configManager,
                                        GrpcChannelManager channelManager) { // Inject GrpcChannelManager
        this.configManager = configManager;
        this.channelManager = channelManager;
    }

    @Override
    public ProcessResponse processStep(PipeStream pipeStream, String stepName) throws PipeStepProcessingException {
        try {
            Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipeStream.getCurrentPipelineName());
            if (pipelineConfig.isEmpty()) {
                throw new PipeStepProcessingException("Pipeline not found: " + pipeStream.getCurrentPipelineName());
            }

            PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(stepName);
            if (stepConfig == null) {
                throw new PipeStepProcessingException("Step not found: " + stepName);
            }

            String grpcServiceName = stepConfig.processorInfo().grpcServiceName();
            if (grpcServiceName == null || grpcServiceName.isBlank()) {
                throw new PipeStepProcessingException("No gRPC service name found for step: " + stepName);
            }

            // Get channel from the manager
            ManagedChannel channel = channelManager.getChannel(grpcServiceName);

            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub =
                PipeStepProcessorGrpc.newBlockingStub(channel);

            ProcessRequest request = createProcessRequest(pipeStream, stepConfig);
            return stub.processData(request);
        } catch (Exception e) { // Catching general Exception is broad, consider more specific gRPC exceptions if possible
            // If channelManager.getChannel throws GrpcEngineException, it will be caught here.
            throw new PipeStepProcessingException("Error processing step: " + stepName + ". Cause: " + e.getMessage(), e);
        }
    }

    // getOrCreateChannel is removed, channelCache is removed

    private ProcessRequest createProcessRequest(PipeStream pipeStream, PipelineStepConfig stepConfig) {
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
            .setPipelineName(pipeStream.getCurrentPipelineName())
            .setPipeStepName(stepConfig.stepName())
            .setStreamId(pipeStream.getStreamId())
            .setCurrentHopNumber(pipeStream.getCurrentHopNumber())
            .addAllHistory(pipeStream.getHistoryList())
            .putAllContextParams(pipeStream.getContextParamsMap())
            .build();

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (stepConfig.customConfig() != null && stepConfig.customConfig().configParams() != null) {
            configBuilder.putAllConfigParams(stepConfig.customConfig().configParams());
        }

        return ProcessRequest.newBuilder()
            .setDocument(pipeStream.getDocument())
            .setConfig(configBuilder.build())
            .setMetadata(metadata)
            .build();
    }
}