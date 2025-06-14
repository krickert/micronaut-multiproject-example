package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * Request to register a new module.
 */
@Serdeable
@Schema(description = "Module registration request")
public record ModuleRegistrationRequest(
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Service ID must be lowercase letters, numbers, and hyphens only")
    @Schema(description = "Unique service ID", example = "tika-parser")
    String serviceId,
    
    @NotBlank
    @Schema(description = "Module display name", example = "Apache Tika Parser")
    String name,
    
    @Schema(description = "What this module does")
    String description,
    
    @NotBlank
    @Schema(description = "Module version", example = "1.0.0")
    String version,
    
    @NotBlank
    @Schema(description = "Host address", example = "tika-parser.yappy.local")
    String host,
    
    @Min(1)
    @Max(65535)
    @Schema(description = "gRPC port", example = "50051")
    int port,
    
    @Schema(description = "Module capabilities")
    List<String> capabilities,
    
    @Schema(description = "Input MIME types accepted")
    List<String> inputTypes,
    
    @Schema(description = "Output MIME types produced")
    List<String> outputTypes,
    
    @Schema(description = "Configuration schema")
    Map<String, Object> configSchema,
    
    @Schema(description = "Health check endpoint path", example = "/health")
    String healthCheckPath,
    
    @Schema(description = "Additional metadata")
    Map<String, String> metadata
) {}