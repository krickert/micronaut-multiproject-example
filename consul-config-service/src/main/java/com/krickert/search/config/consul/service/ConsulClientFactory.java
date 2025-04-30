package com.krickert.search.config.consul.service;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;

@Factory
public class ConsulClientFactory {

    @Bean
    @Primary
    @Singleton
    @Named("primaryConsulClient")
    Consul consulClient(@Value("${consul.host}") String host, @Value("${consul.port}") int port) {
        return Consul.builder()
                .withUrl("http://" + host + ":" + port)
                .build();
    }

    @Bean
    @Primary
    @Singleton
    @Named("primaryKeyValueClient")
    KeyValueClient keyValueClient(@Named("primaryConsulClient") Consul consulClient) {
        return consulClient.keyValueClient();
    }
}
