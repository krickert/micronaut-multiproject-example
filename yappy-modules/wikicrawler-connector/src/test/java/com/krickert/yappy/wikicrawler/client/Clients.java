package com.krickert.yappy.wikicrawler.client;

import com.krickert.search.engine.ConnectorEngineGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;

/**
 * Factory for creating gRPC client stubs for testing.
 * This allows tests to connect to the embedded gRPC server.
 */
@Factory
public class Clients {

    /**
     * Creates a blocking stub for the ConnectorEngine service.
     * This stub connects to the embedded gRPC server running in the test.
     *
     * @param channel The managed channel connected to the embedded server
     * @return A blocking stub for the ConnectorEngine service
     */
    @Bean
    ConnectorEngineGrpc.ConnectorEngineBlockingStub connectorEngineBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) ManagedChannel channel) {
        return ConnectorEngineGrpc.newBlockingStub(channel);
    }
}