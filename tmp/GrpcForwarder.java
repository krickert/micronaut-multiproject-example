// GrpcForwarder.java
package com.krickert.search.pipeline.grpc;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.Route;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Singleton
@Slf4j
@Getter
public class GrpcForwarder {

    // For demonstration we create a single stub.
    // In practice, you may want to select the stub based on route.getDestination().
    private final ManagedChannel channel;
    private final boolean usePlaintext;

    @Inject
    public GrpcForwarder(@Value("${grpc.client.plaintext:true}") boolean usePlaintext) {
        this.usePlaintext = usePlaintext;
        log.info("Initializing GrpcForwarder with plaintext: {}", usePlaintext);

        // Create a channel to the destination gRPC service (address could be externalized to config)
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress("localhost", 50051);

        if (usePlaintext) {
            builder.usePlaintext();
        }

        this.channel = builder.build();
        //TODO: use the micronaut managed channel factory here instead
        //this.stub = PipelineServiceGrpc.newBlockingStub(channel);
    }

    public void forwardToGrpc(PipeStream pipe, Route route) {
        // In a real-world scenario, use route.getDestination() to choose the correct stub.
        // Here we simply call the forward method and ignore the response.
        log.debug("Forwarding to gRPC service: {}", route.getDestination());
        //noinspection ResultOfMethodCallIgnored
        //stub.forward(pipe);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down gRPC channel");
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Error shutting down gRPC channel", e);
                Thread.currentThread().interrupt();
            } finally {
                if (!channel.isTerminated()) {
                    channel.shutdownNow();
                }
            }
        }
    }
}
