package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Defines a type of pipeline module, corresponding to a specific gRPC service implementation.
 * This record is immutable.
 *
 * @param implementationName          The user-friendly display name of this module. Must not be null or blank.
 * @param implementationId            The unique ID of the module (e.g., service ID). This ID is used as the
 *                                    key in PipelineModuleMap.availableModules and typically serves as the
 *                                    'subject' for its schema in the schema registry. Must not be null or blank.
 * @param customConfigSchemaReference A reference to the schema in the registry that defines the structure
 *                                    for this module's custom configuration. Can be null if the module
 *                                    does not have a defined custom configuration schema or accepts any
 *                                    JSON object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record PipelineModuleConfiguration(
        @JsonProperty("implementationName") String implementationName,
        @JsonProperty("implementationId") String implementationId,
        @JsonProperty("customConfigSchemaReference") SchemaReference customConfigSchemaReference
) {
    public PipelineModuleConfiguration {
        if (implementationName == null || implementationName.isBlank()) {
            throw new IllegalArgumentException("PipelineModuleConfiguration implementationName cannot be null or blank.");
        }
        if (implementationId == null || implementationId.isBlank()) {
            throw new IllegalArgumentException("PipelineModuleConfiguration implementationId cannot be null or blank.");
        }
        // customConfigSchemaReference can be null
    }
}
