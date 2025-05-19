package com.krickert.search.engine.grpc;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.DynamicConfigurationManagerImpl;
import com.krickert.search.engine.IngestDataRequest;
import com.krickert.search.engine.IngestDataResponse;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.engine.service.PipelineOrchestrator;
import com.krickert.search.model.PipeStream;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig; // Assuming this is your main config wrapper
import com.krickert.search.config.pipeline.model.PipelineConfig;       // Your pipeline config model

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStreamEngineImpl.class);
    private final String cluster;
    private final String pipelineName;
    private final DynamicConfigurationManager configProvider;
    private final PipelineOrchestrator orchestrator;

    @Inject
    public PipeStreamEngineImpl(DynamicConfigurationManager configProvider,
                                PipelineOrchestrator orchestrator,
                                @Value("${pipeline.cluster.name}") String cluster,
                                @Value("${micronaut.application.name}") String pipelineName) {
        this.configProvider = configProvider;
        this.orchestrator = orchestrator;
        this.cluster = cluster;
        this.pipelineName = pipelineName;
    }

    @Override
    public void ingestDataAsync(IngestDataRequest request, StreamObserver<IngestDataResponse> responseObserver) {
        LOG.info("Received IngestDataAsync request for source_identifier: {}", request.getSourceIdentifier());

        // 1. Basic Validation
        if (request.getSourceIdentifier() == null || request.getSourceIdentifier().isEmpty()) {
            LOG.error("Missing source_identifier in IngestDataRequest.");
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("source_identifier is required.")
                    .asRuntimeException());
            return;
        }
        if (!request.hasDocument() || request.getDocument().getId().isEmpty()) {
            LOG.error("Missing document or document ID in IngestDataRequest for source_identifier: {}", request.getSourceIdentifier());
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("document with a valid id is required.")
                    .asRuntimeException());
            return;
        }

        // 2. Stream ID Generation
        String streamId = request.hasSuggestedStreamId() && !request.getSuggestedStreamId().isEmpty()
                ? request.getSuggestedStreamId()
                : UUID.randomUUID().toString();
        LOG.debug("Using stream_id: {} for source_identifier: {}", streamId, request.getSourceIdentifier());

        // 3. Config Lookup
        PipelineClusterConfig clusterConfig = configProvider.getCurrentPipelineClusterConfig().orElse(null);
        if (clusterConfig == null || clusterConfig.pipelineGraphConfig() == null) {
            LOG.error("PipelineClusterConfig or PipelineConfigs map is not loaded. Cannot process request for source_identifier: {}", request.getSourceIdentifier());
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Engine configuration not loaded. Cannot find pipeline for source_identifier: " + request.getSourceIdentifier())
                    .asRuntimeException());
            return;
        }

        PipelineConfig targetPipelineConfigOpt = clusterConfig.pipelineGraphConfig().pipelines().get(this.pipelineName);

        if (targetPipelineConfigOpt.isEmpty()) {
            LOG.warn("No pipeline found for source_identifier: {}", request.getSourceIdentifier());
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No pipeline configured for source_identifier: " + request.getSourceIdentifier())
                    .asRuntimeException());
            return;
        }
        PipelineConfig targetPipeline = targetPipelineConfigOpt.get();
        String pipelineName = targetPipeline.getPipelineName(); // Or however you get the unique name/ID of the PipelineConfig
        String initialStepName = targetPipeline.getInitialStepName(); // Assuming this field exists and is set

        if (initialStepName == null || initialStepName.isEmpty()) {
            LOG.error("Pipeline '{}' for source_identifier '{}' does not have an initial_step_name defined.", pipelineName, request.getSourceIdentifier());
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Pipeline " + pipelineName + " does not have an initial_step_name defined.")
                    .asRuntimeException());
            return;
        }
        LOG.info("Found pipeline: '{}' with initial step: '{}' for source_identifier: {}", pipelineName, initialStepName, request.getSourceIdentifier());


        // 4. PipeStream Creation
        PipeStream.Builder pipeStreamBuilder = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(request.getDocument())
                .setCurrentPipelineName(pipelineName) // Use the actual pipeline name/ID from config
                .setTargetStepName(initialStepName)
                .setCurrentHopNumber(1); // First hop

        if (request.getInitialContextParamsCount() > 0) {
            pipeStreamBuilder.putAllContextParams(request.getInitialContextParamsMap());
        }

        PipeStream initialPipeStream = pipeStreamBuilder.build();

        // 5. Delegation (Asynchronous)
        try {
            orchestrator.processStream(initialPipeStream); // This should ideally be non-blocking or run in a separate thread pool managed by orchestrator
            LOG.debug("Delegated initial PipeStream to orchestrator for stream_id: {}", streamId);
        } catch (Exception e) {
            LOG.error("Error delegating PipeStream to orchestrator for stream_id: {}", streamId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to initiate pipeline processing: " + e.getMessage())
                    .augmentDescription(e.getClass().getName())
                    .withCause(e)
                    .asRuntimeException());
            return;
        }

        // 6. Response
        IngestDataResponse response = IngestDataResponse.newBuilder()
                .setStreamId(streamId)
                .setAccepted(true)
                .setMessage("Ingestion accepted for stream ID " + streamId + ", targeting pipeline '" + pipelineName + "'.")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
        LOG.info("Successfully accepted IngestDataAsync request for stream_id: {}, source_identifier: {}", streamId, request.getSourceIdentifier());
    }

    @Override
    public void process(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        //TODO: Implement this method
    }

    @Override
    public void processAsync(PipeStream request, StreamObserver<com.google.protobuf.Empty> responseObserver) {
        LOG.info("Received processAsync request for stream_id: {}, target_step: {}", request.getStreamId(), request.getTargetStepName());
        try {
            // This assumes the PipeStream coming in is already well-formed and represents a specific point in an existing flow
            // (e.g., from a Kafka listener that re-injects it into the orchestration)
            orchestrator.processStream(request);
            responseObserver.onNext(com.google.protobuf.Empty.newBuilder().build());
            responseObserver.onCompleted();
            LOG.debug("Delegated PipeStream via processAsync to orchestrator for stream_id: {}", request.getStreamId());
        } catch (Exception e) {
            LOG.error("Error in processAsync for stream_id: {}", request.getStreamId(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to process async stream: " + e.getMessage())
                    .augmentDescription(e.getClass().getName())
                    .withCause(e)
                    .asRuntimeException());
        }
    }
}