package com.krickert.search.pipeline.engine.state;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.GrpcTransportConfig;
import com.krickert.search.config.pipeline.model.KafkaTransportConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.pipeline.engine.common.RouteData;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of PipeStreamStateBuilder that manages pipeline stream state and routing.
 */
@Singleton
public class PipeStreamStateBuilderImpl implements PipeStreamStateBuilder {
    private final PipeStream requestState;
    private PipeStream.Builder presentState;
    private PipeStream responseState;
    private final DynamicConfigurationManager configManager;
    private final long startTime;
    private long endTime;

    @Inject
    public PipeStreamStateBuilderImpl(PipeStream request, 
                                     DynamicConfigurationManager configManager) {
        this.requestState = request;
        this.presentState = request.toBuilder();
        this.configManager = configManager;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public PipeStream getRequestState() {
        return requestState;
    }

    @Override
    public PipeStream.Builder getPresentState() {
        return presentState;
    }

    @Override
    public PipeStreamStateBuilder withHopNumber(int hopNumber) {
        presentState.setCurrentHopNumber(hopNumber);
        return this;
    }

    @Override
    public PipeStreamStateBuilder withTargetStep(String stepName) {
        presentState.setTargetStepName(stepName);
        return this;
    }

    @Override
    public PipeStreamStateBuilder addLogEntry(String log) {
        // Add log entry to history
        StepExecutionRecord record = StepExecutionRecord.newBuilder()
            .setHopNumber(presentState.getCurrentHopNumber())
            .setStepName(presentState.getTargetStepName())
            .setStatus("SUCCESS")
            .addProcessorLogs(log)
            .build();
        presentState.addHistory(record);
        return this;
    }

    @Override
    public List<RouteData> calculateNextRoutes() {
        List<RouteData> routes = new ArrayList<>();

        // Get the pipeline configuration
        Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(presentState.getCurrentPipelineName());
        if (pipelineConfig.isEmpty()) {
            return routes; // Empty list if pipeline not found
        }

        // Get the current step configuration
        PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(presentState.getTargetStepName());
        if (stepConfig == null) {
            return routes; // Empty list if step not found
        }

        // Calculate routes based on outputs
        for (Map.Entry<String, OutputTarget> entry : stepConfig.outputs().entrySet()) {
            String outputKey = entry.getKey();
            OutputTarget target = entry.getValue();

            if (target.transportType() == TransportType.GRPC) {
                // Create gRPC route
                routes.add(RouteData.builder()
                    .targetPipeline(presentState.getCurrentPipelineName())
                    .nextTargetStep(target.targetStepName())
                    .destination(target.grpcTransport().serviceName())
                    .streamId(presentState.getStreamId())
                    .transportType(TransportType.GRPC)
                    .build());
            } else if (target.transportType() == TransportType.KAFKA) {
                // Create Kafka route - handled similarly to gRPC since both take PipeStream requests
                // The KafkaForwarder is already asynchronous by default in Micronaut
                routes.add(RouteData.builder()
                    .targetPipeline(presentState.getCurrentPipelineName())
                    .nextTargetStep(target.targetStepName())
                    .destination(target.kafkaTransport().topic())
                    .streamId(presentState.getStreamId())
                    .transportType(TransportType.KAFKA)
                    .build());
            }
        }

        return routes;
    }

    @Override
    public PipeStream build() {
        endTime = System.currentTimeMillis();
        // Add any final metadata or processing
        // For example, we could add a final history entry with timing information

        // Build the final response
        responseState = presentState.build();
        return responseState;
    }
}
