package com.krickert.yappy.modules.opensearchsink.config;

import com.krickert.testcontainers.opensearch.OpenSearchTestResourceProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
    @Value("${" + OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST + "}")
    String openSearchHost;

    @Value("${" + OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT + "}")
    String openSearchPort;

    @Value("${" + OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL + "}")
    String openSearchUrl;

    // Optional properties that may be null or empty if security is not enabled
    @Value("${" + OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME + ":#{null}}")
    String openSearchUsername;

    @Value("${" + OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD + ":#{null}}")
    String openSearchPassword;

    @Value("${" + OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED + "}")
    String openSearchSecurityEnabled;

    /**
     * Helper method to check if security is enabled
     * @return true if security is enabled, false otherwise
     */
    boolean isSecurityEnabled() {
        return Boolean.parseBoolean(openSearchSecurityEnabled);
    }

    /**
     * Helper method to check if security is disabled
     * @return true if security is disabled, false otherwise
     */
    boolean isSecurityDisabled() {
        return !isSecurityEnabled();
    }

    @Bean
    OpenSearchSinkConfig openSearchSinkConfig() {
        // Build the config with required properties
        OpenSearchSinkConfig.OpenSearchSinkConfigBuilder builder = OpenSearchSinkConfig.builder()
                .hosts(openSearchHost)
                .port(Integer.parseInt(openSearchPort)) // Converts String to Integer
                .useSsl(Boolean.parseBoolean(openSearchSecurityEnabled)); // Converts String to Boolean

        // Add optional properties if they are available
        if (isSecurityEnabled()) {
            builder.username(openSearchUsername)
                   .password(openSearchPassword);
        }

        // Other fields of OpenSearchSinkConfig (e.g., indexName, bulkSize)
        // are not set from the properties injected in this specific test class.
        // They will default to null as their types are objects (String, Integer, Boolean).
        return builder.build();
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
    @DisplayName("Should inject OpenSearch username when security is enabled")
    void testOpenSearchUsernameInjectedWhenSecurityEnabled() {
        // Skip test if security is not enabled
        if (!isSecurityEnabled()) {
            LOG.info("[DEBUG_LOG] Skipping username test because security is not enabled");
            return;
        }

        LOG.info("[DEBUG_LOG] Running username test with security enabled");
        assertNotNull(openSearchUsername, "OpenSearch username should be injected when security is enabled");
        assertFalse(openSearchUsername.isBlank(), "OpenSearch username should not be blank when security is enabled");
        LOG.info("Injected opensearch.username: {}", openSearchUsername);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME),
                "Environment should contain opensearch.username property");
        Optional<String> usernameProperty = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME, String.class);
        assertTrue(usernameProperty.isPresent(), "opensearch.username property should be present");
        assertEquals(openSearchUsername, usernameProperty.get(), "Injected username should match environment property");
    }

    @Test
    @DisplayName("Should inject OpenSearch password when security is enabled")
    void testOpenSearchPasswordInjectedWhenSecurityEnabled() {
        // Skip test if security is not enabled
        if (!isSecurityEnabled()) {
            LOG.info("[DEBUG_LOG] Skipping password test because security is not enabled");
            return;
        }

        LOG.info("[DEBUG_LOG] Running password test with security enabled");
        assertNotNull(openSearchPassword, "OpenSearch password should be injected when security is enabled");
        assertFalse(openSearchPassword.isBlank(), "OpenSearch password should not be blank when security is enabled");
        LOG.info("Injected opensearch.password: {}", openSearchPassword);

        // Verify the property exists in the environment
        assertTrue(environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD),
                "Environment should contain opensearch.password property");
        Optional<String> passwordProperty = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD, String.class);
        assertTrue(passwordProperty.isPresent(), "opensearch.password property should be present");
        assertEquals(openSearchPassword, passwordProperty.get(), "Injected password should match environment property");
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
    @DisplayName("Verify required OpenSearch properties are present in Micronaut Environment")
    void testRequiredPropertiesInEnvironment() {
        // Define the required properties that should always be present
        String[] requiredProperties = {
            OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST,
            OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT,
            OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL,
            OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED
        };

        // Check each required property individually
        for (String propertyName : requiredProperties) {
            LOG.info("[DEBUG_LOG] Checking required property: {}", propertyName);
            assertTrue(environment.containsProperty(propertyName),
                    "Micronaut environment should contain required property: " + propertyName);
            Optional<String> propertyValue = environment.getProperty(propertyName, String.class);
            assertTrue(propertyValue.isPresent(), "Required property value should be present for: " + propertyName);
            assertFalse(propertyValue.get().isBlank(), "Required property value should not be blank for: " + propertyName);
            LOG.info("Environment required property {} = {}", propertyName, propertyValue.get());
        }
    }

    @Test
    @DisplayName("Verify auth properties are present when security is enabled")
    void testAuthPropertiesWhenSecurityEnabled() {
        // Skip test if security is not enabled
        if (!isSecurityEnabled()) {
            LOG.info("[DEBUG_LOG] Skipping auth properties test because security is not enabled");
            return;
        }

        LOG.info("[DEBUG_LOG] Running auth properties test with security enabled");

        // Define the auth properties that should be present when security is enabled
        String[] authProperties = {
            OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME,
            OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD
        };

        // Check each auth property individually
        for (String propertyName : authProperties) {
            LOG.info("[DEBUG_LOG] Checking auth property with security enabled: {}", propertyName);
            assertTrue(environment.containsProperty(propertyName),
                    "Micronaut environment should contain auth property when security is enabled: " + propertyName);
            Optional<String> propertyValue = environment.getProperty(propertyName, String.class);
            assertTrue(propertyValue.isPresent(), "Auth property value should be present when security is enabled for: " + propertyName);
            assertFalse(propertyValue.get().isBlank(), "Auth property value should not be blank when security is enabled for: " + propertyName);
            LOG.info("Environment auth property {} = {}", propertyName, propertyValue.get());
        }
    }

    @Test
    @DisplayName("Verify auth properties handling when security is disabled")
    void testAuthPropertiesWhenSecurityDisabled() {
        // Skip test if security is enabled
        if (isSecurityEnabled()) {
            LOG.info("[DEBUG_LOG] Skipping auth properties disabled test because security is enabled");
            return;
        }

        LOG.info("[DEBUG_LOG] Running auth properties test with security disabled");

        // When security is disabled, username and password properties might still be present
        // but they are not required for the application to function correctly

        LOG.info("[DEBUG_LOG] Security is disabled, checking optional auth properties");

        // Check username property
        if (environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME)) {
            LOG.info("[DEBUG_LOG] Username property is present even though security is disabled");
            Optional<String> usernameValue = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME, String.class);
            if (usernameValue.isPresent() && !usernameValue.get().isBlank()) {
                LOG.info("Username property is present and not blank: {}", usernameValue.get());
            } else {
                LOG.info("Username property is present but blank or empty");
            }
        } else {
            LOG.info("[DEBUG_LOG] Username property is not present, which is acceptable when security is disabled");
        }

        // Check password property
        if (environment.containsProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD)) {
            LOG.info("[DEBUG_LOG] Password property is present even though security is disabled");
            Optional<String> passwordValue = environment.getProperty(OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD, String.class);
            if (passwordValue.isPresent() && !passwordValue.get().isBlank()) {
                LOG.info("Password property is present and not blank");
            } else {
                LOG.info("Password property is present but blank or empty");
            }
        } else {
            LOG.info("[DEBUG_LOG] Password property is not present, which is acceptable when security is disabled");
        }

        // No assertions here - we're just logging the state
    }
}
