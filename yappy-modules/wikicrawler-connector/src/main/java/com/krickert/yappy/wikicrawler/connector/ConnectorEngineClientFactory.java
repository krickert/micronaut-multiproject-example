
package com.krickert.yappy.wikicrawler.connector;

import com.krickert.search.engine.ConnectorEngineGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Singleton;

// Factory to provide the gRPC stub
@Factory
class ConnectorEngineClientFactory {

    @Bean
    @Singleton
    ConnectorEngineGrpc.ConnectorEngineFutureStub connectorEngineFutureStub(
            @GrpcChannel("connector-engine-service") ManagedChannel channel) {
        return ConnectorEngineGrpc.newFutureStub(channel);
    }
}
