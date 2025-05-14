package com.krickert.search.config.pipeline.model; // Or your chosen package

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Collections;
import java.util.Objects;

/**
 * Configuration specific to gRPC transport for a pipeline step.
 *
 * @param serviceId The Consul serviceId or other resolvable gRPC target identifier
 * for the module implementing this step.
 * @param grpcProperties Additional gRPC client properties (e.g., timeout, retry policies) for this call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrpcTransportConfig(
        @JsonProperty("serviceId") String serviceId,
        @JsonProperty("grpcProperties") Map<String, String> grpcProperties
) {
    @JsonCreator
    public GrpcTransportConfig(
            @JsonProperty("serviceId") String serviceId,
            @JsonProperty("grpcProperties") Map<String, String> grpcProperties
    ) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("GrpcTransportConfig serviceId cannot be null or blank.");
        }
        this.serviceId = serviceId;
        this.grpcProperties = (grpcProperties == null) ? Collections.emptyMap() : Map.copyOf(grpcProperties);
    }
}