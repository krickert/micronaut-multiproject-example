package com.krickert.testcontainers.opensearch;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn an OpenSearch test container.
 * It provides properties for OpenSearch connection and client configuration.
 */
public class OpenSearchTestResourceProvider extends AbstractTestContainersProvider<OpenSearchContainer<?>> {
    // OpenSearch Properties
    public static final String OPENSEARCH_PREFIX = "opensearch";
    public static final String PROPERTY_OPENSEARCH_HOST = OPENSEARCH_PREFIX + ".host";
    public static final String PROPERTY_OPENSEARCH_PORT = OPENSEARCH_PREFIX + ".port";
    public static final String PROPERTY_OPENSEARCH_URL = OPENSEARCH_PREFIX + ".url";
    public static final String PROPERTY_OPENSEARCH_USERNAME = OPENSEARCH_PREFIX + ".username";
    public static final String PROPERTY_OPENSEARCH_PASSWORD = OPENSEARCH_PREFIX + ".password";
    public static final String PROPERTY_OPENSEARCH_SECURITY_ENABLED = OPENSEARCH_PREFIX + ".security.enabled";

    // Combined list of properties this provider can resolve
    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_OPENSEARCH_HOST,
            PROPERTY_OPENSEARCH_PORT,
            PROPERTY_OPENSEARCH_URL,
            PROPERTY_OPENSEARCH_USERNAME,
            PROPERTY_OPENSEARCH_PASSWORD,
            PROPERTY_OPENSEARCH_SECURITY_ENABLED
    ));

    public static final String DEFAULT_IMAGE = "opensearchproject/opensearch:3.0.0";
    public static final String SIMPLE_NAME = "opensearch3";
    public static final String DISPLAY_NAME = "OpenSearch";
    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchTestResourceProvider.class);

    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        LOG.info("Resolving properties: {}", propertyEntries.keySet());
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
    protected OpenSearchContainer<?> createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        LOG.info("Creating OpenSearch container with image: {}", imageName);
        // Create a new OpenSearch container with the specified image
        OpenSearchContainer<?> container = new OpenSearchContainer<>(imageName);

        // Check if security should be enabled
        if (Boolean.TRUE.equals(requestedProperties.get(PROPERTY_OPENSEARCH_SECURITY_ENABLED))) {
            LOG.info("Enabling security for OpenSearch container");
            container.withSecurityEnabled();
        }

        return container;
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, OpenSearchContainer<?> container) {
        LOG.info("Resolving property: {}", propertyName);
        Optional<String> result = switch (propertyName) {
            case PROPERTY_OPENSEARCH_URL -> Optional.of(container.getHttpHostAddress());
            case PROPERTY_OPENSEARCH_HOST -> Optional.of(container.getHost());
            case PROPERTY_OPENSEARCH_PORT -> Optional.of(String.valueOf(container.getMappedPort(9200)));
            case PROPERTY_OPENSEARCH_USERNAME -> Optional.of(container.getUsername());
            case PROPERTY_OPENSEARCH_PASSWORD -> Optional.of(container.getPassword());
            case PROPERTY_OPENSEARCH_SECURITY_ENABLED -> Optional.of(String.valueOf(container.isSecurityEnabled()));
            default -> Optional.empty(); // Property not handled by this provider
        };
        LOG.info("Resolved property {} to {}", propertyName, result.orElse("null"));
        return result;
    }


    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Answer if the property is one we can resolve
        boolean shouldAnswer = propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
        LOG.info("Checking if provider should answer property {}: {}", propertyName, shouldAnswer);
        return shouldAnswer;
    }
}
