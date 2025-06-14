package com.krickert.search.pipeline.api.dto;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request to create a complex pipeline.
 */
@Serdeable
@Schema(description = "Request to create a complex pipeline")
public record PipelineCreateRequest(
    @NotBlank
    @Schema(description = "Pipeline name")
    String name,
    
    @NotNull
    @Schema(description = "Full pipeline configuration")
    PipelineConfig pipelineConfig,
    
    @Schema(description = "Cluster to create pipeline in")
    String cluster
) {}