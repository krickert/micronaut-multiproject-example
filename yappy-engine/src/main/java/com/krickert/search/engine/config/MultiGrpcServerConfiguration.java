package com.krickert.search.engine.config;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Configuration for multiple gRPC servers in the engine.
 * This allows us to run the main engine service on one port
 * and the module registration service on another port.
 */
@Factory
public class MultiGrpcServerConfiguration {
    
    @Value("${module.registration.port:50051}")
    private int moduleRegistrationPort;
    
    /**
     * Create a separate gRPC server for module registration.
     * This runs on a different port than the main engine gRPC service.
     */
    @Bean
    @Singleton
    @Named("moduleRegistrationServer")
    public Server moduleRegistrationServer() {
        return ServerBuilder
                .forPort(moduleRegistrationPort)
                .build();
    }
}