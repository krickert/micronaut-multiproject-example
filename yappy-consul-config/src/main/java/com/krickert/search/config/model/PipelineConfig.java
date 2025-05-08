package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.Map;

/**
 * Configuration for a pipeline, which contains a name and a map of pipeline step configurations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineConfig {
    /**
     * The name of the pipeline.
     */
    private String name;

    /**
     * Map of pipeline step configurations, where the key is the step ID.
     */
    private Map<String, PipelineStepConfig> pipelineSteps;
}
