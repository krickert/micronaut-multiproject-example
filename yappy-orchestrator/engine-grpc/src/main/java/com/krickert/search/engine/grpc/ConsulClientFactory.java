package com.krickert.search.engine.grpc;

import com.ecwid.consul.v1.ConsulClient;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;

@Factory
public class ConsulClientFactory {
    
    @Bean
    @Singleton
    @io.micronaut.context.annotation.Requires(property = "consul.client.enabled", notEquals = "false", defaultValue = "true")
    public ConsulClient consulClient(@Property(name = "consul.client.host", defaultValue = "localhost") String host,
                                     @Property(name = "consul.client.port", defaultValue = "8500") Integer port) {
        return new ConsulClient(host, port);
    }
}