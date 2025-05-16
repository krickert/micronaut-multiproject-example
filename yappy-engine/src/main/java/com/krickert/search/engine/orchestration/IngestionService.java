package com.krickert.search.engine.orchestration;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
// Assuming PipelineConnectorSpec is a class/record in your model that now includes initialTargetStepName
// import com.krickert.search.config.pipeline.model.PipelineConnectorSpec;
import com.krickert.search.config.pipeline.model.PipelineStepConfig; // For step validation if needed
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

@Singleton
public class IngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);

    private final DynamicConfigurationManager configManager;
    private final AsyncPipelineProcessor asyncPipelineProcessor;

    // Helper record to hold both pipeline name and its specific initial step name from connector config
    private record ConnectorPipelineTarget(String pipelineName, String initialStepName) {}

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
                public void onCompleted() {}
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
        // 1. Specific mapping (pipeline + initialStepName from connector spec)
        // 2. Default pipeline + (default pipeline's own initial step if connector spec did NOT specify initialStepName)

        var connectorSpec = clusterConfig.pipelineGraphConfig().pipelines().get(sourceIdentifier);

        if (connectorSpec != null) {
            String targetPipelineName = connectorSpec.pipelineSteps().get;
            String initialStepName = connectorSpec.pipelineSteps().; // New field in ConnectorSpec

            if (targetPipelineName != null && !targetPipelineName.isEmpty()) {
                if (initialStepName != null && !initialStepName.isEmpty()) {
                    LOG.info("Found specific pipeline mapping for source_identifier {}: pipeline={}, initial_step={}", sourceIdentifier, targetPipelineName, initialStepName);
                    return new ConnectorPipelineTarget(targetPipelineName, initialStepName); //Explicit initial step
                } else {
                    //If the connector specifies a pipeline, but not an initial step, use the pipeline's default initial step.
                    //This is a fallback.

                    PipelineConfig pipelineConfig = Optional.ofNullable(clusterConfig.pipelineGraphConfig())
                        .map(graphConfig -> graphConfig.getPipelineConfig(targetPipelineName))
                        .orElse(null);

                    if (pipelineConfig != null && pipelineConfig.getInitialStepName() != null && !pipelineConfig.getInitialStepName().isEmpty()) {
                        LOG.info("Found pipeline mapping for source_identifier {} without explicit initial step. Using default initial step from pipeline {}: step={}", sourceIdentifier, targetPipelineName, pipelineConfig.getInitialStepName());
                        return new ConnectorPipelineTarget(targetPipelineName, pipelineConfig.getInitialStepName());
                    } else {
                        LOG.warn("Connector specifies pipeline '{}' for source '{}', but it has no initial step configured (nor does the pipeline itself).", targetPipelineName, sourceIdentifier);
                        return null; //Invalid configuration - need both pipeline AND a valid initial step
                    }
                }
            }
        }

        // No specific mapping found (or incomplete). Use default pipeline.
        String defaultPipelineName = clusterConfig.getDefaultPipelineName();
        if (defaultPipelineName != null && !defaultPipelineName.isEmpty()) {
            PipelineConfig defaultPipelineConfig = Optional.ofNullable(clusterConfig.pipelineGraphConfig())
                .map(graphConfig -> graphConfig.getPipelineConfig(defaultPipelineName))
                .orElse(null);

            if (defaultPipelineConfig != null && defaultPipelineConfig.getInitialStepName() != null && !defaultPipelineConfig.getInitialStepName().isEmpty()) {
                LOG.info("No specific pipeline mapping found for source_identifier {}. Using default pipeline: pipeline={}, initial_step={}", sourceIdentifier, defaultPipelineName, defaultPipelineConfig.getInitialStepName());
                return new ConnectorPipelineTarget(defaultPipelineName, defaultPipelineConfig.getInitialStepName());
            } else {
                LOG.error("Default pipeline '{}' is configured, but it has no initial step defined (or the config is invalid).", defaultPipelineName);
                return null; // Default pipeline misconfigured
            }
        } else {
            LOG.warn("No specific pipeline mapping found for source_identifier {} and no default pipeline configured.", sourceIdentifier);
            return null; // No pipeline configured at all (neither specific nor default)
        }
    }
}