package com.krickert.search.pipeline.engine.grpc;

import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.exception.GrpcEngineException;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager; // Import the new manager
import io.grpc.ManagedChannel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.Getter; // Keep if RouteData still needs it
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Singleton
@Getter // If still needed for RouteData or other fields
public class PipeStreamGrpcForwarder {
    private static final Logger log = LoggerFactory.getLogger(PipeStreamGrpcForwarder.class);

    private final GrpcChannelManager channelManager; // Use the new manager

    @Inject
    public PipeStreamGrpcForwarder(GrpcChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    public void forwardToGrpc(PipeStream.Builder pipeBuilder, RouteData route) {
        log.debug("Forwarding to gRPC service: {}", route.destination());
        pipeBuilder.setCurrentPipelineName(route.targetPipeline());
        pipeBuilder.setTargetStepName(route.nextTargetStep());
        pipeBuilder.setStreamId(route.streamId());
        PipeStream pipe = pipeBuilder.build();
        
        try {
            ManagedChannel channel = channelManager.getChannel(route.destination());
            PipeStreamEngineGrpc.PipeStreamEngineBlockingStub stub = PipeStreamEngineGrpc.newBlockingStub(channel);
            // Consider if processPipeAsync is still what you want, or if a blocking call with timeout is better
            // For fire-and-forget, async is fine.
            stub.processPipeAsync(pipe); 
        } catch (GrpcEngineException e) {
            // Handle exception from channelManager (e.g., service not found)
            log.error("Failed to forward to gRPC service {}: {}", route.destination(), e.getMessage(), e);
            // Decide on error handling strategy: throw, log, dead-letter queue, etc.
            // For now, rethrowing or wrapping might be appropriate.
            throw new GrpcEngineException("Failed to forward to " + route.destination(), e);
        }
    }

    @Builder // Assuming RouteData is still used and built this way
    public record RouteData(
            String targetPipeline,
            String nextTargetStep,
            String destination,
            String streamId
            ) {
    }
}