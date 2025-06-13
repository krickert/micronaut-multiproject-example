package com.krickert.search.engine.core.test.util;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Manages test resources lifecycle and provides utilities for verifying container availability.
 * Helps coordinate between test resources and test execution.
 */
public class TestResourcesManager {
    
    private static final Logger LOG = LoggerFactory.getLogger(TestResourcesManager.class);
    
    private final ApplicationContext applicationContext;
    private final Set<String> requiredProperties;
    private final Map<String, ContainerInfo> containerInfoMap = new HashMap<>();
    
    public TestResourcesManager(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.requiredProperties = new HashSet<>();
        initializeDefaultProperties();
    }
    
    private void initializeDefaultProperties() {
        // Base infrastructure
        requiredProperties.addAll(Arrays.asList(
                "consul.client.host",
                "consul.client.port",
                "kafka.bootstrap.servers",
                "apicurio.registry.url",
                "opensearch.url",
                "aws.endpoint"
        ));
        
        // Module containers
        requiredProperties.addAll(Arrays.asList(
                "chunker.grpc.host",
                "chunker.grpc.port",
                "tika.grpc.host",
                "tika.grpc.port",
                "embedder.grpc.host",
                "embedder.grpc.port",
                "echo.grpc.host",
                "echo.grpc.port",
                "test-module.grpc.host",
                "test-module.grpc.port"
        ));
    }
    
    /**
     * Verifies all required containers are available
     */
    public boolean verifyAllContainersReady() {
        return verifyAllContainersReady(30, TimeUnit.SECONDS);
    }
    
    /**
     * Verifies all required containers are available with timeout
     */
    public boolean verifyAllContainersReady(long timeout, TimeUnit unit) {
        LOG.info("Verifying all test containers are ready...");
        
        long startTime = System.currentTimeMillis();
        long timeoutMillis = unit.toMillis(timeout);
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (checkAllProperties()) {
                LOG.info("All containers are ready!");
                return true;
            }
            
            try {
                Thread.sleep(1000); // Check every second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        LOG.error("Timeout waiting for containers to be ready");
        logMissingProperties();
        return false;
    }
    
    private boolean checkAllProperties() {
        for (String property : requiredProperties) {
            if (!applicationContext.getProperty(property, String.class).isPresent()) {
                return false;
            }
        }
        return true;
    }
    
    private void logMissingProperties() {
        LOG.error("Missing properties:");
        for (String property : requiredProperties) {
            if (!applicationContext.getProperty(property, String.class).isPresent()) {
                LOG.error(" - {}", property);
            }
        }
    }
    
    /**
     * Gets container connection info
     */
    public ContainerInfo getContainerInfo(String containerName) {
        return containerInfoMap.computeIfAbsent(containerName, name -> {
            switch (name) {
                case "consul":
                    return new ContainerInfo(
                            getProperty("consul.client.host"),
                            getPropertyAsInt("consul.client.port"),
                            "http"
                    );
                case "kafka":
                    return new ContainerInfo(
                            getProperty("kafka.bootstrap.servers"),
                            null,
                            "kafka"
                    );
                case "apicurio":
                    return new ContainerInfo(
                            getProperty("apicurio.registry.url"),
                            null,
                            "http"
                    );
                case "opensearch":
                    return new ContainerInfo(
                            getProperty("opensearch.url"),
                            null,
                            "http"
                    );
                case "chunker":
                case "tika":
                case "embedder":
                case "echo":
                case "test-module":
                    String prefix = name.replace("-module", "");
                    return new ContainerInfo(
                            getProperty(prefix + ".grpc.host"),
                            getPropertyAsInt(prefix + ".grpc.port"),
                            "grpc"
                    );
                default:
                    throw new IllegalArgumentException("Unknown container: " + name);
            }
        });
    }
    
    private String getProperty(String property) {
        return applicationContext.getProperty(property, String.class)
                .orElseThrow(() -> new IllegalStateException("Property not found: " + property));
    }
    
    private Integer getPropertyAsInt(String property) {
        return applicationContext.getProperty(property, Integer.class)
                .orElseThrow(() -> new IllegalStateException("Property not found: " + property));
    }
    
    /**
     * Waits for a specific condition to be true
     */
    public static boolean waitFor(Supplier<Boolean> condition, long timeout, TimeUnit unit) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = unit.toMillis(timeout);
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (condition.get()) {
                return true;
            }
            
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Logs current environment information
     */
    public void logEnvironmentInfo() {
        LOG.info("=== Test Environment Information ===");
        LOG.info("Active environments: {}", applicationContext.getEnvironment().getActiveNames());
        LOG.info("Test resources enabled: {}", 
                applicationContext.getEnvironment().getActiveNames().contains(Environment.TEST));
        
        // Log all resolved container endpoints
        LOG.info("Resolved endpoints:");
        for (String property : requiredProperties) {
            applicationContext.getProperty(property, String.class)
                    .ifPresent(value -> LOG.info(" - {}: {}", property, value));
        }
    }
    
    /**
     * Container connection information
     */
    public static class ContainerInfo {
        private final String host;
        private final Integer port;
        private final String protocol;
        
        public ContainerInfo(String host, Integer port, String protocol) {
            this.host = host;
            this.port = port;
            this.protocol = protocol;
        }
        
        public String getHost() {
            return host;
        }
        
        public Integer getPort() {
            return port;
        }
        
        public String getProtocol() {
            return protocol;
        }
        
        public String getConnectionString() {
            if (port != null) {
                return String.format("%s://%s:%d", protocol, host, port);
            } else {
                return host; // Already includes full URL
            }
        }
        
        @Override
        public String toString() {
            return getConnectionString();
        }
    }
}