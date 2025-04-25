package com.krickert.search.test.consul;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating and configuring the Consul container.
 * This factory is automatically loaded when the library is included in a project.
 */
@Factory
public class ConsulContainerFactory {
    private static final Logger log = LoggerFactory.getLogger(ConsulContainerFactory.class);

    /**
     * Creates a singleton ConsulContainer bean.
     * This bean will be automatically loaded when the library is included in a project.
     *
     * @return the ConsulContainer instance
     */
    @Bean
    @Singleton
    @Requires(notEnv = "test") // In test environment, the container is created by the test framework
    @NonNull
    public ConsulContainer consulContainer() {
        log.info("Creating ConsulContainer bean");
        return new ConsulContainer();
    }
}
