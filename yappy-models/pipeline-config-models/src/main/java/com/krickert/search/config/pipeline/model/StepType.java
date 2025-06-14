package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Defines the type of a pipeline step, which affects its validation rules and behavior.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
@Schema(description = "Type of pipeline step", enumAsRef = true)
public enum StepType {
    /**
     * Standard pipeline step that can have both inputs and outputs.
     */
    PIPELINE,

    /**
     * Initial pipeline step that can only have outputs, not inputs.
     * These steps serve as entry points to the pipeline.
     */
    INITIAL_PIPELINE,

    /**
     * Terminal pipeline step that can have inputs but no outputs.
     */
    SINK
}