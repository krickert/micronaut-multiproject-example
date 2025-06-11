package com.krickert.testcontainers.module;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base class for module test resource providers.
 * Provides common functionality for starting Yappy module containers.
 */
public abstract class AbstractModuleTestResourceProvider 
        extends AbstractTestContainersProvider<GenericContainer<?>> {
    
    private static final Logger LOG = LoggerFactory.getLogger(AbstractModuleTestResourceProvider.class);
    
    protected static final int GRPC_PORT = 50051;
    protected static final String MODULE_IMAGE_PREFIX = "yappy-";
    protected static final String MODULE_IMAGE_TAG = "latest";
    
    /**
     * Get the module name (e.g., "chunker", "tika", "embedder")
     */
    protected abstract String getModuleName();
    
    /**
     * Get the wait strategy for this module.
     * Default implementation waits for log message indicating gRPC server started.
     */
    protected Wait.WaitStrategy getWaitStrategy() {
        return Wait.forLogMessage(".*gRPC server started.*", 1)
                .withStartupTimeout(java.time.Duration.ofSeconds(120));
    }
    
    /**
     * Configure any additional environment variables for the module.
     * Override this method to add module-specific environment variables.
     */
    protected Map<String, String> getAdditionalEnvironment(Map<String, Object> testResourcesConfig) {
        return Map.of();
    }
    
    @Override
    protected String getSimpleName() {
        return "yappy-" + getModuleName();
    }
    
    @Override
    protected String getDefaultImageName() {
        return MODULE_IMAGE_PREFIX + getModuleName() + ":" + MODULE_IMAGE_TAG;
    }
    
    @Override
    protected GenericContainer<?> createContainer(
            DockerImageName imageName, 
            Map<String, Object> requestedProperties, 
            Map<String, Object> testResourcesConfig) {
        
        LOG.info("Creating {} container with image: {}", getModuleName(), imageName);
        
        GenericContainer<?> container = new GenericContainer<>(imageName)
                .withExposedPorts(GRPC_PORT)
                .withNetwork(Network.SHARED)
                .withNetworkAliases(getModuleName())
                .waitingFor(getWaitStrategy());
        
        // Configure logging to use Slf4j
        Logger containerLogger = LoggerFactory.getLogger(getClass().getName() + "." + getModuleName());
        container.withLogConsumer(new Slf4jLogConsumer(containerLogger));
        
        // Set common environment variables
        Map<String, String> env = new java.util.HashMap<>();
        env.put("GRPC_SERVER_PORT", String.valueOf(GRPC_PORT));
        env.put("MICRONAUT_SERVER_PORT", "8080");
        env.put("MICRONAUT_ENVIRONMENTS", "test");
        
        // Add any additional module-specific environment variables
        env.putAll(getAdditionalEnvironment(testResourcesConfig));
        
        container.withEnv(env);
        
        LOG.info("Container configured for module: {}", getModuleName());
        
        return container;
    }
    
    @Override
    protected Optional<String> resolveProperty(String propertyName, GenericContainer<?> container) {
        LOG.debug("Resolving property {} for module {}", propertyName, getModuleName());
        
        String prefix = getModuleName() + ".grpc.";
        if (propertyName.startsWith(prefix)) {
            String suffix = propertyName.substring(prefix.length());
            switch (suffix) {
                case "host":
                    return Optional.of(container.getHost());
                case "port":
                    return Optional.of(String.valueOf(container.getMappedPort(GRPC_PORT)));
                default:
                    LOG.warn("Unknown property suffix: {} for module {}", suffix, getModuleName());
                    return Optional.empty();
            }
        }
        
        return Optional.empty();
    }
    
    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        List<String> properties = new ArrayList<>();
        properties.add(getModuleName() + ".grpc.host");
        properties.add(getModuleName() + ".grpc.port");
        return properties;
    }
    
    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        return propertyName != null && propertyName.startsWith(getModuleName() + ".grpc.");
    }
}