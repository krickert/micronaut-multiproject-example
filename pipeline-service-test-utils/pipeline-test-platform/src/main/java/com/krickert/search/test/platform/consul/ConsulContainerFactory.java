package com.krickert.search.test.platform.consul;

import com.krickert.search.test.platform.kafka.TestContainerManager;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for providing ConsulContainer bean from TestContainerManager.
 * This allows tests to inject ConsulContainer directly, while the actual
 * container is managed by TestContainerManager.
 */
@Factory
public class ConsulContainerFactory {
    private static final Logger log = LoggerFactory.getLogger(ConsulContainerFactory.class);

    /**
     * Provides a ConsulContainer bean from TestContainerManager.
     * 
     * @return the ConsulContainer instance managed by TestContainerManager
     */
    @Bean
    @Singleton
    public ConsulContainer consulContainer() {
        log.debug("Creating ConsulContainer bean from TestContainerManager");
        TestContainerManager manager = TestContainerManager.getInstance();
        ConsulContainer container = manager.getConsulContainer();
        if (container == null) {
            log.error("ConsulContainer is null in TestContainerManager");
            throw new IllegalStateException("ConsulContainer is null in TestContainerManager");
        }
        return container;
    }
}