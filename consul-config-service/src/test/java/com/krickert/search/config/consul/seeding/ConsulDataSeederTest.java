package com.krickert.search.config.consul.seeding;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import com.krickert.search.config.consul.model.ApplicationConfig;
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.service.ConsulKvService;
// Import the actual seeder class
import com.krickert.search.config.consul.seeding.ConsulDataSeeder;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ConsulDataSeeder focusing on initial seeding and idempotency.
 * Uses Testcontainers for a real Consul instance.
 */
@MicronautTest(rebuildContext = true) // Rebuild context for each test method for better isolation
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Manage Testcontainer lifecycle PER_CLASS
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // Run tests in order
public class ConsulDataSeederTest implements TestPropertyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulDataSeederTest.class);
    private static final String TEST_SEED_FILE = "classpath:test-seed-data.yaml"; // Seed file in src/test/resources
    private static final String DEFAULT_SEEDING_ENABLED_PROPERTY = "myapp.consul.seeder.enabled";
    private static final String DEFAULT_SEEDING_FILE_PROPERTY = "myapp.consul.seeder.file";

    // Use the shared container instance pattern
    private static final ConsulTestContainer consulContainer = ConsulTestContainer.getInstance();


    @Inject
    private ApplicationContext applicationContext; // Inject context to get beans dynamically if needed

    @Inject
    private ConsulKvService consulKvService;

    // Inject beans to check their state after seeding/refresh
    @Inject
    private ApplicationConfig applicationConfig;

    @Inject
    private PipelineConfig pipelineConfig;

    @Inject
    private ApplicationEventPublisher<RefreshEvent> eventPublisher;

    private String enabledFlagKey;
    // --- MODIFIED ---
    private String testPipelineKey; // Key that ACTUALLY exists in test-seed-data.yaml
    private String testPipelineInitialValue; // Expected value for that key
    private final String testPipelineModifiedValue = "modified-by-test";


    @BeforeAll
    void setupKeys() {
        ConsulKvService kvService = applicationContext.getBean(ConsulKvService.class);
        enabledFlagKey = kvService.getFullPath("pipeline.enabled");

        // --- MODIFIED --- Check the AUTO-ADDED version key
        testPipelineKey = kvService.getFullPath("pipeline.configs.test-pipeline.version");
        testPipelineInitialValue = "1"; // Expect version "1" to be auto-added

        LOG.info("Using enabledFlagKey: {}", enabledFlagKey);
        LOG.info("Using testPipelineKey: {}", testPipelineKey);
        LOG.info("Expecting initial value (auto-added): {}", testPipelineInitialValue);
    }
    /**
     * Provides properties to connect to the test Consul instance and
     * configures the ConsulDataSeeder for the test.
     */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = new HashMap<>(consulContainer.getProperties());
        // Ensure seeder runs and uses the test seed file
        props.put(DEFAULT_SEEDING_ENABLED_PROPERTY, "true");
        props.put(DEFAULT_SEEDING_FILE_PROPERTY, TEST_SEED_FILE);
        // Optional: Lower Consul client timeouts for faster test failures if needed
        // props.put("consul.client.read-timeout", "5s");
        LOG.info("Providing test properties: {}", props);
        return props;
    }
    @Test
    @Order(1)
    @DisplayName("Should perform initial seeding correctly")
    void testInitialSeeding() {
        LOG.info("Running test: testInitialSeeding");
        // 1. Wait for enabled flag (remains the same)
        LOG.info("Waiting for enabled flag '{}' to be 'true'...", enabledFlagKey);
        Awaitility.await("Consul seeding completion")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> StepVerifier.create(consulKvService.getValue(enabledFlagKey))
                        .expectNextMatches(opt -> opt.isPresent() && "true".equals(opt.get()))
                        .as("Check if " + enabledFlagKey + " is true")
                        .verifyComplete()
                );
        LOG.info("Enabled flag found and is true.");

        // 2. Verify the *correct* specific key from the test seed file
        LOG.info("Verifying initial value of test key '{}'...", testPipelineKey); // Uses updated key
        StepVerifier.create(consulKvService.getValue(testPipelineKey))
                // Predicate now checks for the correct initial value
                .expectNextMatches(opt -> opt.isPresent() && testPipelineInitialValue.equals(opt.get()))
                .as("Check initial value of " + testPipelineKey)
                .verifyComplete();
        LOG.info("Initial value verified."); // Should pass now

        // 3. Trigger configuration refresh (remains the same)
        LOG.info("Triggering configuration refresh...");
        eventPublisher.publishEvent(new RefreshEvent());

        // 4. Wait for config beans to update - ADJUST checks
        LOG.info("Waiting for configuration beans to update...");
        Awaitility.await("Config bean update")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                // --- MODIFIED --- Check for the correct pipeline name and relevant loaded data
                // Inside Awaitility until() lambda
                .until(() -> {
                    if (!applicationConfig.isEnabled()) return false;
                    PipelineConfigDto testPipelineDto = pipelineConfig.getPipelines().get("test-pipeline");
                    if (testPipelineDto == null) return false;
                    // --- MODIFIED --- Check the auto-added version
                    return testPipelineDto.getPipelineVersion() == 1L;
                });
        // ...
        // Final Assertions
        assertTrue(applicationConfig.isEnabled(), "ApplicationConfig should be enabled after seeding/refresh");
        assertTrue(pipelineConfig.getPipelines().containsKey("test-pipeline"), "PipelineConfig should contain 'test-pipeline'");
        // --- MODIFIED --- Assert the auto-added version
        assertEquals(1L, pipelineConfig.getPipelines().get("test-pipeline").getPipelineVersion(), "'test-pipeline' version should be 1");
        // Add more assertions here based on how ConfigurationService loads data into PipelineConfigDto
        // Example (assuming serviceImplementation is loaded - adjust based on actual DTO structure):
        // assertNotNull(pipelineConfig.getPipelines().get("test-pipeline").getServices().get("test-service"), "'test-service' should exist");
        // assertEquals(testPipelineInitialValue, pipelineConfig.getPipelines().get("test-pipeline").getServices().get("test-service").getServiceImplementation());

        LOG.info("testInitialSeeding completed successfully.");
    }

    @Test
    @Order(2)
    @DisplayName("Seeding logic should be idempotent (not overwrite existing keys)")
    void testSeedingIsIdempotent() {
        // --- Define key from YAML for modification ---
        String keyToModify = consulKvService.getFullPath("pipeline.configs.test-pipeline.service.test-service.serviceImplementation");
        String initialValueFromYaml = "com.krickert.search.pipeline.service.TestService"; // Value from YAML
        String modifiedValue = "modified-by-idempotency-test";
        // ---

        LOG.info("Running test: testSeedingIsIdempotent");
        // Pre-condition check (remains the same)
        // ...

        // 1. Verify the initial value from YAML exists first
        LOG.info("Verifying initial value of key to modify '{}'...", keyToModify);
        StepVerifier.create(consulKvService.getValue(keyToModify))
                .expectNextMatches(opt -> opt.isPresent() && initialValueFromYaml.equals(opt.get()))
                .as("Check initial value of " + keyToModify)
                .verifyComplete();

        // 2. Modify the chosen value directly in Consul
        LOG.info("Modifying key '{}' to value '{}'", keyToModify, modifiedValue);
        StepVerifier.create(consulKvService.putValue(keyToModify, modifiedValue))
                .expectNext(true)
                .as("Modify value for " + keyToModify)
                .verifyComplete();

        // Verify modification
        StepVerifier.create(consulKvService.getValue(keyToModify))
                .expectNextMatches(opt -> opt.isPresent() && modifiedValue.equals(opt.get()))
                .as("Verify modification of " + keyToModify)
                .verifyComplete();
        LOG.info("Test key modified and verified.");

        // 3. Explicitly trigger seeding logic again (remains the same)
        // ... (call seeder.seedData() and verify) ...

        // 4. Verify the modified value was NOT overwritten
        LOG.info("Verifying key '{}' was not overwritten...", keyToModify);
        StepVerifier.create(consulKvService.getValue(keyToModify))
                .expectNextMatches(opt -> opt.isPresent() && modifiedValue.equals(opt.get()))
                .as("Check " + keyToModify + " retains modified value")
                .verifyComplete();
        LOG.info("Modified value retained. Idempotency verified.");

        // 5. Also verify the auto-added version key STILL exists (wasn't deleted/overwritten)
        String versionKey = consulKvService.getFullPath("pipeline.configs.test-pipeline.version");
        LOG.info("Verifying auto-added version key '{}' still exists...", versionKey);
        StepVerifier.create(consulKvService.getValue(versionKey))
                .expectNextMatches(opt -> opt.isPresent() && "1".equals(opt.get()))
                .as("Check auto-added key " + versionKey + " still exists")
                .verifyComplete();

        LOG.info("testSeedingIsIdempotent completed successfully.");
    }
}