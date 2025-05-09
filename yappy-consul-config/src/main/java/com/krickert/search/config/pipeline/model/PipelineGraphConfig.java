package com.krickert.search.config.pipeline.model; // Adjusted package

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
// No Micronaut imports

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineGraphConfig {

    @JsonProperty("pipelines") // Renamed from pipelineGraph for clarity in previous discussion
    private Map<String, PipelineConfig> pipelines;
}