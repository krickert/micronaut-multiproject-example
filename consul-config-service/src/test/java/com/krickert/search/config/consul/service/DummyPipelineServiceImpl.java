package com.krickert.search.config.consul.service;

import com.google.protobuf.Empty;
import com.krickert.search.model.OutputResponse;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipelineServiceGrpc;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dummy implementation of the PipelineService gRPC service for testing registration with Consul.
 * This implementation provides minimal functionality and is intended only for testing purposes.
 */
@Singleton
@GrpcService
@Requires(env = Environment.TEST)
public class DummyPipelineServiceImpl extends PipelineServiceGrpc.PipelineServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(DummyPipelineServiceImpl.class);

    /**
     * Processes a PipeStream and returns an Empty response.
     * This is a dummy implementation that just logs the request and returns an empty response.
     * 
     * @param request The PipeStream to process
     * @param responseObserver The observer to send the response to
     */
    @Override
    public void forward(PipeStream request, StreamObserver<Empty> responseObserver) {
        LOG.info("Received forward request in dummy service: {}", request.getPipeline());

        // Return an empty response
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Processes a PipeRequest and returns an OutputResponse.
     * This is a dummy implementation that just logs the request and returns a success response.
     * 
     * @param request The PipeRequest to process
     * @param responseObserver The observer to send the response to
     */
    @Override
    public void getOutput(PipeRequest request, StreamObserver<OutputResponse> responseObserver) {
        LOG.info("Received getOutput request in dummy service for doc: {}", 
                request.getDoc() != null ? request.getDoc().getId() : "null");

        // Build a simple success response
        OutputResponse response = OutputResponse.newBuilder()
                .setSuccess(true)
                .setOutputDoc(request.getDoc())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
