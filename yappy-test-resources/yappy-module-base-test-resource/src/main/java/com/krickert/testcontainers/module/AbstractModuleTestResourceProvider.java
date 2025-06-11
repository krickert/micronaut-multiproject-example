package com.krickert.testcontainers.module;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;

/**
 * Abstract base class for YAPPY module test resource providers.
 * Provides common functionality for module containers including:
 * - Shared network configuration
 * - gRPC and HTTP port management
 * - Health check strategies
 * - File-based logging
 * - Property resolution patterns
 */
public abstract class AbstractModuleTestResourceProvider extends AbstractTestContainersProvider<ModuleContainer> {
    
    private static final Logger LOG = LoggerFactory.getLogger(AbstractModuleTestResourceProvider.class);
    
    // Common constants
    protected static final String SHARED_NETWORK_NAME = "yappy-test-network";
    protected static final int GRPC_PORT = 50051;
    protected static final int HTTP_PORT = 8080;
    protected static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(60);
    
    // Shared network instance
    private static volatile Network sharedNetwork;
    
    /**
     * Get the module name (e.g., "chunker", "tika-parser", "embedder")
     */
    protected abstract String getModuleName();
    
    /**
     * Get the default Docker image name for this module
     */
    protected abstract String getDefaultImageName();
    
    /**
     * Optional: Override to add module-specific environment variables
     */
    protected Map<String, String> getModuleEnvironment(Map<String, Object> testResourcesConfig) {
        return new HashMap<>();
    }
    
    /**
     * Optional: Override to add module-specific wait strategy
     */
    protected WaitStrategy getWaitStrategy() {
        // Default to gRPC health check
        return new GrpcHealthCheckWaitStrategy()
                .forService("") // Empty string for overall health
                .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
    }
    
    @Override
    public String getDisplayName() {
        return getModuleName() + " module";
    }
    
    @Override
    protected String getSimpleName() {
        return getModuleName();
    }
    
    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        String module = getModuleName();
        return Arrays.asList(
            // External access (for tests)
            module + ".grpc.host",
            module + ".grpc.port",
            module + ".http.host",
            module + ".http.port",
            
            // Internal network access (for container-to-container)
            module + ".internal.host",
            module + ".internal.grpc.port",
            module + ".internal.http.port",
            
            // Debugging info
            module + ".container.id",
            module + ".container.logs"
        );
    }
    
    /**
     * Get or create the shared Docker network for all YAPPY containers
     */
    protected synchronized Network getSharedNetwork() {
        if (sharedNetwork == null) {
            LOG.info("Creating shared Docker network: {}", SHARED_NETWORK_NAME);
            sharedNetwork = Network.SHARED;
            // Note: Using SHARED network for simplicity. In production, might want custom network.
        }
        return sharedNetwork;
    }
    
    /**
     * Get the log directory for this module
     */
    protected Path getLogDirectory() {
        return Paths.get("build", "test-logs", getModuleName());
    }
    
    @Override
    protected ModuleContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        LOG.info("Creating {} container with image: {}", getModuleName(), imageName);
        
        // Create log directory
        Path logDir = getLogDirectory();
        try {
            Files.createDirectories(logDir);
            LOG.debug("Created log directory: {}", logDir);
        } catch (IOException e) {
            LOG.warn("Failed to create log directory: {}", logDir, e);
        }
        
        // Create container
        ModuleContainer container = new ModuleContainer(imageName, getModuleName());
        
        // Configure container
        container.withNetwork(getSharedNetwork())
                 .withNetworkAliases(getModuleName())
                 .withExposedPorts(GRPC_PORT, HTTP_PORT)
                 .withEnv("MICRONAUT_ENVIRONMENTS", "test")
                 .withEnv("GRPC_SERVER_PORT", String.valueOf(GRPC_PORT))
                 .withEnv("HTTP_SERVER_PORT", String.valueOf(HTTP_PORT));
        
        // Add module-specific environment
        Map<String, String> moduleEnv = getModuleEnvironment(testResourcesConfig);
        moduleEnv.forEach(container::withEnv);
        
        // Configure logging
        Path logFile = logDir.resolve("container.log");
        container.withLogConsumer(new FileLogConsumer(logFile));
        
        // Set wait strategy
        container.waitingFor(getWaitStrategy());
        
        LOG.info("{} container configured with network alias: {}", getModuleName(), getModuleName());
        return container;
    }
    
    @Override
    protected Optional<String> resolveProperty(String propertyName, ModuleContainer container) {
        String module = getModuleName();
        
        // External access properties
        if ((module + ".grpc.host").equals(propertyName)) {
            return Optional.of(container.getHost());
        }
        if ((module + ".grpc.port").equals(propertyName)) {
            return Optional.of(String.valueOf(container.getMappedPort(GRPC_PORT)));
        }
        if ((module + ".http.host").equals(propertyName)) {
            return Optional.of(container.getHost());
        }
        if ((module + ".http.port").equals(propertyName)) {
            return Optional.of(String.valueOf(container.getMappedPort(HTTP_PORT)));
        }
        
        // Internal network properties
        if ((module + ".internal.host").equals(propertyName)) {
            return Optional.of(getModuleName()); // Network alias
        }
        if ((module + ".internal.grpc.port").equals(propertyName)) {
            return Optional.of(String.valueOf(GRPC_PORT));
        }
        if ((module + ".internal.http.port").equals(propertyName)) {
            return Optional.of(String.valueOf(HTTP_PORT));
        }
        
        // Debug properties
        if ((module + ".container.id").equals(propertyName)) {
            return Optional.of(container.getContainerId());
        }
        if ((module + ".container.logs").equals(propertyName)) {
            return Optional.of(getLogDirectory().resolve("container.log").toString());
        }
        
        return Optional.empty();
    }
    
    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        return propertyName != null && propertyName.startsWith(getModuleName() + ".");
    }
}

/**
 * Simple log consumer that writes to a file
 */
class FileLogConsumer implements java.util.function.Consumer<org.testcontainers.containers.output.OutputFrame> {
    private final BufferedWriter writer;
    private final Path logFile;
    
    public FileLogConsumer(Path logFile) {
        this.logFile = logFile;
        try {
            this.writer = Files.newBufferedWriter(logFile, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create log file: " + logFile, e);
        }
    }
    
    @Override
    public void accept(org.testcontainers.containers.output.OutputFrame outputFrame) {
        try {
            writer.write(outputFrame.getUtf8String());
            writer.flush();
        } catch (IOException e) {
            // Log to console as fallback
            System.err.println("Failed to write to log file " + logFile + ": " + e.getMessage());
            System.err.print(outputFrame.getUtf8String());
        }
    }
}