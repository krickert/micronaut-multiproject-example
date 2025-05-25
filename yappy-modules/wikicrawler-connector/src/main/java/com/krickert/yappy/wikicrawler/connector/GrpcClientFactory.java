
package com.krickert.yappy.wikicrawler.connector;

import com.krickert.search.engine.ConnectorEngineGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;

@Factory
class GrpcClientFactory {
    // Micronaut will inject the channel configured for 'connector-engine-service'
    // This name ('connector-engine-service') needs to be defined in application.yml
    // under grpc.channels.connector-engine-service.address (e.g., "localhost:50051")
    @Bean
    ConnectorEngineGrpc.ConnectorEngineFutureStub futureStub(@GrpcChannel("connector-engine-service") ManagedChannel channel) {
        return ConnectorEngineGrpc.newFutureStub(channel);
    }
}
