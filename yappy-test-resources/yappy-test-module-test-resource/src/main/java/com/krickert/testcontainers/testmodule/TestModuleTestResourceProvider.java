package com.krickert.testcontainers.testmodule;

import com.krickert.testcontainers.kafka.KafkaTestResourceProvider;
import com.krickert.testcontainers.module.AbstractModuleTestResourceProvider;
import com.krickert.testcontainers.module.ModuleContainer;
import io.micronaut.testresources.core.TestResourcesResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;

/**
 * Test resource provider for the Test Module.
 * This module has special requirements as it needs access to Kafka.
 * It runs on gRPC port 50062.
 */
public class TestModuleTestResourceProvider extends AbstractModuleTestResourceProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(TestModuleTestResourceProvider.class);
    private static final String MODULE_NAME = "test-module";
    private static final String DEFAULT_IMAGE = "test-module:latest";
    private static final int TEST_MODULE_GRPC_PORT = 50062;
    
    @Override
    protected String getModuleName() {
        return MODULE_NAME;
    }
    
    @Override
    protected String getDefaultImageName() {
        return DEFAULT_IMAGE;
    }
    
    @Override
    protected Map<String, String> getModuleEnvironment(Map<String, Object> testResourcesConfig) {
        Map<String, String> env = new HashMap<>();
        
        // Enable Kafka in the test module
        env.put("KAFKA_ENABLED", "true");
        
        // Look for Kafka bootstrap servers in test resources config
        Object kafkaBootstrapServers = testResourcesConfig.get(KafkaTestResourceProvider.PROPERTY_KAFKA_BOOTSTRAP_SERVERS);
        if (kafkaBootstrapServers != null) {
            LOG.info("Setting KAFKA_BOOTSTRAP_SERVERS to: {}", kafkaBootstrapServers);
            env.put("KAFKA_BOOTSTRAP_SERVERS", kafkaBootstrapServers.toString());
        } else {
            LOG.warn("Kafka bootstrap servers not found in test resources config. " +
                    "Test module will use default value from application.yml");
        }
        
        // Look for Apicurio registry URL in test resources config
        Object apicurioUrl = testResourcesConfig.get("apicurio.registry.url");
        if (apicurioUrl != null) {
            LOG.info("Setting APICURIO_REGISTRY_URL to: {}", apicurioUrl);
            env.put("APICURIO_REGISTRY_URL", apicurioUrl.toString());
        } else {
            LOG.warn("Apicurio registry URL not found in test resources config. " +
                    "Schema registry integration may not work properly.");
        }
        
        // Set the specific gRPC port for this module
        env.put("GRPC_SERVER_PORT", String.valueOf(TEST_MODULE_GRPC_PORT));
        
        return env;
    }
    
    @Override
    protected WaitStrategy getWaitStrategy() {
        // Use log-based wait strategy for the test module
        return Wait.forLogMessage(".*Server Running.*", 1)
                .withStartupTimeout(Duration.ofSeconds(120));
    }
    
    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        // Get base properties from parent
        List<String> baseProperties = super.getResolvableProperties(propertyEntries, testResourcesConfig);
        
        // Add any test-module specific properties if needed
        List<String> properties = new ArrayList<>(baseProperties);
        
        return properties;
    }
    
    @Override
    protected ModuleContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        LOG.info("Creating {} container with image: {}", getModuleName(), imageName);
        
        // Use parent's createContainer method which handles logging setup
        ModuleContainer container = super.createContainer(imageName, requestedProperties, testResourcesConfig);
        
        // Override the exposed ports for test-module's custom gRPC port
        // Clear default ports and add our custom ones
        container.setExposedPorts(Arrays.asList(TEST_MODULE_GRPC_PORT, HTTP_PORT));
        
        // Override the gRPC port environment variable
        container.withEnv("GRPC_SERVER_PORT", String.valueOf(TEST_MODULE_GRPC_PORT));
        
        LOG.info("{} container configured with network alias: {} and gRPC port: {}", 
                getModuleName(), getModuleName(), TEST_MODULE_GRPC_PORT);
        
        return container;
    }
    
    @Override
    protected Optional<String> resolveProperty(String propertyName, ModuleContainer container) {
        String module = getModuleName();
        
        // Override gRPC port resolution for test-module
        if ((module + ".grpc.port").equals(propertyName)) {
            return Optional.of(String.valueOf(container.getMappedPort(TEST_MODULE_GRPC_PORT)));
        }
        if ((module + ".internal.grpc.port").equals(propertyName)) {
            return Optional.of(String.valueOf(TEST_MODULE_GRPC_PORT));
        }
        
        // For all other properties, use parent implementation
        return super.resolveProperty(propertyName, container);
    }
    
}