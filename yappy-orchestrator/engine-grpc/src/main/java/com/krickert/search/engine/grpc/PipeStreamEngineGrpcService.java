package com.krickert.search.engine.grpc;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.engine.ProcessResponse;
import com.krickert.search.engine.ProcessStatus;
import com.krickert.search.model.PipeStream;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * gRPC service implementation for PipeStreamEngine that publishes events
 * instead of directly processing requests. This enables the same event-driven
 * architecture as kafka-service, making the engine transport-agnostic.
 * 
 * Flow:
 * 1. gRPC request received
 * 2. Convert to PipeStreamProcessingEvent
 * 3. Publish event
 * 4. Return acknowledgment to caller
 * 5. Actual processing happens asynchronously via event listeners
 */
@GrpcService
public class PipeStreamEngineGrpcService extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    
    private static final Logger logger = LoggerFactory.getLogger(PipeStreamEngineGrpcService.class);
    
    private final ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher;
    
    @Inject
    public PipeStreamEngineGrpcService(ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher) {
        this.eventPublisher = eventPublisher;
        logger.info("PipeStreamEngineGrpcService initialized - gRPC to event bridge ready");
    }
    
    @Override
    public void processPipeAsync(PipeStream request, StreamObserver<ProcessResponse> responseObserver) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        
        try {
            // Validate request
            if (request == null || !request.hasDocument()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("PipeStream must contain a document")
                    .asException());
                return;
            }
            
            String streamId = request.getStreamId();
            String pipeline = request.getCurrentPipelineName();
            String targetStep = request.getTargetStepName();
            
            logger.info("Received gRPC request - streamId: {}, pipeline: {}, target: {}, requestId: {}", 
                streamId, pipeline, targetStep, requestId);
            
            // Convert gRPC request to event
            PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromGrpc(
                request,
                "PipeStreamEngine",
                "processPipeAsync"
            );
            
            // Add request ID to correlation for tracking
            if (!request.containsContextParams("requestId")) {
                request = request.toBuilder()
                    .putContextParams("requestId", requestId)
                    .build();
                event = PipeStreamProcessingEvent.fromGrpc(
                    request,
                    "PipeStreamEngine", 
                    "processPipeAsync"
                );
            }
            
            // Publish event for asynchronous processing
            logger.debug("Publishing PipeStreamProcessingEvent for streamId: {}, requestId: {}", streamId, requestId);
            eventPublisher.publishEvent(event);
            
            // Return immediate acknowledgment
            ProcessResponse response = ProcessResponse.newBuilder()
                .setStreamId(streamId)
                .setStatus(ProcessStatus.ACCEPTED)
                .setMessage("Request accepted for processing")
                .setRequestId(requestId)
                .setTimestamp(Instant.now().toEpochMilli())
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("gRPC request acknowledged - streamId: {}, requestId: {}, duration: {}ms", 
                streamId, requestId, duration);
            
        } catch (Exception e) {
            logger.error("Error processing gRPC request - requestId: {}, error: {}", 
                requestId, e.getMessage(), e);
            
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error processing request: " + e.getMessage())
                .withCause(e)
                .asException());
        }
    }
    
    @Override
    public StreamObserver<PipeStream> processPipeStream(StreamObserver<ProcessResponse> responseObserver) {
        return new StreamObserver<PipeStream>() {
            private int messageCount = 0;
            private final String sessionId = UUID.randomUUID().toString();
            private final long startTime = System.currentTimeMillis();
            
            @Override
            public void onNext(PipeStream pipeStream) {
                messageCount++;
                String streamId = pipeStream.getStreamId();
                
                logger.debug("Stream message {} received - streamId: {}, sessionId: {}", 
                    messageCount, streamId, sessionId);
                
                try {
                    // Convert to event and publish
                    PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromGrpc(
                        pipeStream,
                        "PipeStreamEngine",
                        "processPipeStream"
                    );
                    
                    eventPublisher.publishEvent(event);
                    
                    // Send acknowledgment for this message
                    ProcessResponse response = ProcessResponse.newBuilder()
                        .setStreamId(streamId)
                        .setStatus(ProcessStatus.ACCEPTED)
                        .setMessage("Stream message " + messageCount + " accepted")
                        .setRequestId(sessionId)
                        .setTimestamp(Instant.now().toEpochMilli())
                        .build();
                    
                    responseObserver.onNext(response);
                    
                } catch (Exception e) {
                    logger.error("Error processing stream message {} - streamId: {}, sessionId: {}, error: {}", 
                        messageCount, streamId, sessionId, e.getMessage(), e);
                    
                    // Send error response but don't close the stream
                    ProcessResponse errorResponse = ProcessResponse.newBuilder()
                        .setStreamId(streamId)
                        .setStatus(ProcessStatus.ERROR)
                        .setMessage("Error processing message: " + e.getMessage())
                        .setRequestId(sessionId)
                        .setTimestamp(Instant.now().toEpochMilli())
                        .build();
                    
                    responseObserver.onNext(errorResponse);
                }
            }
            
            @Override
            public void onError(Throwable t) {
                long duration = System.currentTimeMillis() - startTime;
                logger.error("Stream error - sessionId: {}, messages: {}, duration: {}ms, error: {}", 
                    sessionId, messageCount, duration, t.getMessage(), t);
                responseObserver.onError(t);
            }
            
            @Override
            public void onCompleted() {
                long duration = System.currentTimeMillis() - startTime;
                logger.info("Stream completed - sessionId: {}, messages: {}, duration: {}ms", 
                    sessionId, messageCount, duration);
                responseObserver.onCompleted();
            }
        };
    }
}