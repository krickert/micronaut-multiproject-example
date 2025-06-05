package com.krickert.search.pipeline.integration.config;

import com.krickert.search.engine.ConnectorEngineGrpc;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Named;

/**
 * Factory for creating gRPC client stubs for testing.
 * This factory creates clients for various gRPC services used in tests.
 */
@Factory
public class TestGrpcClientFactory {

    /**
     * Client stub for the internal dummy test service.
     * This is used for self-contained engine testing without external module dependencies.
     */
    @Bean
    @Named("dummyTestClientStub")
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub dummyTestBlockingStub(
            @GrpcChannel("dummy-test-service") // Service name for the internal test dummy
            ManagedChannel dummyChannel) {
        return PipeStepProcessorGrpc.newBlockingStub(dummyChannel);
    }
    
    /**
     * Client stub for the PipeStreamEngine service.
     * This connects to the engine running in the same test context.
     */
    @Bean
    @Named("pipeStreamEngineStub")
    PipeStreamEngineGrpc.PipeStreamEngineBlockingStub pipeStreamEngineBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) // Use the default server channel
            ManagedChannel channel) {
        return PipeStreamEngineGrpc.newBlockingStub(channel);
    }
    
    /**
     * Client stub for the ConnectorEngine service.
     * This connects to the connector engine running in the same test context.
     */
    @Bean
    @Named("connectorEngineStub")
    ConnectorEngineGrpc.ConnectorEngineBlockingStub connectorEngineBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) // Use the default server channel
            ManagedChannel channel) {
        return ConnectorEngineGrpc.newBlockingStub(channel);
    }

    // TODO: Add more client stubs as needed for testing
    // Example pattern:
    // @Bean
    // @Named("someServiceClientStub")
    // PipeStepProcessorGrpc.PipeStepProcessorBlockingStub someServiceBlockingStub(
    //         @GrpcChannel("some-service-name")
    //         ManagedChannel channel) {
    //     return PipeStepProcessorGrpc.newBlockingStub(channel);
    // }
}