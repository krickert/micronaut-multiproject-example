package com.krickert.testcontainers.apicurio;

import io.apicurio.registry.serde.config.SerdeConfig; // Import SerdeConfig
import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn an Apicurio Registry test container.
 * It provides properties for the Apicurio Registry URL, related Kafka SerDe configuration
 * (using constants from SerdeConfig where applicable), and sets default Kafka key/value
 * serializers/deserializers for Apicurio Protobuf usage.
 */
public class ApicurioTestResourceProvider extends AbstractTestContainersProvider<GenericContainer<?>> {
    private static final Logger LOG = LoggerFactory.getLogger(ApicurioTestResourceProvider.class);

    // TestContainers Properties
    public static final String TESTCONTAINERS_PREFIX = "testcontainers";
    public static final String PROPERTY_TESTCONTAINERS_ENABLED = TESTCONTAINERS_PREFIX + ".enabled";
    public static final String PROPERTY_TESTCONTAINERS_APICURIO_ENABLED = TESTCONTAINERS_PREFIX + ".apicurio";

    // Apicurio Properties (direct, not Kafka-prefixed)
    // public static final String APICURIO_PREFIX = "apicurio"; // Defined in SerdeConfig
    public static final String PROPERTY_APICURIO_REGISTRY_URL = SerdeConfig.REGISTRY_URL; // e.g., apicurio.registry.url

    // Kafka Common Prefixes
    public static final String KAFKA_PREFIX = "kafka";
    public static final String PRODUCER_PREFIX = KAFKA_PREFIX + ".producers.default";
    public static final String CONSUMER_PREFIX = KAFKA_PREFIX + ".consumers.default";

    // Standard Kafka Serializer/Deserializer class properties (NOT from SerdeConfig)
    public static final String PROPERTY_PRODUCER_KEY_SERIALIZER_CLASS = PRODUCER_PREFIX + ".key.serializer";
    public static final String PROPERTY_PRODUCER_VALUE_SERIALIZER_CLASS = PRODUCER_PREFIX + ".value.serializer";
    public static final String PROPERTY_CONSUMER_KEY_DESERIALIZER_CLASS = CONSUMER_PREFIX + ".key.deserializer";
    public static final String PROPERTY_CONSUMER_VALUE_DESERIALIZER_CLASS = CONSUMER_PREFIX + ".value.deserializer";

    // Default Key Serializer/Deserializer (often String when values are complex)
    public static final String DEFAULT_KEY_SERIALIZER_CLASS = "org.apache.kafka.common.serialization.UUIDSerializer";
    public static final String DEFAULT_KEY_DESERIALIZER_CLASS = "org.apache.kafka.common.serialization.UUIDDeserializer";

    // Apicurio Protobuf Serializer/Deserializer class names for Values
    public static final String APICURIO_PROTOBUF_VALUE_SERIALIZER_CLASS = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer";
    public static final String APICURIO_PROTOBUF_VALUE_DESERIALIZER_CLASS = "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer";

    // --- Kafka-prefixed Apicurio SerDe properties using SerdeConfig constants ---
    // Generic registry URL properties (can be resolved to Apicurio endpoint for broader compatibility)
    public static final String PROPERTY_PRODUCER_GENERIC_REGISTRY_URL = PRODUCER_PREFIX + "." + SerdeConfig.REGISTRY_URL;
    public static final String PROPERTY_CONSUMER_GENERIC_REGISTRY_URL = CONSUMER_PREFIX + "." + SerdeConfig.REGISTRY_URL;  // For users
    // who might use a generic property

    // Apicurio specific registry URL for Kafka clients, using SerdeConfig.REGISTRY_URL
    public static final String PROPERTY_PRODUCER_APICURIO_REGISTRY_URL = PRODUCER_PREFIX + "." + SerdeConfig.REGISTRY_URL;
    public static final String PROPERTY_CONSUMER_APICURIO_REGISTRY_URL = CONSUMER_PREFIX + "." + SerdeConfig.REGISTRY_URL;

    // Apicurio Auto Register Artifact, using SerdeConfig.AUTO_REGISTER_ARTIFACT
    public static final String PROPERTY_PRODUCER_APICURIO_AUTO_REGISTER_ARTIFACT = PRODUCER_PREFIX + "." + SerdeConfig.AUTO_REGISTER_ARTIFACT;

