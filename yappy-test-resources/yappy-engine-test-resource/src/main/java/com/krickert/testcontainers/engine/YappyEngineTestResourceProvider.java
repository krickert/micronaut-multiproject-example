package com.krickert.testcontainers.engine;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import io.micronaut.testresources.testcontainers.TestContainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;

public class YappyEngineTestResourceProvider extends AbstractTestContainersProvider<GenericContainer<?>> {
    
    private static final Logger logger = LoggerFactory.getLogger(YappyEngineTestResourceProvider.class);
    private static final String ENGINE_IMAGE = "yappy-orchestrator:latest";
    private static final String ENGINE_NETWORK_ALIAS = "yappy-engine";
    
    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        return Arrays.asList(
            "engine.grpc.host",
            "engine.grpc.port",
            "engine.http.host",
            "engine.http.port"
        );
    }
    
    @Override
    protected String getSimpleName() {
        return "yappy-engine";
    }
    
    @Override
    protected String getDefaultImageName() {
        return ENGINE_IMAGE;
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
                            logger.info("Found test resources network: {}", networkName);
                            return networkName;
                        }
                    }
                }
            }
            
            // Fallback to creating a new network if we can't find the test resources network
            logger.warn("Could not find test resources network, creating new network");
            return TestContainers.network("test-network").getId();
        } catch (Exception e) {
            logger.error("Error finding test resources network", e);
            // Fallback to creating a new network
            return TestContainers.network("test-network").getId();
        }
    }
    
    @Override
    protected GenericContainer<?> createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        logger.info("Creating Yappy Engine container...");
        
        // Find and use the same network as other test resource containers
        String networkName = findTestResourcesNetwork();
        
        GenericContainer<?> container = new GenericContainer<>(imageName)
                .withExposedPorts(8080, 50000)
                .withCreateContainerCmdModifier(cmd -> {
                    cmd.getHostConfig().withNetworkMode(networkName);
                })
                .withNetworkAliases(ENGINE_NETWORK_ALIAS)
                .withLogConsumer(new Slf4jLogConsumer(logger))
                .waitingFor(Wait.forHttp("/health")
                        .forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        
        // Set environment variables for infrastructure services
        Map<String, String> envVars = new HashMap<>();
        envVars.put("MICRONAUT_ENVIRONMENTS", "docker,test");
        envVars.put("CONSUL_CLIENT_HOST", "consul");
        envVars.put("CONSUL_CLIENT_PORT", "8500");
        envVars.put("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        envVars.put("APICURIO_REGISTRY_URL", "http://apicurio:8080");
        envVars.put("OPENSEARCH_URL", "http://opensearch:9200");
        envVars.put("AWS_ENDPOINT", "http://localstack:4566");
        
        // Module aliases on shared network
        envVars.put("CHUNKER_GRPC_HOST", "yappy-chunker");
        envVars.put("CHUNKER_GRPC_PORT", "50051");
        envVars.put("TIKA_GRPC_HOST", "yappy-tika");
        envVars.put("TIKA_GRPC_PORT", "50051");
        envVars.put("EMBEDDER_GRPC_HOST", "yappy-embedder");
        envVars.put("EMBEDDER_GRPC_PORT", "50051");
        envVars.put("ECHO_GRPC_HOST", "yappy-echo");
        envVars.put("ECHO_GRPC_PORT", "50051");
        envVars.put("TEST_MODULE_GRPC_HOST", "yappy-test-module");
        envVars.put("TEST_MODULE_GRPC_PORT", "50051");
        
        container.withEnv(envVars);
        
        return container;
    }
    
    @Override
    protected Optional<String> resolveProperty(String propertyName, GenericContainer<?> container) {
        return switch (propertyName) {
            case "engine.grpc.host" -> Optional.of(container.getHost());
            case "engine.grpc.port" -> Optional.of(String.valueOf(container.getMappedPort(50000)));
            case "engine.http.host" -> Optional.of(container.getHost());
            case "engine.http.port" -> Optional.of(String.valueOf(container.getMappedPort(8080)));
            default -> Optional.empty();
        };
    }
    
    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        return propertyName.startsWith("engine.");
    }
}