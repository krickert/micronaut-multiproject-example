package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response after creating a Kafka topic pair (input + DLQ).
 */
@Serdeable
@Schema(description = "Response after creating Kafka topic pair")
public record KafkaTopicPairResponse(
    @Schema(description = "Pipeline identifier", example = "document-processing")
    String pipelineId,
    
    @Schema(description = "Step identifier", example = "parse-documents")
    String stepId,
    
    @Schema(description = "Input topic name", example = "yappy.pipeline.document-processing.step.parse-documents.input")
    String inputTopicName,
    
    @Schema(description = "DLQ topic name", example = "yappy.pipeline.document-processing.step.parse-documents.dlq")
    String dlqTopicName,
    
    @Schema(description = "Number of partitions created", example = "3")
    int partitions,
    
    @Schema(description = "Replication factor", example = "1")
    short replicationFactor,
    
    @Schema(description = "When the topics were created")
    Instant createdAt,
    
    @Schema(description = "Whether both topics were created successfully")
    boolean success,
    
    @Schema(description = "Any error message if creation failed")
    String errorMessage
) {
    
    public static KafkaTopicPairResponse success(String pipelineId, String stepId, 
                                                String inputTopic, String dlqTopic, 
                                                int partitions, short replicationFactor) {
        return new KafkaTopicPairResponse(
            pipelineId, stepId, inputTopic, dlqTopic, 
            partitions, replicationFactor, Instant.now(), true, null
        );
    }
    
    public static KafkaTopicPairResponse error(String pipelineId, String stepId, String errorMessage) {
        return new KafkaTopicPairResponse(
            pipelineId, stepId, null, null, 0, (short) 0, Instant.now(), false, errorMessage
        );
    }
}