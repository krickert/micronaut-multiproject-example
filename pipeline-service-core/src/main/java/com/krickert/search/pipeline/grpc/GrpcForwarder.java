// GrpcForwarder.java
package com.krickert.search.pipeline.grpc;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipelineServiceGrpc;
import com.krickert.search.model.Route;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class GrpcForwarder {

    // For demonstration we create a single stub.
    // In practice, you may want to select the stub based on route.getDestination().
    private final PipelineServiceGrpc.PipelineServiceBlockingStub stub;

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

        ManagedChannel channel = builder.build();
        this.stub = PipelineServiceGrpc.newBlockingStub(channel);
    }

    public void forwardToGrpc(PipeStream pipe, Route route) {
        // In a real-world scenario, use route.getDestination() to choose the correct stub.
        // Here we simply call the forward method and ignore the response.
        log.debug("Forwarding to gRPC service: {}", route.getDestination());
        stub.forward(pipe);
    }
}
