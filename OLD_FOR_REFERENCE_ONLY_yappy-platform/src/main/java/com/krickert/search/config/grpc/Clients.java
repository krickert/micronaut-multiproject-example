package com.krickert.search.config.grpc;

import com.krickert.search.model.ReloadServiceGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;

@Factory
@Requires(notEnv = "test")
public  class Clients {
    @Bean
    ReloadServiceGrpc.ReloadServiceBlockingStub blockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) ManagedChannel channel) {
        return ReloadServiceGrpc.newBlockingStub(
                channel
        );
    }
}