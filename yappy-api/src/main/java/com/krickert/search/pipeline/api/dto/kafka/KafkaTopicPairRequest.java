package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;

/**
 * Request to create a Kafka topic pair (input + DLQ) for a pipeline step.
 */
@Serdeable
@Schema(description = "Request to create Kafka topic pair for pipeline step")
public record KafkaTopicPairRequest(
    @NotBlank
    @Schema(description = "Pipeline identifier", example = "document-processing")
    String pipelineId,
    
    @NotBlank  
    @Schema(description = "Step identifier", example = "parse-documents")
    String stepId,
    
    @Schema(description = "Number of partitions for input topic", example = "3")
    @Min(1)
    Integer partitions,
    
    @Schema(description = "Replication factor", example = "1")  
    @Min(1)
    Short replicationFactor,
    
    @Schema(description = "Retention time in milliseconds", example = "604800000")
    Long retentionMs,
    
    @Schema(description = "Create DLQ topic as well", example = "true")
    Boolean createDlq
) {
    
    public KafkaTopicPairRequest {
        // Set defaults if not provided
        if (partitions == null) partitions = 3;
        if (replicationFactor == null) replicationFactor = 1;
        if (retentionMs == null) retentionMs = 604800000L; // 7 days
        if (createDlq == null) createDlq = true;
    }
    
    /**
     * Generate the input topic name.
     */
    public String inputTopicName() {
        return String.format("yappy.pipeline.%s.step.%s.input", pipelineId, stepId);
    }
    
    /**
     * Generate the DLQ topic name.
     */
    public String dlqTopicName() {
        return String.format("yappy.pipeline.%s.step.%s.dlq", pipelineId, stepId);
    }
}