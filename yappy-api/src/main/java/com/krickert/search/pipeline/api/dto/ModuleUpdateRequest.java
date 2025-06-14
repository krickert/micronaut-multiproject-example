package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Request to update module registration.
 * All fields are optional - only provided fields will be updated.
 */
@Serdeable
@Schema(description = "Module update request")
public record ModuleUpdateRequest(
    @Schema(description = "Updated display name")
    String name,
    
    @Schema(description = "Updated description")
    String description,
    
    @Schema(description = "Updated version")
    String version,
    
    @Schema(description = "Updated host (for re-deployment)")
    String host,
    
    @Schema(description = "Updated port")
    Integer port,
    
    @Schema(description = "Updated capabilities")
    List<String> capabilities,
    
    @Schema(description = "Updated input types")
    List<String> inputTypes,
    
    @Schema(description = "Updated output types")
    List<String> outputTypes,
    
    @Schema(description = "Updated configuration schema")
    Map<String, Object> configSchema,
    
    @Schema(description = "Updated metadata")
    Map<String, String> metadata
) {}