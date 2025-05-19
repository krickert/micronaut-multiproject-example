package com.krickert.search.pipeline.step.grpc;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.step.exception.PipeStepProcessingException;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ServiceMetadata;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of PipelineStepGrpcProcessor that connects to gRPC services.
 */
@Singleton
public class PipelineStepGrpcProcessorImpl implements PipelineStepGrpcProcessor {
    private final DiscoveryClient discoveryClient;
    private final DynamicConfigurationManager configManager;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    @Inject
    public PipelineStepGrpcProcessorImpl(DiscoveryClient discoveryClient,
                                        DynamicConfigurationManager configManager) {
        this.discoveryClient = discoveryClient;
        this.configManager = configManager;
    }

    @Override
    public ProcessResponse processStep(PipeStream pipeStream, String stepName) throws PipeStepProcessingException {
        try {
            // Get the pipeline configuration
            Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipeStream.getCurrentPipelineName());
            if (pipelineConfig.isEmpty()) {
                throw new PipeStepProcessingException("Pipeline not found: " + pipeStream.getCurrentPipelineName());
            }

            // Get the step configuration
            PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(stepName);
            if (stepConfig == null) {
                throw new PipeStepProcessingException("Step not found: " + stepName);
            }

            // Get the gRPC service name
            String grpcServiceName = stepConfig.processorInfo().grpcServiceName();
            if (grpcServiceName == null || grpcServiceName.isBlank()) {
                throw new PipeStepProcessingException("No gRPC service name found for step: " + stepName);
            }

            // Get or create the channel
            ManagedChannel channel = getOrCreateChannel(grpcServiceName);

            // Create the stub
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);

            // Create the request
            ProcessRequest request = createProcessRequest(pipeStream, stepConfig);

            // Call the service
            return stub.processData(request);
        } catch (Exception e) {
            throw new PipeStepProcessingException("Error processing step: " + stepName, e);
        }
    }

    private ManagedChannel getOrCreateChannel(String serviceName) {
        return channelCache.computeIfAbsent(serviceName, name -> {
            // Use discovery client to find the service
            List<ServiceInstance> instances = Mono.from(discoveryClient.getInstances(name))
                .block(Duration.ofSeconds(10));

            if (instances == null || instances.isEmpty()) {
                throw new PipeStepProcessingException("Service not found: " + name);
            }

            ServiceInstance instance = instances.get(0);
            return ManagedChannelBuilder.forAddress(instance.getHost(), instance.getPort())
                .usePlaintext()
                .build();
        });
    }

    private ProcessRequest createProcessRequest(PipeStream pipeStream, PipelineStepConfig stepConfig) {
        // Create service metadata
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
            .setPipelineName(pipeStream.getCurrentPipelineName())
            .setPipeStepName(stepConfig.stepName())
            .setStreamId(pipeStream.getStreamId())
            .setCurrentHopNumber(pipeStream.getCurrentHopNumber())
            .addAllHistory(pipeStream.getHistoryList())
            .putAllContextParams(pipeStream.getContextParamsMap())
            .build();

        // Create process configuration
        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        
        // Add config params if available
        if (stepConfig.customConfig() != null && stepConfig.customConfig().configParams() != null) {
            configBuilder.putAllConfigParams(stepConfig.customConfig().configParams());
        }
        
        // Create the request
        return ProcessRequest.newBuilder()
            .setDocument(pipeStream.getDocument())
            .setConfig(configBuilder.build())
            .setMetadata(metadata)
            .build();
    }
}