package com.krickert.search.config.consul.util;

import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull; // Or io.micronaut.core.annotation.NonNull
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstract base class for integration tests requiring Consul interaction.
 * Handles Consul state cleanup between test methods.
 * Remember to annotate concrete subclasses with @MicronautTest.
 */
// Consider PER_METHOD lifecycle for better isolation, especially if tests modify context beans
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
// Add @MicronautTest to subclasses, or here if all subclasses use the same env/props
// @MicronautTest(environments = "test") // Example: Add appropriate annotations if needed globally
public abstract class AbstractConsulIntegrationTest implements TestPropertyProvider {

    private static final Logger LOG_ABSTRACT = LoggerFactory.getLogger(AbstractConsulIntegrationTest.class);

    // Inject the service responsible for Consul operations
    @Inject
    protected ConsulKvService consulKvService;

    // Define the base Consul KV path used by your application/tests
    // It's often good practice to have tests use a dedicated root path.
    // We can use consulKvService.getFullPath() if needed, but a fixed path might be safer in @BeforeEach.
    protected final String consulConfigBasePath = "config/pipeline"; // Or fetch dynamically

    @BeforeEach
    void setUpConsulState() {
        LOG_ABSTRACT.info("Executing @BeforeEach: Cleaning Consul state under prefix '{}'...", consulConfigBasePath);
        // Clean up Consul KV store before each test method
        boolean cleaned = consulKvService.resetConsulState(consulConfigBasePath).block();
        assertTrue(cleaned, "Consul state should be reset successfully before test.");
        LOG_ABSTRACT.info("Consul state cleaned.");

        // Add any other common setup logic needed before each test
    }

    @AfterEach
    void tearDownConsulState() {
        LOG_ABSTRACT.info("Executing @AfterEach: Cleaning Consul state under prefix '{}'...", consulConfigBasePath);
        // Clean up Consul KV store after each test method
        boolean cleaned = consulKvService.resetConsulState(consulConfigBasePath).block();
        // Log a warning if cleanup fails, but don't fail the test itself during teardown
        if (!cleaned) {
            LOG_ABSTRACT.warn("Consul state cleanup might have failed after test.");
        } else {
            LOG_ABSTRACT.info("Consul state cleaned.");
        }
        // Add any other common teardown logic
    }

    /**
     * Provides common properties, like disabling seeding which might interfere
     * with controlled test setup. Subclasses can override and add more.
     *
     * @return A map of properties for the test context.
     */
    @Override
    @NotNull
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        // Disable data seeding by default in integration tests to control state precisely
        properties.put("consul.data.seeding.enabled", "false");
        // Ensure Consul client is enabled for these tests
        properties.put("consul.client.enabled", "true");
        properties.put("consul.client.config.enabled", "true"); // Needed for KV service path
        // You might disable registration/discovery/watch if not needed by the specific test class group
        properties.put("consul.client.registration.enabled", "false");
        properties.put("consul.client.discovery.enabled", "false");
        properties.put("consul.client.watch.enabled", "false");
        // Disable Micronaut's config client if you want to rely *only* on KV seeding/manipulation
        properties.put("micronaut.config-client.enabled", "false");
        properties.put("grpc.server.enabled", "false"); // Disable gRPC if not needed


        // Example: Add properties from TestContainerManager if needed and not already handled by @MicronautTest's TestResources integration
        // Map<String, String> containerProps = TestContainerManager.getInstance().getProperties();
        // properties.putAll(containerProps);

        return properties;
    }

    // --- Helper Methods (Optional) ---
    // You could add helper methods here for common test actions, e.g.,
    // protected void seedConsulKey(String key, String value) { ... }
    // protected Optional<String> readConsulKey(String key) { ... }
}
