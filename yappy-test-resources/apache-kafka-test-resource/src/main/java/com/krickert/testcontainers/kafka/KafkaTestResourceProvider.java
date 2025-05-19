package com.krickert.testcontainers.kafka;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn a Kafka test container.
 * It provides properties for Kafka bootstrap servers and client configuration.
 */
public class KafkaTestResourceProvider extends AbstractTestContainersProvider<KafkaContainer> {
    // TestContainers Properties
    public static final String TESTCONTAINERS_PREFIX = "testcontainers";
    public static final String PROPERTY_TESTCONTAINERS_ENABLED = TESTCONTAINERS_PREFIX + ".enabled";
    public static final String PROPERTY_TESTCONTAINERS_KAFKA_ENABLED = TESTCONTAINERS_PREFIX + ".kafka";
    // Kafka Properties
    public static final String KAFKA_PREFIX = "kafka";
    public static final String PROPERTY_KAFKA_BOOTSTRAP_SERVERS = KAFKA_PREFIX + ".bootstrap.servers";
    public static final String PROPERTY_KAFKA_BROKERS = KAFKA_PREFIX + ".brokers";
    // Producer Properties
    public static final String PRODUCER_PREFIX = KAFKA_PREFIX + ".producers.default";
    public static final String PROPERTY_PRODUCER_BOOTSTRAP_SERVERS = PRODUCER_PREFIX + ".bootstrap.servers";
    public static final String PROPERTY_PRODUCER_KEY_SERIALIZER = PRODUCER_PREFIX + ".key.serializer";
    public static final String PROPERTY_PRODUCER_VALUE_SERIALIZER = PRODUCER_PREFIX + ".value.serializer";
    // Consumer Properties
    public static final String CONSUMER_PREFIX = KAFKA_PREFIX + ".consumers.default";
    public static final String PROPERTY_CONSUMER_BOOTSTRAP_SERVERS = CONSUMER_PREFIX + ".bootstrap.servers";
    public static final String PROPERTY_CONSUMER_KEY_DESERIALIZER = CONSUMER_PREFIX + ".key.deserializer";
    public static final String PROPERTY_CONSUMER_VALUE_DESERIALIZER = CONSUMER_PREFIX + ".value.deserializer";
    // Admin Properties
    public static final String PROPERTY_ADMIN_BOOTSTRAP_SERVERS = KAFKA_PREFIX + ".bootstrap.servers.config";
    public static final String PROPERTY_ADMIN_REQUEST_TIMEOUT = KAFKA_PREFIX + ".request.timeout.ms";
    public static final String PROPERTY_ADMIN_DEFAULT_API_TIMEOUT = KAFKA_PREFIX + ".default.api.timeout.ms";
    // Combined list of properties this provider can resolve
    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_KAFKA_BOOTSTRAP_SERVERS,
            PROPERTY_KAFKA_BROKERS,
            PROPERTY_PRODUCER_BOOTSTRAP_SERVERS,
            PROPERTY_CONSUMER_BOOTSTRAP_SERVERS,
            PROPERTY_ADMIN_BOOTSTRAP_SERVERS,
            PROPERTY_ADMIN_REQUEST_TIMEOUT,
            PROPERTY_ADMIN_DEFAULT_API_TIMEOUT
    ));
    public static final String DEFAULT_IMAGE = "apache/kafka:latest";
    public static final String SIMPLE_NAME = "apache-kafka";
    public static final String DISPLAY_NAME = "Kafka";
    private static final Logger LOG = LoggerFactory.getLogger(KafkaTestResourceProvider.class);

    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        // Return all properties we can resolve
        return RESOLVABLE_PROPERTIES_LIST;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    protected String getSimpleName() {
        return SIMPLE_NAME;
    }

    @Override
    protected String getDefaultImageName() {
        return DEFAULT_IMAGE;
    }

    @Override
    protected KafkaContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        // Create a new Kafka container with the specified image
        return new KafkaContainer(imageName);
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, KafkaContainer container) {
        // Resolve Kafka bootstrap servers property
        if (PROPERTY_KAFKA_BOOTSTRAP_SERVERS.equals(propertyName) ||
                PROPERTY_KAFKA_BROKERS.equals(propertyName) ||
                PROPERTY_PRODUCER_BOOTSTRAP_SERVERS.equals(propertyName) ||
                PROPERTY_CONSUMER_BOOTSTRAP_SERVERS.equals(propertyName) ||
                PROPERTY_ADMIN_BOOTSTRAP_SERVERS.equals(propertyName)) {
            return Optional.of(container.getBootstrapServers());
        }

        // Set default timeout values for admin client
        if (PROPERTY_ADMIN_REQUEST_TIMEOUT.equals(propertyName)) {
            return Optional.of("30000");
        }
        if (PROPERTY_ADMIN_DEFAULT_API_TIMEOUT.equals(propertyName)) {
            return Optional.of("30000");
        }

        return Optional.empty(); // Property not handled by this provider
    }

    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Answer if the property is one we can resolve
        return propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
    }
}
