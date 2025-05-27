package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.inject.Provider; // Import Provider
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    private static final Logger log = LoggerFactory.getLogger(PipeStreamEngineImpl.class);

    private final Provider<PipeStreamEngine> coreEngineProvider; // 👈 Use Provider

    @Inject
    public PipeStreamEngineImpl(Provider<PipeStreamEngine> coreEngineProvider) { // 👈 Inject Provider
        this.coreEngineProvider = coreEngineProvider;
    }

    @Override
    public void processPipeAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
        try {
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                // ... error handling ...
                responseObserver.onError(new IllegalArgumentException("Target step name must be set in the request"));
                return;
            }

            coreEngineProvider.get().processStream(request); // 👈 Get the bean when needed

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            // ... error handling ...
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to process pipe: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void testPipeStream(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        // If this method also uses coreEngine, it should also use coreEngineProvider.get()
        // For now, assuming it's still placeholder or has its own logic
        log.error("testPipeStream needs refactoring after core logic moved. Returning error for now.");
        responseObserver.onError(io.grpc.Status.UNIMPLEMENTED.withDescription("testPipeStream needs refactoring").asRuntimeException());
    }
}