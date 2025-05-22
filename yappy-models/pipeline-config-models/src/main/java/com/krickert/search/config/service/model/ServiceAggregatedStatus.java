package com.krickert.search.config.service.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the comprehensive aggregated status information for a logical service.
 * This record is intended to be serialized to JSON and stored in Consul KV,
 * as well as exposed via APIs.
 *
 * @param serviceName                 The unique logical name of the service (e.g., "echo-service", "chunker-service").
 * @param operationalStatus           The overall operational status of the service.
 * @param statusDetail                A human-readable summary or reason for the current operationalStatus.
 * @param lastCheckedByEngineMillis   Timestamp (epoch milliseconds) when the engine last aggregated/updated this status.
 * @param totalInstancesConsul        Total number of instances registered in Consul for this logical service.
 * @param healthyInstancesConsul      Number of instances reported as healthy by Consul for this logical service.
 * @param isLocalInstanceActive       True if there is an active (healthy or initializing) instance of this service
 *                                    managed by the current engine node.
 * @param activeLocalInstanceId       The instance ID of the locally active module, if isLocalInstanceActive is true. Nullable.
 * @param isProxying                  True if this engine node is currently proxying requests for this service
 *                                    to a remote instance because its local module is unavailable/unhealthy.
 * @param proxyTargetInstanceId       The instance ID of the remote service instance being proxied to, if isProxying is true. Nullable.
 * @param isUsingStaleClusterConfig   True if the module associated with this service (if local) or the engine itself
 *                                    is operating with an outdated PipelineClusterConfig version compared to what's current in Consul.
 * @param activeClusterConfigVersion  The version identifier (e.g., MD5 hash or timestamp) of the PipelineClusterConfig
 *                                    currently active or last successfully loaded by the engine/module.
 * @param reportedModuleConfigDigest  The configuration digest (e.g., MD5 hash of its specific customConfig) reported by
 *                                    the module instance via Consul tags, if available. Nullable.
 * @param errorMessages               A list of recent or significant error messages related to this service's status.
 * @param additionalAttributes        A map for any other service-specific status attributes or metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceAggregatedStatus(
        @JsonProperty("serviceName") String serviceName,
        @JsonProperty("operationalStatus") ServiceOperationalStatus operationalStatus,
        @JsonProperty("statusDetail") String statusDetail,
        @JsonProperty("lastCheckedByEngineMillis") long lastCheckedByEngineMillis,
        @JsonProperty("totalInstancesConsul") int totalInstancesConsul,
        @JsonProperty("healthyInstancesConsul") int healthyInstancesConsul,
        @JsonProperty("isLocalInstanceActive") boolean isLocalInstanceActive,
        @JsonProperty("activeLocalInstanceId") String activeLocalInstanceId, // Nullable
        @JsonProperty("isProxying") boolean isProxying,
        @JsonProperty("proxyTargetInstanceId") String proxyTargetInstanceId, // Nullable
        @JsonProperty("isUsingStaleClusterConfig") boolean isUsingStaleClusterConfig,
        @JsonProperty("activeClusterConfigVersion") String activeClusterConfigVersion, // Nullable
        @JsonProperty("reportedModuleConfigDigest") String reportedModuleConfigDigest, // Nullable
        @JsonProperty("errorMessages") List<String> errorMessages,
        @JsonProperty("additionalAttributes") Map<String, String> additionalAttributes
) {
    // Canonical constructor for validation and unmodifiable collections
    public ServiceAggregatedStatus {
        Objects.requireNonNull(serviceName, "serviceName cannot be null");
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName cannot be blank");
        }
        Objects.requireNonNull(operationalStatus, "operationalStatus cannot be null");
        // statusDetail can be null or empty
        // activeClusterConfigVersion can be null
        // activeLocalInstanceId can be null if isLocalInstanceActive is false
        // proxyTargetInstanceId can be null if isProxying is false
        // reportedModuleConfigDigest can be null

        errorMessages = (errorMessages == null) ? Collections.emptyList() : List.copyOf(errorMessages);
        additionalAttributes = (additionalAttributes == null) ? Collections.emptyMap() : Map.copyOf(additionalAttributes);
    }

    // Optional: Convenience constructor for minimal creation if needed, though builder pattern is often preferred for records with many fields.
    // For now, the canonical constructor is sufficient.
}