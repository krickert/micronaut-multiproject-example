package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response from VCR-style Kafka consumer operations.
 */
@Serdeable
@Schema(description = "Response from VCR-style Kafka consumer operations")
public record KafkaVcrResponse(
    @Schema(description = "Pipeline identifier", example = "document-processing")
    String pipelineId,
    
    @Schema(description = "Step identifier", example = "parse-documents")
    String stepId,
    
    @Schema(description = "Topic name", example = "yappy.pipeline.document-processing.step.parse-documents.input")
    String topicName,
    
    @Schema(description = "Consumer group ID", example = "yappy-engine-default")
    String groupId,
    
    @Schema(description = "Operation performed", example = "PAUSE")
    String operation,
    
    @Schema(description = "Whether the operation was successful")
    boolean success,
    
    @Schema(description = "When the operation was performed")
    Instant timestamp,
    
    @Schema(description = "Error message if operation failed")
    String errorMessage,
    
    @Schema(description = "Additional context about the operation")
    String message
) {
    
    public static KafkaVcrResponse success(String pipelineId, String stepId, String topicName, 
                                          String groupId, String operation, String message) {
        return new KafkaVcrResponse(
            pipelineId, stepId, topicName, groupId, operation, 
            true, Instant.now(), null, message
        );
    }
    
    public static KafkaVcrResponse error(String pipelineId, String stepId, String topicName, 
                                        String groupId, String operation, String errorMessage) {
        return new KafkaVcrResponse(
            pipelineId, stepId, topicName, groupId, operation, 
            false, Instant.now(), errorMessage, null
        );
    }
}