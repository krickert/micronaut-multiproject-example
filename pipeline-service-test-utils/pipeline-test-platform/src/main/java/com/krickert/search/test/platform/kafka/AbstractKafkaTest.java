package com.krickert.search.test.platform.kafka;

import io.micronaut.test.support.TestPropertyProvider;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.apache.kafka.common.serialization.UUIDSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract base class for Kafka tests.
 * This class provides common functionality for Kafka tests with different schema registry implementations.
 */
public abstract class AbstractKafkaTest implements KafkaTest, TestPropertyProvider {
    protected static final Logger log = LoggerFactory.getLogger(AbstractKafkaTest.class);
    
    // Kafka container
    protected static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:latest")
    ).withPrivilegedMode(true).withAccessToHost(true);
    
    // Test container manager
    protected static final TestContainerManager containerManager = TestContainerManager.getInstance();
    
    // List of topics to create
    protected static final List<String> TOPICS = Arrays.asList(
        "test-pipeline-input",
        "test-pipeline-output",
        "test-PipeStream"
    );
    
    /**
     * Start the Kafka container.
     * This method is called before all tests.
     */
    @BeforeAll
    public static void setupKafka() {
        log.info("Setting up Kafka container...");
        if (!kafka.isRunning()) {
            kafka.start();
            log.info("Kafka container started at: {}", kafka.getBootstrapServers());
        }
    }
    
    /**
     * Set up the test environment before each test.
     * This method starts the containers and creates the topics.
     */
    @BeforeEach
    public void setUp() {
        startContainers();
        createTopics();
    }
    
    /**
     * Clean up the test environment after each test.
     * This method deletes the topics and resets the containers.
     */
    @AfterEach
    public void tearDown() {
        deleteTopics();
        resetContainers();
        // Force garbage collection to help clean up resources
        System.gc();
    }
    
    /**
     * Get the bootstrap servers for the Kafka container.
     * 
     * @return the bootstrap servers as a string
     */
    protected String getBootstrapServers() {
        if (!kafka.isRunning()) {
            log.info("Kafka container is not running, starting it...");
            kafka.start();
        }
        return kafka.getBootstrapServers();
    }
    
    /**
     * Create Kafka topics needed for tests.
     * This ensures that topics are available for tests.
     */
    @Override
    public void createTopics() {
        log.info("Creating Kafka topics...");
        try {
            Map<String, Object> adminProps = new HashMap<>();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
            
            try (AdminClient adminClient = AdminClient.create(adminProps)) {
                List<NewTopic> newTopics = TOPICS.stream()
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .toList();
                
                CreateTopicsResult result = adminClient.createTopics(newTopics);
                result.all().get(30, TimeUnit.SECONDS);
                log.info("Topics created successfully");
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.warn("Error creating topics: {}", e.getMessage());
            // Topics might already exist, which is fine
        }
    }
    
    /**
     * Delete Kafka topics after tests.
     * This ensures a clean state for the next test.
     */
    @Override
    public void deleteTopics() {
        log.info("Deleting Kafka topics...");
        try {
            Map<String, Object> adminProps = new HashMap<>();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
            
            try (AdminClient adminClient = AdminClient.create(adminProps)) {
                DeleteTopicsResult result = adminClient.deleteTopics(TOPICS);
                result.all().get(30, TimeUnit.SECONDS);
                log.info("Topics deleted successfully");
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.warn("Error deleting topics: {}", e.getMessage());
            // Topics might not exist, which is fine
        }
    }
    
    /**
     * Set up common Kafka properties.
     * This method sets up properties for Kafka producers and consumers.
     * 
     * @param props the properties map to add to
     */
    protected void setupKafkaProperties(Map<String, String> props) {
        // Bootstrap servers
        String bootstrapServers = getBootstrapServers();
        props.put("kafka.bootstrap.servers", bootstrapServers);
        
        // Producer properties
        String producerPrefix = "kafka.producers.default.";
        props.put(producerPrefix + ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(producerPrefix + ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        props.put(producerPrefix + ProducerConfig.ACKS_CONFIG, "all");
        props.put(producerPrefix + ProducerConfig.RETRIES_CONFIG, "3");
        
        // Consumer properties
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(consumerPrefix + ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        props.put(consumerPrefix + ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group");
        props.put(consumerPrefix + ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Add properties to container manager
        containerManager.setProperties(props);
    }
    
    /**
     * Get the properties for the test.
     * This method returns the properties from the container manager.
     * 
     * @return the properties map
     */
    @Override
    public Map<String, String> getProperties() {
        return containerManager.getProperties();
    }
}