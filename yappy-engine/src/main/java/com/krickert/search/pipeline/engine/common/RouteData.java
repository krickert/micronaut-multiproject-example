package com.krickert.search.pipeline.engine.common;

import com.krickert.search.config.pipeline.model.TransportType;
import io.micronaut.context.annotation.Value;
import lombok.Builder;

/**
 * Route data for forwarding PipeStream to the next step.
 * This record is used by both gRPC and Kafka forwarders.
 */
@Builder
public record RouteData(
        String targetPipeline,
        String nextTargetStep,
        String destination,
        String streamId,
        TransportType transportType,
        @Value("${grpc.client.plaintext:true}") boolean usePlainText) {
}