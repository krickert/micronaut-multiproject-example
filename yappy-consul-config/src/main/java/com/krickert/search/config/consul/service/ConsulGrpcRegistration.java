package com.krickert.search.config.consul.service;

import com.google.common.net.HostAndPort;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.model.health.Service;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;

import java.util.List;

public class ConsulGrpcRegistration {

    public static void main(String[] args) {
        // Create the client
        Consul consulClient = Consul.builder().withHostAndPort(HostAndPort.fromParts("localhost", 8500)).build();

        // Define the service registration
        Registration registration = ImmutableRegistration.builder()
                .id("my-grpc-service-id")
                .name("my-grpc-service")
                .address("127.0.0.1")
                .port(6565)
                .tags(List.of("grpc", "java", "v1"))
                .check(Registration.RegCheck.grpc("127.0.0.1:6565", 10)) // TTL check every 10s
                .build();

        // Register the service with Consul
        consulClient.agentClient().register(registration);

        System.out.println("gRPC service registered in Consul.");
    }
}
