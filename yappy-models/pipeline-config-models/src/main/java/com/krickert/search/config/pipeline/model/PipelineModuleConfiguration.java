package com.krickert.search.config.pipeline.model; // Adjusted package

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
// No Micronaut imports

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineModuleConfiguration {

    @JsonProperty("implementationName")
    private String implementationName;

    @JsonProperty("implementationId")
    private String implementationId;

    @JsonProperty("customConfigSchemaReference")
    private SchemaReference customConfigSchemaReference;
}