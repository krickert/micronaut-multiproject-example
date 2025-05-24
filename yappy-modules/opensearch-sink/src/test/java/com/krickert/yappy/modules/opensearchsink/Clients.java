package com.krickert.yappy.modules.opensearchsink;

import com.krickert.search.engine.SinkServiceGrpc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.testcontainers.opensearch.OpenSearchTestResourceProvider;
import com.krickert.yappy.modules.opensearchsink.config.OpenSearchSinkConfig;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;

@Factory
public class Clients {
    @Bean
    SinkServiceGrpc.SinkServiceBlockingStub pipeStepProcessorBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return SinkServiceGrpc.newBlockingStub(
                channel
        );
    }

    @Bean
    SinkServiceGrpc.SinkServiceStub serviceStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return SinkServiceGrpc.newStub(
                channel
        );
    }

    // Directly inject properties from the OpenSearchTestResourceProvider
    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST)
    String openSearchHost;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT)
    String openSearchPort;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL)
    String openSearchUrl;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME)
    String openSearchUsername;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD)
    String openSearchPassword;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED)
    String openSearchSecurityEnabled;

    @Bean
    OpenSearchSinkConfig openSearchSinkConfig() {
        // The tests in this class assert that openSearchHost, openSearchPort,
        // and openSearchSecurityEnabled are non-null and that openSearchPort is a valid integer.
        // openSearchUsername and openSearchPassword can be null if security is not enabled.
        OpenSearchSinkConfig config = OpenSearchSinkConfig.builder()
                .hosts(openSearchHost)
                .port(Integer.parseInt(openSearchPort)) // Converts String to Integer
                .username(openSearchUsername)
                .password(openSearchPassword)
                .useSsl(Boolean.parseBoolean(openSearchSecurityEnabled)) // Converts String to Boolean
                // Other fields of OpenSearchSinkConfig (e.g., indexName, bulkSize)
                // are not set from the properties injected in this specific test class.
                // They will default to null as their types are objects (String, Integer, Boolean).
                .build();
        return config;
    }

}
