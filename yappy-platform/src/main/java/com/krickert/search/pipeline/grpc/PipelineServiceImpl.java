package com.krickert.search.pipeline.grpc;

// --- Project Imports ---
// Consul Configuration Models (adjust paths as needed)
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
//import com.krickert.search.config.consul.model.ServiceDestination; // Assuming this exists
//import com.krickert.search.config.consul.model.InternalPipeStepResponse; // Assuming this exists/is created
// Consul Configuration Services/Exceptions
import com.krickert.search.config.consul.service.ConfigurationService;
import com.krickert.search.config.consul.exception.PipelineNotFoundException;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeStreamEngineGrpc;
import com.krickert.search.model.ProtobufUtils; // Your existing utility

// --- Framework / Library Imports ---
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.InvalidProtocolBufferException; // For JSON parsing
import com.google.protobuf.util.JsonFormat;

import io.grpc.stub.StreamObserver;
import io.grpc.Status;

import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletionException; // Potential from async operations

/**
 * Implementation of the main PipelineService gRPC interface.
 * This service acts as the central orchestrator for pipeline execution,
 * leveraging Consul for configuration and dynamically routing/executing steps.
 */
@Singleton
@GrpcService
public class PipelineServiceImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {

    private static final Logger log = LoggerFactory.getLogger(PipelineServiceImpl.class);

    private final ConfigurationService configurationService;
    private final PipelineRouter pipelineRouter;
    private final PipelineStepExecutor pipelineStepExecutor;
    private final Forwarder forwarder;
    // ProtobufUtils might handle Struct conversion, or inject a dedicated helper
    // private final ProtobufUtils protobufUtils;

    @Inject
    public PipelineServiceImpl(ConfigurationService configurationService,
                               PipelineRouter pipelineRouter,
                               PipelineStepExecutor pipelineStepExecutor,
                               Forwarder forwarder
                              /* ProtobufUtils protobufUtils */) {
        this.configurationService = configurationService;
        this.pipelineRouter = pipelineRouter;
        this.pipelineStepExecutor = pipelineStepExecutor;
        this.forwarder = forwarder;
        // this.protobufUtils = protobufUtils;
    }

    // --- forward RPC Implementation ---

    @Override
    public void forward(PipeStream request, StreamObserver<Empty> responseObserver) {
        String pipelineName = request.getPipeline();
        String streamId = request.getStreamId();

        log.info("Forward request received -Pipeline: '{}' ", pipelineName);

        if (pipelineName == null || pipelineName.isEmpty()) {
            log.error("StreamId: '{}' - Pipeline name missing in forward request.", streamId);
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("PipeStream must contain a valid pipelineName")
                .asRuntimeException());
            return;
        }

        try {
            // 1. Load Configuration from Consul
            PipelineConfig pipelineConfig = configurationService.g(pipelineName)
                .orElseThrow(() -> new PipelineNotFoundException("Pipeline config not found: " + pipelineName));

            // 2. Determine Next Step/Destination using the Router
            PipelineRouter.RoutingResult routingResult = pipelineRouter.determineNextStep(pipelineConfig, currentStepIndex);

            if (routingResult.isEndOfPipeline()) {
                log.info("StreamId: '{}' - Pipeline '{}' completed. No further steps.", streamId, pipelineName);
                // Optional: Final actions (e.g., write completion status somewhere)
                responseObserver.onNext(Empty.newBuilder().build());
                responseObserver.onCompleted();
                return;
            }

            PipeStepConfigurationDto nextStepConfig = routingResult.getNextStepConfig();
            int nextStepIndex = routingResult.getNextStepIndex();

            // 3. Prepare the stream for the next step
            PipeStream.Builder streamBuilder = request.toBuilder()
                .setCurrentStepIndex(nextStepIndex)
                .addHistory(createHistoryEntry("FORWARDING", nextStepConfig, routingResult.getPreviousStepName())); // Add history *before* sending

            // 4. Forward the stream using the appropriate mechanism (Kafka/gRPC)
            log.info("StreamId: '{}' - Forwarding pipeline '{}' to step '{}' ({}) via {}: {}",
                     streamId, pipelineName, nextStepConfig.getStepName(), nextStepIndex,
                     nextStepConfig.getServiceDestination().getType(),
                     nextStepConfig.getServiceDestination().getAddress());

            // Forwarder handles dispatch based on destination type
            forwarder.forward(streamBuilder.build(), nextStepConfig.getServiceDestination());

            // 5. Acknowledge successful forwarding
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
            log.debug("StreamId: '{}' - Forward request completed successfully.", streamId);

        } catch (PipelineNotFoundException e) {
            log.error("StreamId: '{}' - Pipeline configuration '{}' not found: {}", streamId, pipelineName, e.getMessage());
            responseObserver.onError(Status.NOT_FOUND
                .withDescription(e.getMessage())
                .withCause(e)
                .asRuntimeException());
        } catch (IllegalArgumentException e) {
            // Errors likely from routing logic (invalid index etc.)
            log.error("StreamId: '{}' - Invalid argument during forward processing for pipeline '{}': {}", streamId, pipelineName, e.getMessage());
             responseObserver.onError(Status.FAILED_PRECONDITION // Or INVALID