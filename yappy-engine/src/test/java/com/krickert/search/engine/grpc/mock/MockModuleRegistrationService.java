package com.krickert.search.engine.grpc.mock;

import com.krickert.search.engine.grpc.ModuleRegistrationMetrics;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock implementation of module registration service for testing.
 */
@Singleton
@Secondary
@Requires(property = "consul.client.enabled", value = "false")
public class MockModuleRegistrationService implements ModuleRegistrationMetrics {
    
    private static final Logger LOG = LoggerFactory.getLogger(MockModuleRegistrationService.class);
    
    private final AtomicInteger moduleCount = new AtomicInteger(0);
    
    @Override
    public int getRegisteredModuleCount() {
        return moduleCount.get();
    }
    
    /**
     * Simulate module registration.
     * @param moduleName module name
     */
    public void registerModule(String moduleName) {
        LOG.info("Mock: Registering module: {}", moduleName);
        moduleCount.incrementAndGet();
    }
    
    /**
     * Simulate module unregistration.
     * @param moduleName module name
     */
    public void unregisterModule(String moduleName) {
        LOG.info("Mock: Unregistering module: {}", moduleName);
        moduleCount.decrementAndGet();
    }
}