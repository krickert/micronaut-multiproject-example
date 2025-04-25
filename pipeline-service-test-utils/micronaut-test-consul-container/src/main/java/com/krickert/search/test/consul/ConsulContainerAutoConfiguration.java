package com.krickert.search.test.consul;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-configuration for the Consul container.
 * This class is automatically loaded when the library is included in a project.
 */
@Factory
public class ConsulContainerAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ConsulContainerAutoConfiguration.class);

    /**
     * Creates a singleton ConsulContainer bean.
     * This bean will be automatically loaded when the library is included in a project.
     * The @Context annotation ensures that the bean is created at application startup.
     *
     * @return the ConsulContainer instance
     */
    @Bean
    @Context
    @Singleton
    @Requires(notEnv = "test") // In test environment, the container is created by the test framework
    @NonNull
    public ConsulContainer consulContainer() {
        log.info("Auto-creating ConsulContainer bean");
        return new ConsulContainer();
    }
}
