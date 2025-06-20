package com.krickert.testcontainers.module;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import io.micronaut.testresources.testcontainers.TestContainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.nio.file.Paths;
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
    protected static final int GRPC_PORT = 50051;
    protected static final int HTTP_PORT = 8080;
    protected static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(120);
    
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
        // Note: gRPC health checks ARE working in the chunker module (verified by GrpcHealthCheckTest)
        // However, we use log-based wait strategy for compatibility with test resources framework
        // The "Server Running" message is emitted after both HTTP and gRPC servers are ready
        return Wait.forLogMessage(".*Server Running.*", 1)
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
     * Get the actual network that test resources is using.
     * This finds the network that other test resource containers (like Consul) are on.
     */
    protected String findTestResourcesNetwork() {
        try {
            var dockerClient = org.testcontainers.DockerClientFactory.instance().client();
            var containers = dockerClient.listContainersCmd()
                .withShowAll(false)
                .exec();
            
            // Look for known test resource containers (Consul, Kafka, etc.)
            for (var container : containers) {
                String image = container.getImage();
                if (image.contains("consul") || image.contains("kafka") || image.contains("apicurio")) {
                    var containerInfo = dockerClient.inspectContainerCmd(container.getId()).exec();
                    var networks = containerInfo.getNetworkSettings().getNetworks();
                    
                    for (String networkName : networks.keySet()) {
                        if (!"bridge".equals(networkName) && !"host".equals(networkName)) {
                            LOG.info("Found test resources network: {}", networkName);
                            return networkName;
                        }
                    }
                }
            }
            
            // Fallback to creating a new network if we can't find the test resources network
            LOG.warn("Could not find test resources network, creating new network");
            return TestContainers.network("test-network").getId();
        } catch (Exception e) {
            LOG.error("Error finding test resources network", e);
            // Fallback to creating a new network
            return TestContainers.network("test-network").getId();
        }
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
        
        // Create container
        ModuleContainer container = new ModuleContainer(imageName, getModuleName());
        
        // Find and use the same network as other test resource containers
        String networkName = findTestResourcesNetwork();
        
        // Configure container
        container.withCreateContainerCmdModifier(cmd -> {
                     cmd.getHostConfig().withNetworkMode(networkName);
                 })
                 .withNetworkAliases(getModuleName())
                 .withExposedPorts(GRPC_PORT, HTTP_PORT)
                 .withEnv("MICRONAUT_ENVIRONMENTS", "test")
                 .withEnv("GRPC_SERVER_PORT", String.valueOf(GRPC_PORT))
                 .withEnv("HTTP_SERVER_PORT", String.valueOf(HTTP_PORT));
        
        // Add module-specific environment
        Map<String, String> moduleEnv = getModuleEnvironment(testResourcesConfig);
        moduleEnv.forEach(container::withEnv);
        
        // Configure logging to use Slf4j
        Logger containerLogger = LoggerFactory.getLogger(getClass().getName() + "." + getModuleName());
        container.withLogConsumer(new Slf4jLogConsumer(containerLogger));
        
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
            return Optional.of("Container logs are written to SLF4J logger: " + getClass().getName() + "." + getModuleName());
        }
        
        return Optional.empty();
    }
    
    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        return propertyName != null && propertyName.startsWith(getModuleName() + ".");
    }
}