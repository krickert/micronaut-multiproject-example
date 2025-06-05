package com.krickert.search.pipeline.integration.config;

import com.krickert.search.sdk.PipeStepProcessorGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Named;

/**
 * Factory for creating gRPC client stubs for testing.
 * This factory can be used to create clients for any PipeStepProcessor service.
 * 
 * TODO: Update this to create clients for test services instead of echo/chunker
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