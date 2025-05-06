package com.krickert.search.config.consul.service;

import com.google.common.net.HostAndPort;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import okhttp3.ConnectionPool;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.config.CacheConfig;
import org.kiwiproject.consul.config.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

@Factory
public class ConsulClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulClientFactory.class);

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

    /**
     * Properly close the Consul client when the bean is destroyed.
     * This ensures that all resources are released gracefully.
     *
     * @param consulClient the Consul client to close
     */
    @PreDestroy
    public void closeConsulClient(Consul consulClient) {
        LOG.info("Closing Consul client and releasing resources");
        try {
            // Shutdown the Consul client gracefully
            consulClient.destroy();
            LOG.debug("Consul client successfully closed");

            // Add a small delay to allow resources to be released
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for resources to be released", e);
            }
        } catch (Exception e) {
            LOG.warn("Error closing Consul client", e);
        }
    }
}
