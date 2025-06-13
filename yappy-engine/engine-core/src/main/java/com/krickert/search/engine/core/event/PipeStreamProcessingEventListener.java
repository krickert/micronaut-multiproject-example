package com.krickert.search.engine.core.event;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.engine.core.PipelineEngine;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Event listener that bridges the event-driven architecture with the existing
 * PipelineEngine implementation. This listener receives PipeStreamProcessingEvent
 * instances and delegates the processing to the PipelineEngine.
 * 
 * This design allows the system to be event-driven while preserving the existing
 * routing and processing logic.
 */
@Singleton
@Requires(property = "engine.event-driven.enabled", value = "true", defaultValue = "true")
public class PipeStreamProcessingEventListener implements ApplicationEventListener<PipeStreamProcessingEvent> {
    
    private static final Logger logger = LoggerFactory.getLogger(PipeStreamProcessingEventListener.class);
    
    private final PipelineEngine pipelineEngine;
    
    @Inject
    public PipeStreamProcessingEventListener(PipelineEngine pipelineEngine) {
        this.pipelineEngine = pipelineEngine;
        logger.info("PipeStreamProcessingEventListener initialized - event-driven processing enabled");
    }
    
    @Override
    @Async // Process events asynchronously to avoid blocking the event publisher
    public void onApplicationEvent(PipeStreamProcessingEvent event) {
        logger.debug("Received PipeStreamProcessingEvent from source: {}, streamId: {}", 
            event.getSourceModule(), event.getPipeStream().getStreamId());
        
        // Extract metadata for enhanced logging
        String streamId = event.getPipeStream().getStreamId();
        String pipeline = event.getPipeStream().getCurrentPipelineName();
        String targetStep = event.getPipeStream().getTargetStepName();
        
        logger.info("Processing event for stream: {}, pipeline: {}, target: {}, source: {}", 
            streamId, pipeline, targetStep, event.getSourceModule());
        
        // Log context-specific metadata if available
        Map<String, Object> context = event.getProcessingContext();
        String sourceModule = event.getSourceModule();
        
        if (sourceModule.contains("kafka")) {
            logger.debug("Kafka metadata - Topic: {}, Partition: {}, Offset: {}, Group: {}", 
                context.get("kafka.topic"), context.get("kafka.partition"), 
                context.get("kafka.offset"), context.get("kafka.consumer.group"));
        } else if (sourceModule.contains("grpc")) {
            logger.debug("gRPC metadata - Service: {}, Method: {}", 
                context.get("grpc.service"), context.get("grpc.method"));
        } else if (sourceModule.contains("internal")) {
            logger.debug("Internal source - Source step: {}", context.get("source.step"));
        }
        
        // Delegate to the pipeline engine for actual processing
        processPipeStream(event)
            .doOnSuccess(v -> logger.debug("Successfully processed event for stream: {}", streamId))
            .doOnError(error -> logger.error("Failed to process event for stream: {}, error: {}", 
                streamId, error.getMessage(), error))
            .subscribe(); // Subscribe to execute the reactive chain
    }
    
    /**
     * Process the PipeStream through the pipeline engine.
     * This method wraps the processing in error handling and monitoring.
     */
    private Mono<Void> processPipeStream(PipeStreamProcessingEvent event) {
        return pipelineEngine.processMessage(event.getPipeStream())
            .doOnSubscribe(s -> {
                if (logger.isTraceEnabled()) {
                    logger.trace("Starting pipeline processing for stream: {}", 
                        event.getPipeStream().getStreamId());
                }
            })
            .doOnTerminate(() -> {
                if (logger.isTraceEnabled()) {
                    logger.trace("Completed pipeline processing for stream: {}", 
                        event.getPipeStream().getStreamId());
                }
            })
            .onErrorResume(error -> {
                // Log the error but don't propagate it to prevent affecting other events
                logger.error("Error processing PipeStream {} from source {}: {}", 
                    event.getPipeStream().getStreamId(), 
                    event.getSourceModule(), 
                    error.getMessage(), 
                    error);
                return Mono.empty();
            });
    }
    
    @Override
    public boolean supports(PipeStreamProcessingEvent event) {
        // We support all PipeStreamProcessingEvent instances
        return event != null && event.getPipeStream() != null;
    }
}