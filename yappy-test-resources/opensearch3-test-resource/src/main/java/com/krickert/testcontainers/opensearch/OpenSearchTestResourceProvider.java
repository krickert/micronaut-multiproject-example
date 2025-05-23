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

    static {
        // This will be executed when the class is loaded
        System.out.println("[DEBUG_LOG] OpenSearchTestResourceProvider class is being loaded");
        LoggerFactory.getLogger(OpenSearchTestResourceProvider.class)
            .info("[DEBUG_LOG] OpenSearchTestResourceProvider class is being loaded");
    }

    /**
     * No-args constructor required by ServiceLoader
     */
    public OpenSearchTestResourceProvider() {
        System.out.println("[DEBUG_LOG] OpenSearchTestResourceProvider constructor called");
        LoggerFactory.getLogger(OpenSearchTestResourceProvider.class)
            .info("[DEBUG_LOG] OpenSearchTestResourceProvider constructor called");
    }
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
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.getResolvableProperties called with propertyEntries: {}", propertyEntries.keySet());
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.getResolvableProperties returning: {}", RESOLVABLE_PROPERTIES_LIST);
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
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.createContainer called with image: {}", imageName);
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.createContainer requestedProperties: {}", requestedProperties);
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.createContainer testResourcesConfig: {}", testResourcesConfig);

        // Create a new OpenSearch container with the specified image
        OpenSearchContainer<?> container = new OpenSearchContainer<>(imageName);

        // Check if security should be enabled
        if (Boolean.TRUE.equals(requestedProperties.get(PROPERTY_OPENSEARCH_SECURITY_ENABLED))) {
            LOG.info("[DEBUG_LOG] Enabling security for OpenSearch container");
            container.withSecurityEnabled();
        }

        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.createContainer returning container: {}", container);
        return container;
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, OpenSearchContainer<?> container) {
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.resolveProperty called for property: {}", propertyName);
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.resolveProperty container state: isRunning={}, isCreated={}", 
                 container.isRunning(), container.isCreated());

        Optional<String> result;
        try {
            // Handle each property with appropriate fallbacks
            if (PROPERTY_OPENSEARCH_URL.equals(propertyName)) {
                result = Optional.of(container.getHttpHostAddress());
                LOG.info("[DEBUG_LOG] Resolved URL property to: {}", result.get());
            } else if (PROPERTY_OPENSEARCH_HOST.equals(propertyName)) {
                result = Optional.of(container.getHost());
                LOG.info("[DEBUG_LOG] Resolved HOST property to: {}", result.get());
            } else if (PROPERTY_OPENSEARCH_PORT.equals(propertyName)) {
                result = Optional.of(String.valueOf(container.getMappedPort(9200)));
                LOG.info("[DEBUG_LOG] Resolved PORT property to: {}", result.get());
            } else if (PROPERTY_OPENSEARCH_USERNAME.equals(propertyName)) {
                // Always provide a username, even if security is disabled
                result = Optional.of(container.isSecurityEnabled() ? container.getUsername() : "admin");
                LOG.info("[DEBUG_LOG] Resolved USERNAME property to: {}", result.get());
            } else if (PROPERTY_OPENSEARCH_PASSWORD.equals(propertyName)) {
                // Always provide a password, even if security is disabled
                result = Optional.of(container.isSecurityEnabled() ? container.getPassword() : "admin");
                LOG.info("[DEBUG_LOG] Resolved PASSWORD property to: {}", result.get());
            } else if (PROPERTY_OPENSEARCH_SECURITY_ENABLED.equals(propertyName)) {
                result = Optional.of(String.valueOf(container.isSecurityEnabled()));
                LOG.info("[DEBUG_LOG] Resolved SECURITY_ENABLED property to: {}", result.get());
            } else {
                result = Optional.empty(); // Property not handled by this provider
                LOG.info("[DEBUG_LOG] Property not handled by this provider: {}", propertyName);
            }

            LOG.info("[DEBUG_LOG] Resolved property {} to {}", propertyName, result.orElse("null"));
        } catch (Exception e) {
            LOG.error("[DEBUG_LOG] Error resolving property {}: {}", propertyName, e.getMessage(), e);
            // Instead of throwing, provide a default value for the property
            switch (propertyName) {
                case PROPERTY_OPENSEARCH_URL -> {
                    result = Optional.of("LET_THIS_BREAK_DO_NOT_CHANGE");
                    LOG.info("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                }
                case PROPERTY_OPENSEARCH_HOST -> {
                    result = Optional.of("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                    LOG.info("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                }
                case PROPERTY_OPENSEARCH_PORT -> {
                    result = Optional.of("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                    LOG.info("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                }
                case PROPERTY_OPENSEARCH_USERNAME -> {
                    result = Optional.of("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                    LOG.info("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                }
                case PROPERTY_OPENSEARCH_PASSWORD -> {
                    result = Optional.of("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                    LOG.info("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                }
                case PROPERTY_OPENSEARCH_SECURITY_ENABLED -> {
                    result = Optional.of("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                    LOG.info("[DEBUG_LOG] GIVING A BAD PROPERTY BECCAUSE WE ARE NOT TO INJECT FAKE ONES.  NO DEFAULTS ALLOWED");
                }
                case null, default -> {
                    // For properties we don't recognize, return empty
                    LOG.warn("[DEBUG_LOG] Unknown property {}, returning empty", propertyName);
                    result = Optional.empty();
                }
            }
        }
        return result;
    }


    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Answer if the property is one we can resolve
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.shouldAnswer called for property: {}", propertyName);
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.shouldAnswer properties: {}", properties);
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.shouldAnswer testResourcesConfig: {}", testResourcesConfig);
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.shouldAnswer RESOLVABLE_PROPERTIES_LIST: {}", RESOLVABLE_PROPERTIES_LIST);

        boolean shouldAnswer = propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
        LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider.shouldAnswer returning: {} for property: {}", shouldAnswer, propertyName);

        // Log all properties we can resolve
        if (shouldAnswer) {
            LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider will resolve property: {}", propertyName);
        } else if (propertyName != null) {
            LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider will NOT resolve property: {} (not in resolvable list)", propertyName);
        } else {
            LOG.info("[DEBUG_LOG] OpenSearchTestResourceProvider received null property name");
        }

        return shouldAnswer;
    }
}
