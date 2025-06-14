package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response from module registration.
 */
@Serdeable
@Schema(description = "Module registration response")
public record ModuleRegistrationResponse(
    @Schema(description = "Assigned service ID")
    String serviceId,
    
    @Schema(description = "Registration status", allowableValues = {"registered", "updated"})
    String status,
    
    @Schema(description = "Registration timestamp")
    Instant registeredAt,
    
    @Schema(description = "Consul service ID for reference")
    String consulServiceId,
    
    @Schema(description = "Success message")
    String message
) {}