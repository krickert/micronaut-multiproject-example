package com.krickert.search.config.consul.model;

import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request DTO for creating a new pipeline.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class CreatePipelineRequest {
    /**
     * The name of the pipeline to create.
     */
    private String name;
}