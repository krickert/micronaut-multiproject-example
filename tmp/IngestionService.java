package com.krickert.search.engine.orchestration;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
// Assuming PipelineConnectorSpec is a class/record in your model that now includes initialTargetStepName
// import com.krickert.search.config.pipeline.model.PipelineConnectorSpec;
import com.krickert.search.config.pipeline.model.PipelineStepConfig; // For step validation if needed
import com.krickert.search.config.pipeline.model.StepType; // For identifying INITIAL_PIPELINE steps
import com.google.protobuf.Empty;
import com.krickert.search.engine.IngestDataRequest;
import com.krickert.search.engine.IngestDataResponse;
import com.krickert.search.model.PipeStream;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.Optional;
import java.util.Map;

@Singleton
public class IngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);

    private final DynamicConfigurationManager configManager;
    private final AsyncPipelineProcessor asyncPipelineProcessor;

    @Inject
    public IngestionService(DynamicConfigurationManager configManager, AsyncPipelineProcessor asyncPipelineProcessor) {
        this.configManager = configManager;
        this.asyncPipelineProcessor = asyncPipelineProcessor;
    }

    public void handleIngestion(IngestDataRequest request, StreamObserver<IngestDataResponse> responseObserver) {
        LOG.info("Handling ingestion for source_identifier: {}", request.getSourceIdentifier());

        if (!request.hasDocument() || request.getSourceIdentifier().isEmpty()) {
            LOG.warn("Invalid IngestDataRequest: missing document or source_identifier");
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Document and source_identifier are required.")
                    .asRuntimeException());
            return;
        }

        String streamId = request.hasSuggestedStreamId() && !request.getSuggestedStreamId().isEmpty()
                ? request.getSuggestedStreamId()
                : UUID.randomUUID().toString();
        try {
            PipelineClusterConfig clusterConfig = configManager.getCurrentPipelineClusterConfig().orElse(null);
            if (clusterConfig == null) {
                LOG.error("PipelineClusterConfig not found. Cannot process ingestion.");
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("System configuration (ClusterConfig) not available.")
                        .asRuntimeException());
                return;
            }

            ConnectorPipelineTarget targetInfo = findConnectorPipelineTarget(clusterConfig, request.getSourceIdentifier());

            if (targetInfo == null) {
                LOG.error("No pipeline and initial step configured for source_identifier: {} (or default is invalid/missing)", request.getSourceIdentifier());
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("No pipeline and initial step configured for source_identifier: " + request.getSourceIdentifier())
                        .asRuntimeException());
                return;
            }

            String pipelineName = targetInfo.pipelineName();
            String initialStepName = targetInfo.initialStepName();

            PipelineConfig pipelineConfig = Optional.ofNullable(clusterConfig.pipelineGraphConfig())
                    .map(graphConfig -> graphConfig.getPipelineConfig(pipelineName))
                    .orElse(null);

            if (pipelineConfig == null || pipelineConfig.pipelineSteps() == null || pipelineConfig.pipelineSteps().isEmpty()) {
                LOG.error("Pipeline configuration for '{}' not found, is null, or has no steps.", pipelineName);
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Pipeline configuration error for: " + pipelineName)
                        .asRuntimeException());
                return;
            }

            // Validate that the initialStepName (from connector config or default pipeline's own initial step) exists in the pipeline steps
            boolean initialStepExists = pipelineConfig.pipelineSteps().get(initialStepName) != null;

            if (!initialStepExists) {
                LOG.error("Initial step '{}' (for pipeline '{}', source_identifier '{}') does not exist in the pipeline configuration's step list.",
                        initialStepName, pipelineName, request.getSourceIdentifier());
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Initial step '" + initialStepName + "' for pipeline '" + pipelineName + "' is not defined in its steps.")
                        .asRuntimeException());
                return;
            }

            PipeStream.Builder pipeStreamBuilder = PipeStream.newBuilder()
                    .setStreamId(streamId)
                    .setDocument(request.getDocument())
                    .setCurrentPipelineName(pipelineName)
                    .setTargetStepName(initialStepName) // Use the initial step from connector/default config
                    .setCurrentHopNumber(0)
                    .putAllContextParams(request.getInitialContextParamsMap());

            PipeStream initialPipeStream = pipeStreamBuilder.build();
            LOG.info("Initial PipeStream created: id={}, pipeline={}, target_step={}",
                    initialPipeStream.getStreamId(),
                    initialPipeStream.getCurrentPipelineName(),
                    initialPipeStream.getTargetStepName());

            // Hand off to async processor
            asyncPipelineProcessor.processAndDispatchAsync(initialPipeStream, new StreamObserver<Empty>() {
                @Override
                public void onNext(Empty value) {
                    LOG.info("Successfully initiated pipeline processing for stream_id: {}", streamId);
                    IngestDataResponse response = IngestDataResponse.newBuilder()
                            .setStreamId(streamId)
                            .setAccepted(true)
                            .setMessage("Ingestion accepted, pipeline processing initiated for stream ID: " + streamId)
                            .build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }

                @Override
                public void onError(Throwable t) {
                    LOG.error("Error initiating pipeline processing for stream_id: " + streamId, t);
                    responseObserver.onError(Status.INTERNAL
                            .withDescription("Failed to initiate pipeline processing: " + t.getMessage())
                            .withCause(t)
                            .asRuntimeException());
                }

                @Override
                public void onCompleted() {
                }
            });

        } catch (Exception e) {
            LOG.error("Unexpected error during ingestion for source_identifier: {}", request.getSourceIdentifier(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Unexpected error processing ingestion request: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    private ConnectorPipelineTarget findConnectorPipelineTarget(PipelineClusterConfig clusterConfig, String sourceIdentifier) {
        // Priority:
        // 1. Look for a pipeline with an INITIAL_PIPELINE step that matches the sourceIdentifier
        // 2. Use the default pipeline if specified

        if (clusterConfig.pipelineGraphConfig() == null) {
            LOG.warn("No pipeline graph configuration found.");
            return null;
        }

        // Search through all pipelines for an INITIAL_PIPELINE step that matches the sourceIdentifier
        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipelineConfig = pipelineEntry.getValue();

            if (pipelineConfig == null || pipelineConfig.pipelineSteps() == null) {
                continue;
            }

            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipelineConfig.pipelineSteps().entrySet()) {
                String stepId = stepEntry.getKey();
                PipelineStepConfig stepConfig = stepEntry.getValue();

                if (stepConfig == null) {
                    continue;
                }

                // Check if this is an INITIAL_PIPELINE step and if its ID matches the sourceIdentifier
                if (stepConfig.stepType() == StepType.INITIAL_PIPELINE && stepId.equals(sourceIdentifier)) {
                    LOG.info("Found initial step with ID matching source_identifier {}: pipeline={}, step={}",
                            sourceIdentifier, pipelineName, stepId);
                    return new ConnectorPipelineTarget(pipelineName, stepId);
                }
            }
        }

        // No specific initial step found for this sourceIdentifier
        // Fall back to the default pipeline if configured
        String defaultPipelineName = clusterConfig.defaultPipelineName();
        if (defaultPipelineName != null && !defaultPipelineName.isEmpty()) {
            PipelineConfig defaultPipelineConfig = clusterConfig.pipelineGraphConfig().getPipelineConfig(defaultPipelineName);

            if (defaultPipelineConfig != null && defaultPipelineConfig.pipelineSteps() != null) {
                // Find the first INITIAL_PIPELINE step in the default pipeline
                for (Map.Entry<String, PipelineStepConfig> stepEntry : defaultPipelineConfig.pipelineSteps().entrySet()) {
                    String stepId = stepEntry.getKey();
                    PipelineStepConfig stepConfig = stepEntry.getValue();

                    if (stepConfig != null && stepConfig.stepType() == StepType.INITIAL_PIPELINE) {
                        LOG.info("Using default pipeline's initial step for source_identifier {}: pipeline={}, step={}",
                                sourceIdentifier, defaultPipelineName, stepId);
                        return new ConnectorPipelineTarget(defaultPipelineName, stepId);
                    }
                }

                LOG.warn("Default pipeline '{}' has no INITIAL_PIPELINE steps.", defaultPipelineName);
            } else {
                LOG.warn("Default pipeline '{}' not found in configuration or has no steps.", defaultPipelineName);
            }
        }

        LOG.warn("No matching initial step found for source_identifier {} and no usable default pipeline.", sourceIdentifier);
        return null;
    }

    // Helper record to hold both pipeline name and its specific initial step name from connector config
    private record ConnectorPipelineTarget(String pipelineName, String initialStepName) {
    }
}