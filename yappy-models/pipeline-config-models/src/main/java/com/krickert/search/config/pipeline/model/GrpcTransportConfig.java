// File: yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/pipeline/model/GrpcTransportConfig.java
package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Collections;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record GrpcTransportConfig(
        @JsonProperty("serviceName") String serviceName, // Consul service name of the TARGET gRPC service
        @JsonProperty("grpcClientProperties") Map<String, String> grpcClientProperties
        // e.g., timeout, loadBalancingPolicy for THIS output call
) {
    @JsonCreator
    public GrpcTransportConfig(
            @JsonProperty("serviceName") String serviceName,
            @JsonProperty("grpcClientProperties") Map<String, String> grpcClientProperties
    ) {
        this.serviceName = serviceName; // Can be null if not a GRPC output, validation by OutputTarget
        this.grpcClientProperties = (grpcClientProperties == null) ? Collections.emptyMap() : Map.copyOf(grpcClientProperties);
    }
}
