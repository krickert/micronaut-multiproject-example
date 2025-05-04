package com.krickert.search.config.consul.container;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for creating Consul client beans for tests.
 * This centralizes the creation of Consul clients using the shared ConsulTestContainer.
 */
@Factory
public class ConsulTestClientFactory {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestClientFactory.class);

    /**
     * Creates a default Consul client bean using the shared ConsulTestContainer.
     *
     * @return a Consul client connected to the shared container
     */
    @Bean
    @Singleton
    @Named("testConsulClient")
    public Consul consulClient() {
        LOG.debug("Creating default Consul client");
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        if (!container.getContainer().isRunning()) {
            container.getContainer().start();
        }
        return Consul.builder()
                .withUrl(container.getContainerUrl())
                .build();
    }

    /**
     * Creates a default KeyValueClient bean using the provided Consul client.
     *
     * @param consulClient the Consul client to use
     * @return a KeyValueClient using the provided Consul client
     */
    @Bean
    @Singleton
    @Named("testKeyValueClient")
    public KeyValueClient keyValueClient(@Named("testConsulClient") Consul consulClient) {
        LOG.debug("Creating default KeyValueClient");
        return consulClient.keyValueClient();
    }

    // Specific beans for different test classes to avoid conflicts

    @Bean
    @Singleton
    @Named("configurationTest")
    public Consul configurationTestConsulClient() {
        LOG.debug("Creating Consul client for ConfigurationTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("consulKvServiceTest")
    public Consul consulKvServiceTestConsulClient() {
        LOG.debug("Creating Consul client for ConsulKvServiceTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("serviceDiscoveryControllerTest")
    public Consul serviceDiscoveryControllerTestConsulClient() {
        LOG.debug("Creating Consul client for ServiceDiscoveryControllerTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("openApiTest")
    public Consul openApiTestConsulClient() {
        LOG.debug("Creating Consul client for OpenApiTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("swaggerUiGeneratedTest")
    public Consul swaggerUiGeneratedTestConsulClient() {
        LOG.debug("Creating Consul client for SwaggerUiGeneratedTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("openApiExposedTest")
    public Consul openApiExposedTestConsulClient() {
        LOG.debug("Creating Consul client for OpenApiExposedTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("consulIntegrationTest")
    public Consul consulIntegrationTestConsulClient() {
        LOG.debug("Creating Consul client for ConsulIntegrationTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("configControllerTest")
    public Consul configControllerTestConsulClient() {
        LOG.debug("Creating Consul client for ConfigControllerTest");
        return createConsulClient();
    }

    @Bean
    @Singleton
    @Named("consulDataSeederTest")
    public Consul consulDataSeederTestConsulClient() {
        LOG.debug("Creating Consul client for ConsulDataSeederTest");
        return createConsulClient();
    }

    // Helper method to create a Consul client
    private Consul createConsulClient() {
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        return Consul.builder()
                .withUrl(container.getContainerUrl())
                .build();
    }
}
