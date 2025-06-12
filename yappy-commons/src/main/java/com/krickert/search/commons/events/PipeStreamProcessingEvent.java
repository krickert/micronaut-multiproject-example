package com.krickert.search.commons.events;

import com.krickert.search.model.PipeStream;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Event published when a PipeStream needs to be processed.
 * This replaces direct calls to PipeStreamEngine, enabling decoupled event-driven processing.
 */
@Introspected
public class PipeStreamProcessingEvent extends BaseEvent {
    
    private final PipeStream pipeStream;
    private final String targetPipeline;
    private final String targetStep;
    private final Map<String, Object> processingContext;
    
    public PipeStreamProcessingEvent(
            @NonNull PipeStream pipeStream,
            @Nullable String targetPipeline,
            @Nullable String targetStep,
            @NonNull EventMetadata metadata,
            @NonNull Map<String, Object> processingContext) {
        super(metadata);
        this.pipeStream = pipeStream;
        this.targetPipeline = targetPipeline;
        this.targetStep = targetStep;
        this.processingContext = processingContext;
    }
    
    /**
     * Factory method for creating event from Kafka message.
     */
    public static PipeStreamProcessingEvent fromKafka(
            PipeStream pipeStream,
            String topic,
            int partition,
            long offset,
            String consumerGroup) {
        
        String correlationId = extractCorrelationId(pipeStream);
        
        EventMetadata metadata = EventMetadata.createWithHeaders(
            correlationId,
            "kafka-listener",
            "1.0.0",
            Map.of(
                "kafka.topic", topic,
                "kafka.consumer.group", consumerGroup
            )
        );
        
        Map<String, Object> context = Map.of(
            "kafka.topic", topic,
            "kafka.partition", partition,
            "kafka.offset", offset,
            "kafka.consumer.group", consumerGroup
        );
        
        return new PipeStreamProcessingEvent(
            pipeStream,
            extractTargetPipeline(pipeStream),
            extractTargetStep(pipeStream),
            metadata,
            context
        );
    }
    
    /**
     * Factory method for creating event from gRPC call.
     */
    public static PipeStreamProcessingEvent fromGrpc(
            PipeStream pipeStream,
            String serviceName,
            String methodName) {
        
        String correlationId = extractCorrelationId(pipeStream);
        
        EventMetadata metadata = EventMetadata.createWithHeaders(
            correlationId,
            "grpc-gateway",
            "1.0.0",
            Map.of(
                "grpc.service", serviceName,
                "grpc.method", methodName
            )
        );
        
        Map<String, Object> context = Map.of(
            "grpc.service", serviceName,
            "grpc.method", methodName
        );
        
        return new PipeStreamProcessingEvent(
            pipeStream,
            extractTargetPipeline(pipeStream),
            extractTargetStep(pipeStream),
            metadata,
            context
        );
    }
    
    /**
     * Factory method for creating event from internal routing.
     */
    public static PipeStreamProcessingEvent fromInternal(
            PipeStream pipeStream,
            String sourceStep,
            String targetPipeline,
            String targetStep) {
        
        String correlationId = extractCorrelationId(pipeStream);
        
        EventMetadata metadata = EventMetadata.create(
            correlationId,
            "internal-router",
            "1.0.0"
        );
        
        Map<String, Object> context = Map.of(
            "source.step", sourceStep
        );
        
        return new PipeStreamProcessingEvent(
            pipeStream,
            targetPipeline,
            targetStep,
            metadata,
            context
        );
    }
    
    // Helper methods
    private static String extractCorrelationId(PipeStream pipeStream) {
        // Check if there's a correlationId in context params
        if (pipeStream.getContextParamsCount() > 0 &&
            pipeStream.containsContextParams("correlationId")) {
            return pipeStream.getContextParamsOrThrow("correlationId");
        }
        // Generate a new correlation ID if not present
        return UUID.randomUUID().toString();
    }
    
    private static String extractTargetPipeline(PipeStream pipeStream) {
        // For now, pipeline routing will be handled externally
        // This can be enhanced when we have routing metadata in PipeStream
        return null;
    }
    
    private static String extractTargetStep(PipeStream pipeStream) {
        // For now, step routing will be handled externally
        // This can be enhanced when we have routing metadata in PipeStream
        return null;
    }
    
    // Getters
    public PipeStream getPipeStream() {
        return pipeStream;
    }
    
    @Nullable
    public String getTargetPipeline() {
        return targetPipeline;
    }
    
    @Nullable
    public String getTargetStep() {
        return targetStep;
    }
    
    public Map<String, Object> getProcessingContext() {
        return processingContext;
    }
}