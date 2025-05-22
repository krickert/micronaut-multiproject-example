package com.krickert.search.pipeline.engine.grpc;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty; // Ensure this is imported
import com.google.protobuf.Timestamp;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.exception.GrpcEngineException;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import io.grpc.ManagedChannel;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.Builder;
// import lombok.Getter; // Not used on the class itself
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Singleton
public class PipeStreamGrpcForwarder {
    private static final Logger log = LoggerFactory.getLogger(PipeStreamGrpcForwarder.class);

    private final GrpcChannelManager channelManager;
    private final ExecutorService executorService; // Executor for handling future callbacks
    private final KafkaForwarder kafkaForwarder;

    @Inject
    public PipeStreamGrpcForwarder(
            GrpcChannelManager channelManager,
            @Named(TaskExecutors.SCHEDULED) ExecutorService executorService, // Inject a suitable executor
            KafkaForwarder kafkaForwarder) {
        this.channelManager = channelManager;
        this.executorService = executorService;
        this.kafkaForwarder = kafkaForwarder;
    }

    /**
     * Forwards a PipeStream message to another gRPC engine instance asynchronously.
     * The target service is determined by the route's destination.
     *
     * @param pipeBuilder The PipeStream builder containing the message state.
     * @param route       The routing information including target pipeline, step, and destination service name.
     * @return A CompletableFuture<Void> that completes when the gRPC call is acknowledged or fails.
     */
    public CompletableFuture<Void> forwardToGrpc(PipeStream.Builder pipeBuilder, RouteData route) {
        CompletableFuture<Void> operationFuture = new CompletableFuture<>();

        if (pipeBuilder == null || route == null || route.destination() == null || route.destination().isBlank()) {
            log.error("Attempted to forward null PipeStream or invalid route data.");
            operationFuture.completeExceptionally(
                    new IllegalArgumentException("Invalid pipeBuilder or route data for gRPC forwarding. Route: " + route + ", PipeBuilder null? " + (pipeBuilder == null))
            );
            return operationFuture;
        }

        // Update the PipeStream state based on the route for the *next* hop
        pipeBuilder.setCurrentPipelineName(route.targetPipeline());
        pipeBuilder.setTargetStepName(route.nextTargetStep());
        pipeBuilder.setStreamId(route.streamId());
        PipeStream pipe = pipeBuilder.build();

        String targetService = route.destination();
        String streamId = pipe.getStreamId();

        log.debug("Attempting to forward PipeStream (streamId: {}) via gRPC to service: {}, target step: {}",
                streamId, targetService, route.nextTargetStep());

        try {
            ManagedChannel channel = channelManager.getChannel(targetService); // Can throw GrpcEngineException

            PipeStreamEngineGrpc.PipeStreamEngineFutureStub stub = PipeStreamEngineGrpc.newFutureStub(channel);
            ListenableFuture<com.google.protobuf.Empty> grpcCallFuture = stub.processPipeAsync(pipe);

            Futures.addCallback(grpcCallFuture, new FutureCallback<com.google.protobuf.Empty>() {
                @Override
                public void onSuccess(com.google.protobuf.Empty result) {
                    log.info("Successfully forwarded PipeStream (streamId: {}) via gRPC to service: {}, intended next target step: {}",
                            streamId, targetService, route.nextTargetStep());
                    operationFuture.complete(null); // Signal success
                }

                @Override
                public void onFailure(Throwable t) {
                    log.error("gRPC call failed for PipeStream (streamId: {}) to service {}: {}",
                            streamId, targetService, t.getMessage(), t);

                    // --- DLQ Logic for gRPC Call Failure ---
                    PipeStream.Builder errorPipeBuilder = pipe.toBuilder();
                    ErrorData.Builder errorDataBuilder = ErrorData.newBuilder()
                            .setErrorMessage("gRPC forwarding failed to service: " + targetService + ". Reason: " + t.getMessage())
                            .setErrorCode("GRPC_FORWARDING_FAILURE")
                            .setTechnicalDetails("Exception: " + t.getClass().getName() + ". Target Service: " + targetService)
                            .setAttemptedTargetStepName(route.nextTargetStep())
                            .setTimestamp(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build());

                    if (!pipe.getHistoryList().isEmpty()) {
                        errorDataBuilder.setOriginatingStepName(pipe.getHistory(pipe.getHistoryCount() - 1).getStepName());
                    } else {
                        errorDataBuilder.setOriginatingStepName("UnknownOrInitialStep");
                    }
                    errorPipeBuilder.setStreamErrorData(errorDataBuilder.build());
                    PipeStream errorPipeStream = errorPipeBuilder.build();

                    log.warn("Sending failed PipeStream (streamId: {}) to DLQ due to gRPC forwarding failure to service: {}", streamId, targetService);
                    kafkaForwarder.forwardToErrorTopic(errorPipeStream, targetService)
                            .whenComplete((metadata, ex) -> {
                                if (ex != null) {
                                    log.error("CRITICAL: Failed to send message (streamId: {}) to DLQ for targetService {}: {}",
                                            streamId, targetService, ex.getMessage(), ex);
                                } else {
                                    log.info("Message (streamId: {}) successfully sent to DLQ for targetService {}. DLQ Topic: error-{}, Partition: {}, Offset: {}",
                                            streamId, targetService, targetService, metadata.partition(), metadata.offset());
                                }
                            });
                    // --- End DLQ Logic ---
                    operationFuture.completeExceptionally(new GrpcEngineException("gRPC call failed to " + targetService + " for step " + route.nextTargetStep(), t));
                }
            }, executorService);

        } catch (GrpcEngineException e) {
            log.error("Failed to get gRPC channel for service {}: {}. StreamId: {}", targetService, e.getMessage(), streamId, e);
            operationFuture.completeExceptionally(e);
        } catch (Exception e) {
            log.error("Unexpected error during gRPC forwarding setup for streamId {}: {}. TargetService: {}", streamId, e.getMessage(), targetService, e);
            operationFuture.completeExceptionally(new GrpcEngineException("Setup error for gRPC call to " + targetService, e));
        }
        return operationFuture;
    }

    @Builder
    public record RouteData(
            String targetPipeline,
            String nextTargetStep,
            String destination, // This is the service name for channelManager
            String streamId
    ) {
    }
}