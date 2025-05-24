package com.krickert.yappy.modules.opensearchsink.config;

import com.krickert.testcontainers.opensearch.OpenSearchTestResourceProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.env.Environment;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that properties resolved by OpenSearchTestResourceProvider
 * are correctly injected into the Micronaut application context when Testcontainers
 * for OpenSearch is active.
 */
@MicronautTest(startApplication = false) // We don't need the full app, just property resolution
class OpenSearchTestResourceProviderPropertyInjectionTest {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchTestResourceProviderPropertyInjectionTest.class);

    // Inject Micronaut's Environment to access all properties
    @Inject
    Environment environment;

    /**
     * Setup method to ensure OpenSearch container is started before tests run.
     * This method attempts to access OpenSearch properties which should trigger
     * the container startup if it hasn't already started.
     */
    @BeforeEach
    void setupOpenSearch() {
        LOG.info("[DEBUG_LOG] Setting up OpenSearch test environment");

        // Try to get OpenSearch properties from the environment
        // This should trigger the container startup if it hasn't already started
        Optional<String> openSearchHostOpt = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST, String.class);
        LOG.info("[DEBUG_LOG] OpenSearch host from environment: {}", openSearchHostOpt.orElse("not found"));

        Optional<String> openSearchPortOpt = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT, String.class);
        LOG.info("[DEBUG_LOG] OpenSearch port from environment: {}", openSearchPortOpt.orElse("not found"));

        Optional<String> openSearchUrlOpt = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL, String.class);
        LOG.info("[DEBUG_LOG] OpenSearch URL from environment: {}", openSearchUrlOpt.orElse("not found"));

        // Log all available properties for debugging
        LOG.info("[DEBUG_LOG] All available properties in environment:");
        environment.getPropertySources().forEach(source -> {
            LOG.info("[DEBUG_LOG] Property source: {}", source.getName());
        });
    }

    // Directly inject properties from the OpenSearchTestResourceProvider with default values
    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST)
    String openSearchHost;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT)
    String openSearchPort;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL)
    String openSearchUrl;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME)
    String openSearchUsername;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD)
    String openSearchPassword;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED)
    String openSearchSecurityEnabled;

    @Bean
    OpenSearchSinkConfig openSearchSinkConfig() {
        // The tests in this class assert that openSearchHost, openSearchPort,
        // and openSearchSecurityEnabled are non-null and that openSearchPort is a valid integer.
        // openSearchUsername and openSearchPassword can be null if security is not enabled.
        OpenSearchSinkConfig config = OpenSearchSinkConfig.builder()
                .hosts(openSearchHost)
                .port(Integer.parseInt(openSearchPort)) // Converts String to Integer
                .username(openSearchUsername)
                .password(openSearchPassword)
                .useSsl(Boolean.parseBoolean(openSearchSecurityEnabled)) // Converts String to Boolean
                // Other fields of OpenSearchSinkConfig (e.g., indexName, bulkSize)
                // are not set from the properties injected in this specific test class.
                // They will default to null as their types are objects (String, Integer, Boolean).
                .build();
        return config;
    }


    @Test
    @DisplayName("Should inject OpenSearch host resolved by TestResources")
    void testOpenSearchHostInjected() {
        assertNotNull(openSearchHost, "OpenSearch host should be injected");
        assertFalse(openSearchHost.isBlank(), "OpenSearch host should not be blank");
        LOG.info("Injected opensearch.host: {}", openSearchHost);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST),
                "Environment should contain opensearch.host property");
        Optional<String> hostProperty = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST, String.class);
        assertTrue(hostProperty.isPresent(), "opensearch.host property should be present");
        assertEquals(openSearchHost, hostProperty.get(), "Injected host should match environment property");
    }

    @Test
    @DisplayName("Should inject OpenSearch port resolved by TestResources")
    void testOpenSearchPortInjected() {
        assertNotNull(openSearchPort, "OpenSearch port should be injected");
        assertFalse(openSearchPort.isBlank(), "OpenSearch port should not be blank");
        try {
            Integer.parseInt(openSearchPort); // Check if it's a number
        } catch (NumberFormatException e) {
            fail("OpenSearch port is not a valid number: " + openSearchPort);
        }
        LOG.info("Injected opensearch.port: {}", openSearchPort);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT),
                "Environment should contain opensearch.port property");
        Optional<String> portProperty = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT, String.class);
        assertTrue(portProperty.isPresent(), "opensearch.port property should be present");
        assertEquals(openSearchPort, portProperty.get(), "Injected port should match environment property");
    }

    @Test
    @DisplayName("Should inject OpenSearch URL resolved by TestResources")
    void testOpenSearchUrlInjected() {
        assertNotNull(openSearchUrl, "OpenSearch URL should be injected");
        assertFalse(openSearchUrl.isBlank(), "OpenSearch URL should not be blank");
        LOG.info("Injected opensearch.url: {}", openSearchUrl);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL),
                "Environment should contain opensearch.url property");
        Optional<String> urlProperty = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL, String.class);
        assertTrue(urlProperty.isPresent(), "opensearch.url property should be present");
        assertEquals(openSearchUrl, urlProperty.get(), "Injected URL should match environment property");
    }

    @Test
    @DisplayName("Should inject OpenSearch username resolved by TestResources")
    void testOpenSearchUsernameInjected() {
        // Username might be null if security is disabled
        LOG.info("Injected opensearch.username: {}", openSearchUsername);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME),
                "Environment should contain opensearch.username property");
    }

    @Test
    @DisplayName("Should inject OpenSearch password resolved by TestResources")
    void testOpenSearchPasswordInjected() {
        // Password might be null if security is disabled
        LOG.info("Injected opensearch.password: {}", openSearchPassword);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD),
                "Environment should contain opensearch.password property");
    }

    @Test
    @DisplayName("Should inject OpenSearch security enabled flag resolved by TestResources")
    void testOpenSearchSecurityEnabledInjected() {
        assertNotNull(openSearchSecurityEnabled, "OpenSearch security enabled flag should be injected");
        LOG.info("Injected opensearch.security.enabled: {}", openSearchSecurityEnabled);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED),
                "Environment should contain opensearch.security.enabled property");
        Optional<String> securityEnabledProperty = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED, String.class);
        assertTrue(securityEnabledProperty.isPresent(), "opensearch.security.enabled property should be present");
        assertEquals(openSearchSecurityEnabled, securityEnabledProperty.get(), "Injected security enabled should match environment property");
    }

    @Test
    @DisplayName("Verify OpenSearch URL can be constructed from host and port")
    void testOpenSearchUrlConstruction() {
        assertNotNull(openSearchHost, "Host should not be null");
        assertNotNull(openSearchPort, "Port should not be null");

        boolean securityEnabled = Boolean.parseBoolean(openSearchSecurityEnabled);
        String protocol = securityEnabled ? "https" : "http";
        String constructedUrl = protocol + "://" + openSearchHost + ":" + openSearchPort;

        LOG.info("Constructed OpenSearch URL: {}", constructedUrl);
        LOG.info("Injected OpenSearch URL: {}", openSearchUrl);

        // If the URL is directly provided by the test container, verify it has the same protocol and host
        // We don't check the port because it might be dynamically assigned by the test container
        if (openSearchUrl != null && !openSearchUrl.isBlank()) {
            String expectedProtocol = securityEnabled ? "https://" : "http://";
            assertTrue(openSearchUrl.startsWith(expectedProtocol + openSearchHost + ":"), 
                    "Injected URL should start with the expected protocol and host");
        }
    }

    @Test
    @DisplayName("Verify all resolvable OpenSearch properties are present in Micronaut Environment")
    void testAllResolvablePropertiesInEnvironment() {
        for (String propertyName : OpenSearchTestResourceProvider.RESOLVABLE_PROPERTIES_LIST) {
            assertTrue(environment.containsProperty(propertyName),
                    "Micronaut environment should contain property: " + propertyName);
            Optional<String> propertyValue = environment.getProperty(propertyName, String.class);
            assertTrue(propertyValue.isPresent(), "Property value should be present for: " + propertyName);
            assertFalse(propertyValue.get().isBlank(), "Property value should not be blank for: " + propertyName);
            LOG.info("Environment property {} = {}", propertyName, propertyValue.get());
        }
    }
}
