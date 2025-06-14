package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Response containing generated test data.
 */
@Serdeable
@Schema(description = "Generated test data response")
public record TestDataResponse(
    @Schema(description = "Number of items generated")
    int count,
    
    @Schema(description = "Generated test data items")
    List<Map<String, Object>> items,
    
    @Schema(description = "Metadata about the generated data")
    Map<String, String> metadata
) {}