    // Apicurio Artifact Resolver Strategy, using SerdeConfig.ARTIFACT_RESOLVER_STRATEGY (inherited from SchemaResolverConfig)
    public static final String PROPERTY_PRODUCER_APICURIO_ARTIFACT_RESOLVER_STRATEGY = PRODUCER_PREFIX + "." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY;
    public static final String PROPERTY_CONSUMER_APICURIO_ARTIFACT_RESOLVER_STRATEGY = CONSUMER_PREFIX + "." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY;
    public static final String DEFAULT_ARTIFACT_RESOLVER_STRATEGY = io.apicurio.registry.serde.strategy.TopicIdStrategy.class.getName(); // SerdeConfig.ARTIFACT_RESOLVER_STRATEGY_DEFAULT;

    // Apicurio Explicit Artifact Group ID, using SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID
    public static final String PROPERTY_PRODUCER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID = PRODUCER_PREFIX + "." + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID;
    public static final String PROPERTY_CONSUMER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID = CONSUMER_PREFIX + "." + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID;
    public static final String DEFAULT_EXPLICIT_ARTIFACT_GROUP_ID = "default"; // Or use SerdeConfig if it has a default constant for this

    // Apicurio Deserializer Specific Value Return Class, using SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS
    public static final String PROPERTY_CONSUMER_APICURIO_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS = CONSUMER_PREFIX + "." + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS;
    public static final String DEFAULT_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS = "com.krickert.search.model.PipeStream"; // As per your example

