package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * View of a pipeline step with full details.
 */
@Serdeable
@Schema(description = "Detailed view of a pipeline step")
public record PipelineStepView(
    @Schema(description = "Step identifier", example = "text-extraction")
    String id,
    
    @Schema(description = "Module used for processing", example = "tika-parser")
    String module,
    
    @Schema(description = "Step configuration")
    Map<String, Object> config,
    
    @Schema(description = "Next steps in the pipeline")
    List<String> next,
    
    @Schema(description = "Previous steps in the pipeline")
    List<String> previous,
    
    @Schema(description = "Position in the pipeline", example = "1")
    Integer position,
    
    @Schema(description = "Kafka topics associated with this step")
    KafkaTopicsInfo kafkaTopics,
    
    @Schema(description = "Step metadata")
    Map<String, Object> metadata
) {
    
    /**
     * Information about Kafka topics for this step.
     */
    @Serdeable
    @Schema(description = "Kafka topic information for a pipeline step")
    public record KafkaTopicsInfo(
        @Schema(description = "Input topic name", example = "yappy.pipeline.doc-pipeline.step.extraction.input")
        String inputTopic,
        
        @Schema(description = "Output topic name", example = "yappy.pipeline.doc-pipeline.step.extraction.output")
        String outputTopic,
        
        @Schema(description = "Error topic name", example = "yappy.pipeline.doc-pipeline.step.extraction.error")
        String errorTopic,
        
        @Schema(description = "Whether topics have been created", example = "true")
        boolean created
    ) {}
}