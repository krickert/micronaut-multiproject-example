package com.krickert.search.config.consul.service;

import com.google.common.net.HostAndPort;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton; // It's good practice to be explicit for singleton beans
import okhttp3.ConnectionPool;
import org.kiwiproject.consul.*;
import org.kiwiproject.consul.config.CacheConfig;
import org.kiwiproject.consul.config.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

@Factory
@Requires(property = "consul.client.enabled", value = "true", defaultValue = "true")
public class ConsulClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulClientFactory.class);

    // Default values for connection pool, good for clarity and consistency
    private static final int DEFAULT_MAX_IDLE_CONNECTIONS = 5;
    private static final long DEFAULT_KEEP_ALIVE_MINUTES = 5L;

    @Bean
    @Singleton // Explicitly mark the Consul client as a Singleton
    public Consul createConsulClient(
            @Value("${consul.client.host}") String host,
            @Value("${consul.client.port}") Integer port,
            @Value("${consul.client.pool.maxIdleConnections:" + DEFAULT_MAX_IDLE_CONNECTIONS + "}") int maxIdleConnections,
            @Value("${consul.client.pool.keepAliveMinutes:" + DEFAULT_KEEP_ALIVE_MINUTES + "}") long keepAliveMinutes,
            CacheConfig cacheConfig, // Injected from ConsulCacheConfigFactory
            ClientConfig clientConfig // Injected from this factory (see below)
    ) {
        LOG.info("Creating Consul client for host: {}:{}", host, port);
        LOG.debug("Consul client pool config: maxIdleConnections={}, keepAliveMinutes={}", maxIdleConnections, keepAliveMinutes);

        // Create a configurable ConnectionPool
        ConnectionPool consulConnectionPool = new ConnectionPool(maxIdleConnections, keepAliveMinutes, TimeUnit.MINUTES);

        return Consul.builder()
                .withHostAndPort(HostAndPort.fromParts(host, port))
                .withConnectionPool(consulConnectionPool)
                .withClientConfiguration(clientConfig) // Use the ClientConfig bean
                .build();
    }

    /**
     * Provides a ClientConfig bean, primarily used to wrap CacheConfig for the Consul client.
     * @param cacheConfig The CacheConfig bean.
     * @return A ClientConfig instance.
     */
    @Bean
    @Singleton
    public ClientConfig clientConfig(CacheConfig cacheConfig) {
        return new ClientConfig(cacheConfig);
    }

    @Bean
    @Singleton
    public KeyValueClient keyValueClient(Consul consulClient) {
        return consulClient.keyValueClient();
    }

    @Bean
    @Singleton
    public AgentClient agentClient(Consul consulClient) {
        return consulClient.agentClient();
    }

    @Bean
    @Singleton
    public CatalogClient catalogClient(Consul consulClient) {
        return consulClient.catalogClient();
    }

    @Bean
    @Singleton
    public HealthClient healthClient(Consul consulClient) {
        return consulClient.healthClient();
    }

    @Bean
    @Singleton
    public StatusClient statusClient(Consul consulClient) {
        return consulClient.statusClient();
    }


    /**
     * Properly close the Consul client when the application context shuts down.
     * This ensures that all resources (like connection pools and executor services
     * managed by the kiwiproject.consul.Consul client) are released gracefully.
     *
     * @param consulClient the Consul client to close
     */
    @PreDestroy
    public void closeConsulClient(Consul consulClient) {
        if (consulClient != null && !consulClient.isDestroyed()) {
            LOG.info("Closing Consul client and releasing resources for host: {}", consulClient.agentClient().getAgent().getConfig().getDatacenter());
            try {
                consulClient.destroy();
                LOG.debug("Consul client successfully closed.");
            } catch (Exception e) {
                // Catching a broad exception here as kiwiproject's destroy() might throw various runtime ones
                // if OkHttp resources have issues shutting down.
                LOG.warn("Error closing Consul client: {}", e.getMessage(), e);
            }
        } else if (consulClient != null && consulClient.isDestroyed()) {
            LOG.debug("Consul client was already destroyed.");
        } else {
            LOG.warn("Consul client was null in @PreDestroy, cannot close.");
        }
    }
}