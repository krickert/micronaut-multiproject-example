package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine; // Your core engine interface
// Remove other imports that are now only needed in DefaultPipeStreamEngineLogicImpl
// (like KafkaForwarder, PipeStepExecutorFactory, DynamicConfigurationManager, specific model details if not used in testPipeStream's direct logic)

import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
// No @Singleton needed if @GrpcService implies it, or if you want to explicitly make it one.
// For clarity and consistency, @Singleton is usually fine alongside @GrpcService.
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Removed unused imports related to detailed logic

@Singleton // Explicitly making it a singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase { // REMOVE "implements PipeStreamEngine"
    private static final Logger log = LoggerFactory.getLogger(PipeStreamEngineImpl.class);

    private final PipeStreamEngine coreEngine; // Inject the interface

    // Define keys for context params in testPipeStream as constants - KEEP THESE HERE
    private static final String TEST_ROUTE_PREFIX = "route_";
    // ... other TEST_ROUTE constants ...

    @Inject
    public PipeStreamEngineImpl(PipeStreamEngine coreEngine) { // Injecting your core engine
        this.coreEngine = coreEngine;
    }

    @Override
    public void testPipeStream(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        // This method's logic might largely stay here if it's specific to gRPC testing
        // and response construction, or it could delegate parts to coreEngine if applicable.
        // For now, assuming its existing complex logic stays here or is refactored carefully.
        // If it calls the main processing logic, it should call coreEngine.processStream()
        // or a similar method if testPipeStream needs a synchronous response.
        // Given its nature, it might need its own simplified execution path or call a
        // synchronous version if you add one to PipeStreamEngine interface.

        // For now, let's assume your current testPipeStream logic remains here.
        // You'll need to ensure its dependencies (like executorFactory, configManager)
        // are available if they are not passed to the coreEngine.
        // This method might need its own injection of those if it doesn't go through coreEngine.
        // Easiest for now: testPipeStream might be an exception and keep its direct dependencies if it can't use coreEngine.processStream
        log.warn("PipeStreamEngineImpl.testPipeStream is called - ensure its logic is correctly refactored or uses coreEngine appropriately.");
        // For simplicity of this refactor step, I'm assuming testPipeStream will need more thought.
        // The critical part is processPipeAsync.
        // A simple (but perhaps incorrect for your full test logic) delegation:
        // coreEngine.processStream(request); // This is async, testPipeStream is sync
        // responseObserver.onNext(request); // This is likely wrong, testPipeStream expects transformed output
        // responseObserver.onCompleted();
        // --> You will need to refactor how testPipeStream works. It probably shouldn't
        //     just call the async processStream. It might need a synchronous method on the coreEngine.
        // For now, to make it compile and focus on processPipeAsync:
        log.error("testPipeStream needs refactoring after core logic moved. Returning error for now.");
        responseObserver.onError(io.grpc.Status.UNIMPLEMENTED.withDescription("testPipeStream needs refactoring").asRuntimeException());
    }

    @Override
    public void processPipeAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
        try {
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                log.error("gRPC processPipeAsync called with invalid request: targetStepName missing. StreamId: {}", request.getStreamId());
                responseObserver.onError(new IllegalArgumentException("Target step name must be set in the request"));
                return;
            }

            // Delegate to the core engine
            coreEngine.processStream(request); // This is the call to your new core logic

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in gRPC PipeStreamEngineImpl.processPipeAsync for streamId {}: {}", request.getStreamId(), e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to process pipe: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }
    // The methods executeStepAndForward, findConnectorStepDetailsByIdentifier,
    // handleFailedDispatch, handleFailedStepExecutionOrRouting are MOVED to DefaultPipeStreamEngineLogicImpl
}