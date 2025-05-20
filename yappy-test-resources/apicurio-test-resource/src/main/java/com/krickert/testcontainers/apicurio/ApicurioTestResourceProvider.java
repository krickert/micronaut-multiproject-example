package com.krickert.testcontainers.apicurio;

import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn an Apicurio Registry test container
 * using the ApicurioContainer class.
 * It provides properties for the Apicurio Registry URL, related Kafka SerDe configuration
 * (using constants from SerdeConfig where applicable), and sets default Kafka key/value
 * serializers/deserializers for Apicurio Protobuf usage.
 */
public class ApicurioTestResourceProvider extends AbstractTestContainersProvider<ApicurioContainer> { // Changed to ApicurioContainer
    // Apicurio Properties (direct, not Kafka-prefixed)
    public static final String PROPERTY_APICURIO_REGISTRY_URL = SerdeConfig.REGISTRY_URL; // e.g., apicurio.registry.url
    // Kafka Common Prefixes
    public static final String KAFKA_PREFIX = "kafka";
    public static final String PRODUCER_PREFIX = KAFKA_PREFIX + ".producers.default";
    // Standard Kafka Serializer/Deserializer class properties (NOT from SerdeConfig)
    public static final String PROPERTY_PRODUCER_KEY_SERIALIZER_CLASS = PRODUCER_PREFIX + ".key.serializer";
    public static final String PROPERTY_PRODUCER_VALUE_SERIALIZER_CLASS = PRODUCER_PREFIX + ".value.serializer";
    // --- Kafka-prefixed Apicurio SerDe properties using SerdeConfig constants ---
    public static final String PROPERTY_PRODUCER_GENERIC_REGISTRY_URL = PRODUCER_PREFIX + "." + SerdeConfig.REGISTRY_URL;
    public static final String PROPERTY_PRODUCER_APICURIO_REGISTRY_URL = PRODUCER_PREFIX + "." + SerdeConfig.REGISTRY_URL;
    public static final String PROPERTY_PRODUCER_APICURIO_AUTO_REGISTER_ARTIFACT = PRODUCER_PREFIX + "." + SerdeConfig.AUTO_REGISTER_ARTIFACT;
    public static final String PROPERTY_PRODUCER_APICURIO_ARTIFACT_RESOLVER_STRATEGY = PRODUCER_PREFIX + "." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY;
    public static final String PROPERTY_PRODUCER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID = PRODUCER_PREFIX + "." + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID;
    public static final String PROPERTY_PRODUCER_GENERIC_AUTO_REGISTER_ARTIFACT = PRODUCER_PREFIX + ".auto.register.artifact";
    public static final String CONSUMER_PREFIX = KAFKA_PREFIX + ".consumers.default";
    public static final String PROPERTY_CONSUMER_KEY_DESERIALIZER_CLASS = CONSUMER_PREFIX + ".key.deserializer";
    public static final String PROPERTY_CONSUMER_VALUE_DESERIALIZER_CLASS = CONSUMER_PREFIX + ".value.deserializer";
    public static final String PROPERTY_CONSUMER_GENERIC_REGISTRY_URL = CONSUMER_PREFIX + "." + SerdeConfig.REGISTRY_URL;
    public static final String PROPERTY_CONSUMER_APICURIO_REGISTRY_URL = CONSUMER_PREFIX + "." + SerdeConfig.REGISTRY_URL;
    public static final String PROPERTY_CONSUMER_APICURIO_ARTIFACT_RESOLVER_STRATEGY = CONSUMER_PREFIX + "." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY;
    public static final String PROPERTY_CONSUMER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID = CONSUMER_PREFIX + "." + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID;
    public static final String PROPERTY_CONSUMER_APICURIO_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS = CONSUMER_PREFIX + "." + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS;
    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_APICURIO_REGISTRY_URL,
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
            PROPERTY_PRODUCER_KEY_SERIALIZER_CLASS,
            PROPERTY_PRODUCER_VALUE_SERIALIZER_CLASS,
            PROPERTY_CONSUMER_KEY_DESERIALIZER_CLASS,
            PROPERTY_CONSUMER_VALUE_DESERIALIZER_CLASS
    ));
    // Default Key Serializer/Deserializer (often String when values are complex)
    public static final String DEFAULT_KEY_SERIALIZER_CLASS = "org.apache.kafka.common.serialization.UUIDSerializer";
    public static final String DEFAULT_KEY_DESERIALIZER_CLASS = "org.apache.kafka.common.serialization.UUIDDeserializer";
    // Apicurio Protobuf Serializer/Deserializer class names for Values
    public static final String APICURIO_PROTOBUF_VALUE_SERIALIZER_CLASS = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer";
    public static final String APICURIO_PROTOBUF_VALUE_DESERIALIZER_CLASS = "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer";
    public static final String DEFAULT_ARTIFACT_RESOLVER_STRATEGY = io.apicurio.registry.serde.strategy.TopicIdStrategy.class.getName();
    public static final String DEFAULT_EXPLICIT_ARTIFACT_GROUP_ID = "default";
    public static final String DEFAULT_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS = "com.krickert.search.model.PipeStream";
    // DEFAULT_IMAGE and APICURIO_PORT are now defined in ApicurioContainer,
    // but we can keep them here for the provider's getDefaultImageName() or if needed elsewhere.
    // Alternatively, access them via ApicurioContainer.DEFAULT_IMAGE_NAME.asCanonicalNameString()
    // and ApicurioContainer.APICURIO_HTTP_PORT if you prefer.
    public static final String DEFAULT_IMAGE = "apicurio/apicurio-registry:3.0.7"; // Or ApicurioContainer.DEFAULT_IMAGE_NAME.asCanonicalNameString()
    public static final int APICURIO_PORT = 8080; // Or ApicurioContainer.APICURIO_HTTP_PORT
    public static final String SIMPLE_NAME = "apicurio-registry";
    public static final String DISPLAY_NAME = "Apicurio Registry";
    private static final Logger LOG = LoggerFactory.getLogger(ApicurioTestResourceProvider.class);

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
        // You can get this from ApicurioContainer if you prefer to centralize it
        // return ApicurioContainer.DEFAULT_IMAGE_NAME.asCanonicalNameString();
        return DEFAULT_IMAGE;
    }

    @Override
    protected ApicurioContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        LOG.info("Creating Apicurio container with image: {}", imageName);
        // Now instantiates ApicurioContainer directly.
        // The ApicurioContainer constructor already handles withExposedPorts, withEnv, and wait strategy.
        // The withStartupTimeout was part of the GenericContainer in your previous provider;
        // it's now part of the WaitStrategy within ApicurioContainer.
        return new ApicurioContainer(imageName);
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, ApicurioContainer container) { // Changed to ApicurioContainer
        LOG.info("Resolving property '{}' for Apicurio container: {}", propertyName, container.getContainerName());

        // Use the helper method from ApicurioContainer
        String registryUrl = container.getRegistryApiV3Url();

        LOG.info("ApicurioRegistry URL: {}", registryUrl);

        Optional<String> resolvedValue = Optional.empty();

        if (PROPERTY_APICURIO_REGISTRY_URL.equals(propertyName)) {
            resolvedValue = Optional.of(registryUrl);
        } else if (PROPERTY_PRODUCER_GENERIC_REGISTRY_URL.equals(propertyName) ||
                PROPERTY_CONSUMER_GENERIC_REGISTRY_URL.equals(propertyName) ||
                PROPERTY_PRODUCER_APICURIO_REGISTRY_URL.equals(propertyName) ||
                PROPERTY_CONSUMER_APICURIO_REGISTRY_URL.equals(propertyName)) {
            resolvedValue = Optional.of(registryUrl);
        } else if (PROPERTY_PRODUCER_GENERIC_AUTO_REGISTER_ARTIFACT.equals(propertyName) ||
                PROPERTY_PRODUCER_APICURIO_AUTO_REGISTER_ARTIFACT.equals(propertyName)) {
            resolvedValue = Optional.of("true");
        } else if (PROPERTY_PRODUCER_APICURIO_ARTIFACT_RESOLVER_STRATEGY.equals(propertyName) ||
                PROPERTY_CONSUMER_APICURIO_ARTIFACT_RESOLVER_STRATEGY.equals(propertyName)) {
            resolvedValue = Optional.of(DEFAULT_ARTIFACT_RESOLVER_STRATEGY);
        } else if (PROPERTY_PRODUCER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID.equals(propertyName) ||
                PROPERTY_CONSUMER_APICURIO_EXPLICIT_ARTIFACT_GROUP_ID.equals(propertyName)) {
            resolvedValue = Optional.of(DEFAULT_EXPLICIT_ARTIFACT_GROUP_ID);
        } else if (PROPERTY_CONSUMER_APICURIO_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS.equals(propertyName)) {
            resolvedValue = Optional.of(DEFAULT_DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS);
        } else if (PROPERTY_PRODUCER_KEY_SERIALIZER_CLASS.equals(propertyName)) {
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