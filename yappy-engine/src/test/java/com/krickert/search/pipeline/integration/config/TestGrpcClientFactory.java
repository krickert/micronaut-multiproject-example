package com.krickert.search.pipeline.integration.config; // Or a suitable package in yappy-engine

import com.krickert.search.sdk.PipeStepProcessorGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Named;

@Factory
public class TestGrpcClientFactory {

    @Bean
    @Named("chunkerClientStub") // Qualify the name if you have multiple stubs of the same type
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) // Discovers the "chunker" service via Consul
            ManagedChannel chunkerChannel) {
        return PipeStepProcessorGrpc.newBlockingStub(chunkerChannel);
    }

    @Bean
    @Named("echoClientStub") // Qualify the name
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub echoBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) // Discovers the "echo" service via Consul
            ManagedChannel echoChannel) {
        return PipeStepProcessorGrpc.newBlockingStub(echoChannel);
    }

}