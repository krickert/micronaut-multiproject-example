package com.krickert.search.config.consul.service;

import com.google.common.net.HostAndPort;
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
    public Consul createConsulClient(
            @Value("${consul.client.host}") String host,
            @Value("${consul.client.port}") Integer port) {
        return Consul.builder()
                .withHostAndPort(HostAndPort.fromParts(host,port))
                .build();
    }

    @Bean
    public KeyValueClient keyValueClient(Consul consulClient) {
        return consulClient.keyValueClient();
    }
}