    // Original property for broader compatibility if ".auto.register.artifact" is used without "apicurio." prefixing the SerdeConfig constant part
    public static final String PROPERTY_PRODUCER_GENERIC_AUTO_REGISTER_ARTIFACT = PRODUCER_PREFIX + ".auto.register.artifact";


    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_APICURIO_REGISTRY_URL, // Direct apicurio.registry.url
            PROPERTY_PRODUCER_GENERIC_REGISTRY_URL,
            PROPERTY_CONSUMER_GENERIC_REGISTRY_URL,
            PROPERTY_PRODUCER_APICURIO_REGISTRY_URL,
            PROPERTY_CONSUMER_APICURIO_REGISTRY_URL,
            PROPERTY_PRODUCER_GENERIC_AUTO_REGISTER_ARTIFACT,
            PROPERTY_PRODUCER_APICURIO_AUTO_REGISTER_ARTIFACT,
            PROPERTY_PRODUCER_APICURIO_ARTIFACT_RESOLVER_STRATEGY,
            PROPERTY_CONSUMER_APICURIO_ARTIFACT_RESOLVER_STRATEGY,
            PROPERTY_PRODUCER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID,
            PROPERTY_CONSUMER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID,
            PROPERTY_CONSUMER_APICURIO_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS,
            // Standard Kafka key/value serializer/deserializer properties
            PROPERTY_PRODUCER_KEY_SERIALIZER_CLASS,
            PROPERTY_PRODUCER_VALUE_SERIALIZER_CLASS,
            PROPERTY_CONSUMER_KEY_DESERIALIZER_CLASS,
            PROPERTY_CONSUMER_VALUE_DESERIALIZER_CLASS
    ));

    public static final String DEFAULT_IMAGE = "apicurio/apicurio-registry:latest";
    public static final int APICURIO_PORT = 8080;
    public static final String SIMPLE_NAME = "apicurio-registry";
    public static final String DISPLAY_NAME = "Apicurio Registry";


    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
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
    protected GenericContainer<?> createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        LOG.info("Creating Apicurio container with image: {}", imageName);
        return new GenericContainer<>(imageName)
                .withExposedPorts(APICURIO_PORT)
                .withEnv("QUARKUS_PROFILE", "prod")
                .withStartupTimeout(java.time.Duration.ofSeconds(120));
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, GenericContainer<?> container) {
        LOG.info("Resolving property '{}' for Apicurio container: {}", propertyName, container.getContainerName());
        String registryUrl = String.format("http://%s:%d/apis/registry/v3",
                container.getHost(),
                container.getMappedPort(APICURIO_PORT));

        LOG.info("ApicurioRegistry URL: {}", registryUrl);

        Optional<String> resolvedValue = Optional.empty();

        // Direct Apicurio Registry URL (not Kafka prefixed)
        if (PROPERTY_APICURIO_REGISTRY_URL.equals(propertyName)) { // This is SerdeConfig.REGISTRY_URL
            resolvedValue = Optional.of(registryUrl);
        }
        // Kafka-prefixed Apicurio Registry URLs
        else if (PROPERTY_PRODUCER_GENERIC_REGISTRY_URL.equals(propertyName) ||
                PROPERTY_CONSUMER_GENERIC_REGISTRY_URL.equals(propertyName) ||
                PROPERTY_PRODUCER_APICURIO_REGISTRY_URL.equals(propertyName) || // Uses SerdeConfig.REGISTRY_URL
                PROPERTY_CONSUMER_APICURIO_REGISTRY_URL.equals(propertyName)) {  // Uses SerdeConfig.REGISTRY_URL
            resolvedValue = Optional.of(registryUrl);
        }
        // Auto Register Artifact
        else if (PROPERTY_PRODUCER_GENERIC_AUTO_REGISTER_ARTIFACT.equals(propertyName) ||
                PROPERTY_PRODUCER_APICURIO_AUTO_REGISTER_ARTIFACT.equals(propertyName)) { // Uses SerdeConfig.AUTO_REGISTER_ARTIFACT
            resolvedValue = Optional.of("true");
        }
        // Artifact Resolver Strategy
        else if (PROPERTY_PRODUCER_APICURIO_ARTIFACT_RESOLVER_STRATEGY.equals(propertyName) || // Uses SerdeConfig.ARTIFACT_RESOLVER_STRATEGY
                PROPERTY_CONSUMER_APICURIO_ARTIFACT_RESOLVER_STRATEGY.equals(propertyName)) {  // Uses SerdeConfig.ARTIFACT_RESOLVER_STRATEGY
            resolvedValue = Optional.of(DEFAULT_ARTIFACT_RESOLVER_STRATEGY);
        }
        // Explicit Artifact Group ID
        else if (PROPERTY_PRODUCER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID.equals(propertyName) || // Uses SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID
                PROPERTY_CONSUMER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID.equals(propertyName)) {  // Uses SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID
            resolvedValue = Optional.of(DEFAULT_EXPLICIT_ARTIFACT_GROUP_ID);
        }
        // Deserializer Specific Value Return Class
        else if (PROPERTY_CONSUMER_APICURIO_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS.equals(propertyName)) { // Uses SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS
            resolvedValue = Optional.of(DEFAULT_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS);
        }
        // Standard Kafka Key & Value Serializer/Deserializer (NOT from SerdeConfig, but set by this provider for Apicurio Protobuf)
        else if (PROPERTY_PRODUCER_KEY_SERIALIZER_CLASS.equals(propertyName)) {
            resolvedValue = Optional.of(DEFAULT_KEY_SERIALIZER_CLASS);
        } else if (PROPERTY_PRODUCER_VALUE_SERIALIZER_CLASS.equals(propertyName)) {
            resolvedValue = Optional.of(APICURIO_PROTOBUF_VALUE_SERIALIZER_CLASS);
        } else if (PROPERTY_CONSUMER_KEY_DESERIALIZER_CLASS.equals(propertyName)) {
            resolvedValue = Optional.of(DEFAULT_KEY_DESERIALIZER_CLASS);
        } else if (PROPERTY_CONSUMER_VALUE_DESERIALIZER_CLASS.equals(propertyName)) {
            resolvedValue = Optional.of(APICURIO_PROTOBUF_VALUE_DESERIALIZER_CLASS);
        }

        if (resolvedValue.isPresent()) {
            LOG.info("ApicurioTestResourceProvider resolved property '{}' to '{}'", propertyName, resolvedValue.get());
        }
        return resolvedValue;
    }

    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        boolean canAnswer = propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
        if (canAnswer) {
            LOG.debug("ApicurioTestResourceProvider will attempt to answer for property: {}", propertyName);
        }
        return canAnswer;
    }
}