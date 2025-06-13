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
    private static final String ENGINE_IMAGE = "engine:latest";
    private static final String ENGINE_NETWORK_ALIAS = "engine";
    
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
        
        // Wait a moment to ensure other containers are starting
        try {
            logger.info("Waiting for infrastructure containers to be ready...");
            Thread.sleep(5000); // Give Consul, Kafka, etc. time to start
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Find and use the same network as other test resource containers
        String networkName = findTestResourcesNetwork();
        
        GenericContainer<?> container = new GenericContainer<>(imageName)
                .withExposedPorts(8090, 50070)
                .withCreateContainerCmdModifier(cmd -> {
                    cmd.getHostConfig().withNetworkMode(networkName);
                })
                .withNetworkAliases(ENGINE_NETWORK_ALIAS)
                .withLogConsumer(new Slf4jLogConsumer(logger))
                .waitingFor(Wait.forHttp("/health")
                        .forPort(8090)
                        .withStartupTimeout(Duration.ofMinutes(5)));  // Increased timeout for dependencies
        
        // Set environment variables for infrastructure services
        Map<String, String> envVars = new HashMap<>();
        envVars.put("MICRONAUT_ENVIRONMENTS", "docker,test");
        envVars.put("GRPC_SERVER_PORT", "50070");
        envVars.put("GRPC_SERVER_HOST", "0.0.0.0");
        envVars.put("MICRONAUT_SERVER_PORT", "8090");
        envVars.put("MICRONAUT_SERVER_HOST", "0.0.0.0");
        // CRITICAL: Disable test resources client inside the container
        envVars.put("MICRONAUT_TEST_RESOURCES_ENABLED", "false");
        // Use test resource network aliases that match what other providers use
        envVars.put("CONSUL_CLIENT_HOST", "consul");
        envVars.put("CONSUL_CLIENT_PORT", "8500");
        // Kafka configuration - use container network aliases
        envVars.put("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        envVars.put("KAFKA_BROKERS", "kafka:9092");
        envVars.put("KAFKA_PRODUCERS_DEFAULT_BOOTSTRAP_SERVERS", "kafka:9092");
        envVars.put("KAFKA_CONSUMERS_DEFAULT_BOOTSTRAP_SERVERS", "kafka:9092");
        envVars.put("KAFKA_BOOTSTRAP_SERVERS_CONFIG", "kafka:9092");
        envVars.put("KAFKA_ENABLED", "true");
        // Apicurio Registry configuration
        envVars.put("APICURIO_REGISTRY_URL", "http://apicurio:8080");
        envVars.put("KAFKA_PRODUCERS_DEFAULT_APICURIO_REGISTRY_URL", "http://apicurio:8080/apis/registry/v3");
        envVars.put("KAFKA_CONSUMERS_DEFAULT_APICURIO_REGISTRY_URL", "http://apicurio:8080/apis/registry/v3");
        envVars.put("OPENSEARCH_URL", "http://opensearch:9200");
        envVars.put("AWS_ENDPOINT", "http://localstack:4566");
        
        // Add missing configuration properties that engine requires
        envVars.put("APP_CONFIG_CONSUL_KEY_PREFIXES_PIPELINE_CLUSTERS", "config/pipeline/clusters/");
        envVars.put("APP_CONFIG_CONSUL_KEY_PREFIXES_SCHEMA_VERSIONS", "config/pipeline/schemas/");
        envVars.put("APP_CONFIG_CONSUL_KEY_PREFIXES_WHITELISTS", "config/pipeline/whitelists/");
        envVars.put("APP_CONFIG_CONSUL_WATCH_SECONDS", "5");
        envVars.put("APP_CONFIG_CLUSTER_NAME", "integration-test-cluster");
        envVars.put("YAPPY_CLUSTER_NAME", "integration-test-cluster");
        envVars.put("ENGINE_CLUSTER_NAME", "integration-test-cluster");
        
        // Module aliases on shared network - matching the actual aliases from module providers
        envVars.put("CHUNKER_GRPC_HOST", "chunker");
        envVars.put("CHUNKER_GRPC_PORT", "50051");
        envVars.put("TIKA_GRPC_HOST", "tika");
        envVars.put("TIKA_GRPC_PORT", "50051");
        envVars.put("EMBEDDER_GRPC_HOST", "embedder");
        envVars.put("EMBEDDER_GRPC_PORT", "50051");
        envVars.put("ECHO_GRPC_HOST", "echo");
        envVars.put("ECHO_GRPC_PORT", "50051");
        envVars.put("TEST_MODULE_GRPC_HOST", "test-module");
        envVars.put("TEST_MODULE_GRPC_PORT", "50062");  // test-module uses port 50062
        
        container.withEnv(envVars);
        
        return container;
    }
    
    @Override
    protected Optional<String> resolveProperty(String propertyName, GenericContainer<?> container) {
        return switch (propertyName) {
            case "engine.grpc.host" -> Optional.of(container.getHost());
            case "engine.grpc.port" -> Optional.of(String.valueOf(container.getMappedPort(50070)));
            case "engine.http.host" -> Optional.of(container.getHost());
            case "engine.http.port" -> Optional.of(String.valueOf(container.getMappedPort(8090)));
            default -> Optional.empty();
        };
    }
    
    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        return propertyName.startsWith("engine.");
    }
    
    @Override
    public List<String> getRequiredProperties(String expression) {
        // The engine container depends on these services being available
        // By declaring these as required properties, the test resources framework
        // will ensure these containers are started BEFORE attempting to create
        // the engine container. This prevents connection failures during startup.
        if (expression.startsWith("engine.")) {
            logger.info("Engine property {} requested - declaring required dependencies", expression);
            return Arrays.asList(
                "consul.client.host",        // Consul must be running first
                "consul.client.port",    
                "kafka.bootstrap.servers",   // Kafka must be running first
                "apicurio.registry.url",     // Apicurio registry must be running
                "aws.endpoint"               // Moto/LocalStack must be running
            );
        }
        return Collections.emptyList();
    }
}