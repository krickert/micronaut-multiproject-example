package com.krickert.search.config.consul.schema.service;

import com.krickert.search.schema.registry.SchemaRegistryServiceGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;

@Factory
public class Clients {

    @Bean
    SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub schemaBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return SchemaRegistryServiceGrpc.newBlockingStub(
                channel
        );
    }

    @Bean
    SchemaRegistryServiceGrpc.SchemaRegistryServiceStub serviceStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return SchemaRegistryServiceGrpc.newStub(
                channel
        );
    }
}