package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Request for VCR-style Kafka consumer operations.
 */
@Serdeable
@Schema(description = "Request for VCR-style Kafka consumer operations")
public record KafkaVcrRequest(
    @NotBlank
    @Schema(description = "Pipeline identifier", example = "document-processing")
    String pipelineId,
    
    @NotBlank
    @Schema(description = "Step identifier", example = "parse-documents")
    String stepId,
    
    @NotBlank
    @Schema(description = "Topic name", example = "yappy.pipeline.document-processing.step.parse-documents.input")
    String topicName,
    
    @NotBlank
    @Schema(description = "Consumer group ID", example = "yappy-engine-default")
    String groupId,
    
    @Schema(description = "Target timestamp for rewind operations")
    Instant targetTimestamp,
    
    @Schema(description = "Partition to target (null for all partitions)")
    Integer partition
) {
    
    /**
     * Create a VCR request for basic operations (pause/resume).
     */
    public static KafkaVcrRequest basic(String pipelineId, String stepId, String topicName, String groupId) {
        return new KafkaVcrRequest(pipelineId, stepId, topicName, groupId, null, null);
    }
    
    /**
     * Create a VCR request for timestamp-based rewind.
     */
    public static KafkaVcrRequest rewind(String pipelineId, String stepId, String topicName, String groupId, Instant timestamp) {
        return new KafkaVcrRequest(pipelineId, stepId, topicName, groupId, timestamp, null);
    }
}