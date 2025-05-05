package com.krickert.search.config.consul.service;

import com.google.common.net.HostAndPort;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import okhttp3.ConnectionPool;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.config.CacheConfig;
import org.kiwiproject.consul.config.ClientConfig;

import java.util.concurrent.TimeUnit;

@Factory
public class ConsulClientFactory {

    // Example properties you would add to your application configuration (e.g., application.yml)
    // consul.client.pool.maxIdleConnections: 10
    // consul.client.pool.keepAliveMinutes: 10

    @Bean
    public Consul createConsulClient(
            @Value("${consul.client.host}") String host,
            @Value("${consul.client.port}") Integer port,
            @Value("${consul.client.pool.maxIdleConnections:5}") int maxIdleConnections, // Default to 5 if property not set
            @Value("${consul.client.pool.keepAliveMinutes:5}") long keepAliveMinutes,
            CacheConfig cacheConfig) { // Default to 5 if property not set

        // Create a configurable ConnectionPool
        ConnectionPool consulConnectionPool = new ConnectionPool(maxIdleConnections, keepAliveMinutes, TimeUnit.MINUTES);
        ClientConfig config = new ClientConfig(cacheConfig);
        return Consul.builder()
                .withHostAndPort(HostAndPort.fromParts(host, port))
                .withConnectionPool(consulConnectionPool)
                .withClientConfiguration(config)
                .build();
    }

    @Bean
    public KeyValueClient keyValueClient(Consul consulClient) {
        return consulClient.keyValueClient();
    }
}