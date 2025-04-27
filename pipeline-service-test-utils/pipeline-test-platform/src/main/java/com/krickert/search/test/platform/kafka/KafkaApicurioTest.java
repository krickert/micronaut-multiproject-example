package com.krickert.search.test.platform.kafka;

import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of KafkaTest using Apicurio Registry.
 * This class manages the Kafka and Apicurio Registry containers and provides configuration for tests.
 */
public class KafkaApicurioTest extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaApicurioTest.class);
    
    // Registry type
    private static final String REGISTRY_TYPE = "apicurio";
    
    // Apicurio container
    private static final GenericContainer<?> apicurioContainer = new GenericContainer<>(
            DockerImageName.parse("apicurio/apicurio-registry:latest")
    )
            .withExposedPorts(8080)
            .withAccessToHost(true)
            .withEnv(Map.of(
                    "QUARKUS_PROFILE", "prod",
                    "REGISTRY_STORAGE_KIND", "in-memory",
                    "REGISTRY_AUTH_ANONYMOUS_READ_ACCESS_ENABLED", "true",
                    "REGISTRY_LOG_LEVEL", "DEBUG"
            ))
            .withStartupTimeout(Duration.ofSeconds(60))
            .withReuse(false);
    
    // Registry endpoint
    private static String registryEndpoint;
    
    // Default return class for the deserializer
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream";
    
    // Return class for the deserializer
    private final String returnClass;
    
    /**
     * Constructor that initializes the test with a specific return class.
     * 
     * @param returnClass the return class for the deserializer
     */
    public KafkaApicurioTest(String returnClass) {
        this.returnClass = returnClass;
        // Set the registry type in the container manager
        containerManager.setProperty(TestContainerManager.KAFKA_REGISTRY_TYPE_PROP, REGISTRY_TYPE);
    }
    
    /**
     * Default constructor that initializes the test with the default return class.
     */
    public KafkaApicurioTest() {
        this(DEFAULT_RETURN_CLASS);
    }
    
    /**
     * Get the registry type.
     * 
     * @return the registry type
     */
    @Override
    public String getRegistryType() {
        return REGISTRY_TYPE;
    }
    
    /**
     * Get the registry endpoint.
     * 
     * @return the registry endpoint
     */
    @Override
    public String getRegistryEndpoint() {
        if (registryEndpoint == null) {
            startContainers();
        }
        return registryEndpoint;
    }
    
    /**
     * Start the Kafka and Apicurio Registry containers.
     */
    @Override
    public void startContainers() {
        // Start Kafka if not already running
        if (!kafka.isRunning()) {
            log.info("Starting Kafka container...");
            kafka.start();
        }
        
        // Start Apicurio if not already running
        if (!apicurioContainer.isRunning()) {
            log.info("Starting Apicurio Registry container...");
            apicurioContainer.start();
            
            // Set the registry endpoint
            registryEndpoint = "http://" + apicurioContainer.getHost() + ":" 
                    + apicurioContainer.getMappedPort(8080) + "/apis/registry/v3";
            log.info("Apicurio Registry endpoint: {}", registryEndpoint);
        }
        
        // Set up properties
        setupProperties();
    }
    
    /**
     * Check if the containers are running.
     * 
     * @return true if both containers are running, false otherwise
     */
    @Override
    public boolean areContainersRunning() {
        return kafka.isRunning() && apicurioContainer.isRunning();
    }
    
    /**
     * Reset the containers between tests.
     */
    @Override
    public void resetContainers() {
        log.info("Resetting containers...");
        // No need to reset Apicurio container, just clear the properties
        containerManager.clearProperties();
        // Set up properties again
        setupProperties();
    }
    
    /**
     * Set up the properties for the test.
     */
    private void setupProperties() {
        Map<String, String> props = new HashMap<>();
        
        // Set up common Kafka properties
        setupKafkaProperties(props);
        
        // Set up Apicurio Registry properties
        String producerPrefix = "kafka.producers.default.";
        props.put(producerPrefix + SerdeConfig.REGISTRY_URL, registryEndpoint);
        props.put(producerPrefix + SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        props.put(producerPrefix + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        props.put(producerPrefix + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, "default");
        props.put(producerPrefix + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ProtobufKafkaSerializer.class.getName());
        
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + SerdeConfig.REGISTRY_URL, registryEndpoint);
        props.put(consumerPrefix + SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        props.put(consumerPrefix + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        props.put(consumerPrefix + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, "default");
        props.put(consumerPrefix + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ProtobufKafkaDeserializer.class.getName());
        props.put(consumerPrefix + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, returnClass);
        
        // Add properties to container manager
        containerManager.setProperties(props);
    }
}