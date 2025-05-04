package com.krickert.search.config.consul.seeding;

import com.krickert.search.config.consul.model.ApplicationConfig;
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.model.kv.Value;
import org.kiwiproject.consul.option.ConsistencyMode;
import org.kiwiproject.consul.option.ImmutableQueryOptions;
import org.kiwiproject.consul.option.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@MicronautTest // REMOVE rebuildContext = true
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConsulDataSeederTest implements TestPropertyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulDataSeederTest.class);
    private static final String TEST_SEED_FILE = "classpath:dev-seed-data.yaml";

    @Inject private ConsulDataSeeder consulDataSeeder;
    @Inject private ConsulKvService consulKvService;
    @Inject private ApplicationConfig applicationConfig;
    @Inject private PipelineConfig pipelineConfig;
    @Inject private ApplicationEventPublisher<RefreshEvent> eventPublisher;
    @Inject private KeyValueClient keyValueClient; // For cleanup

    private String seededFlagKey;
    private String testPipelineVersionKey;
    private String testPipelineVersionValue; // Expect "0" from seeder
    private String testPipelineLastUpdatedKey;
    private String testPipelineServiceImplKey;
    private String testPipelineServiceImplValue;
    private String pipelineConfigsPrefix;

    @BeforeAll
    void setupStaticKeys() {
        // Getting the bean here might be too early if context isn't fully ready.
        // It's safer to construct paths manually or get the service in BeforeEach.
        // Let's assume default path for now for simplicity in BeforeAll.
        String basePath = "config/pipeline"; // Assuming default path for tests
        seededFlagKey = basePath + "/" + ConsulDataSeeder.SEEDED_FLAG_NAME;
        testPipelineVersionKey = basePath + "/pipeline.configs.test-pipeline.version";
        testPipelineVersionValue = "0"; // Expect seeder to add "0"
        testPipelineLastUpdatedKey = basePath + "/pipeline.configs.test-pipeline.lastUpdated";
        testPipelineServiceImplKey = basePath + "/pipeline.configs.test-pipeline.service.test-service.serviceImplementation";
        testPipelineServiceImplValue = "com.krickert.search.pipeline.service.TestService";
        pipelineConfigsPrefix = basePath + "/pipeline.configs";

        LOG.info("Using seededFlagKey: {}", seededFlagKey);
        LOG.info("Using testPipelineVersionKey: {}", testPipelineVersionKey);
        LOG.info("Using testPipelineLastUpdatedKey: {}", testPipelineLastUpdatedKey);
        LOG.info("Using testPipelineServiceImplKey: {}", testPipelineServiceImplKey);
        LOG.info("Using pipelineConfigsPrefix for cleanup: {}", pipelineConfigsPrefix);
    }

    // --- Option B: Keep @BeforeEach for full cleanup ---
    @BeforeEach
    void cleanupConsulState() {
        LOG.info("Cleaning Consul state before test method using path prefix '{}'...", pipelineConfigsPrefix);
        try {
            keyValueClient.deleteKey(seededFlagKey);
            LOG.debug("Deleted key: {}", seededFlagKey);
            keyValueClient.deleteKeys(pipelineConfigsPrefix);
            LOG.debug("Deleted keys with prefix: {}", pipelineConfigsPrefix);
        } catch(Exception e) {
            LOG.error("Error cleaning Consul state: {}", e.getMessage());
            fail("Failed to clean Consul state before test", e);
        }
        pipelineConfig.reset();
        applicationConfig.reset();
        LOG.info("Consul state cleaned and beans reset.");
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = new HashMap<>();
        // Use constants from ConsulDataSeeder for consistency
        props.put(ConsulDataSeeder.ENABLED_PROPERTY, "true"); // "consul.data.seeding.enabled"
        props.put(ConsulDataSeeder.FILE_PROPERTY, TEST_SEED_FILE); // "consul.data.seeding.file"
        // Ensure test uses the same base path as BeforeAll assumes
        props.put("consul.client.config.path", "config/pipeline");
        LOG.info("Providing test properties for ConsulDataSeederTest: {}", props);
        return props;
    }

    // --- Test 1: Verify Seeding Happens (Manually Triggered) ---
    @Test
    @Order(1)
    @DisplayName("Should perform initial seeding correctly (when manually triggered)")
    void testManualInitialSeeding() {
        LOG.info("Running test: testManualInitialSeeding");
        // @BeforeEach cleaned state. Seeder listener on StartupEvent might have run
        // but state is clean now.

        // 1. Manually trigger seeding logic
        LOG.info("Manually triggering seedData()...");
        StepVerifier.create(consulDataSeeder.seedData())
                .expectNext(true) // Expect seeding to run and return true
                .verifyComplete();
        LOG.info("Manual seedData() completed.");

        // 2. Wait briefly for consistency
        try {
            LOG.info("Waiting briefly (500ms) after manual seeding...");
            Thread.sleep(500);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); fail("Interrupted"); }

        // 3. Perform consistent read check for the flag
        LOG.info("Performing single consistent read check for flag '{}'...", seededFlagKey);
        QueryOptions consistentQueryOptions = ImmutableQueryOptions.builder()
                .consistencyMode(ConsistencyMode.CONSISTENT).build();
        Optional<Value> readRaw = keyValueClient.getValue(seededFlagKey, consistentQueryOptions);
        Optional<String> flagOpt = readRaw.flatMap(Value::getValueAsString);
        assertTrue(flagOpt.isPresent() && "true".equals(flagOpt.get()), "Flag check after manual seed failed. Got: " + flagOpt.orElse("empty"));
        LOG.info("Flag check after manual seed passed.");

        // 4. Verify other seeded values (version, lastUpdated etc.) using consistent reads if needed
        StepVerifier.create(consulKvService.getValue(testPipelineVersionKey)) // Or use direct client consistent read
                .expectNextMatches(opt -> opt.isPresent() && testPipelineVersionValue.equals(opt.get()))
                .verifyComplete();
        // ... verify last updated ...

        // 5. Trigger refresh and verify beans (as before)
        // ... (eventPublisher.publishEvent, Awaitility for bean state) ...

        LOG.info("testManualInitialSeeding completed successfully.");
    }

    // --- Test 2: Verify Idempotency ---
    @Test
    @Order(2)
    @DisplayName("Seeding logic should be idempotent (flag prevents re-run)")
    void testSeedingIsIdempotent() {
        String keyToModify = testPipelineServiceImplKey;
        String initialValueFromYaml = testPipelineServiceImplValue;
        String modifiedValue = "modified-by-idempotency-test";
        LOG.info("Running test: testSeedingIsIdempotent");

        // Pre-condition 1: Manually seed first (simulates state after first run)
        LOG.info("Seeding manually to establish initial state...");
        StepVerifier.create(consulDataSeeder.seedData()).expectNext(true).verifyComplete();

        // Pre-condition 2: Verify flag IS set after manual seed (using consistent read)
        LOG.info("Verifying flag is set after initial manual seed...");
        QueryOptions consistentOpts = ImmutableQueryOptions.builder().consistencyMode(ConsistencyMode.CONSISTENT).build();
        assertTrue(keyValueClient.getValue(seededFlagKey, consistentOpts).flatMap(Value::getValueAsString).filter("true"::equals).isPresent(),
                "Pre-condition failed: Seeded flag not true after initial manual seed.");
        LOG.info("Pre-condition verified (seeded flag is true).");


        // Verify the initial value from YAML exists
        LOG.info("Verifying initial value of key to modify '{}'...", keyToModify);
        // Use consistent read here too for safety
        Optional<String> initialRead = keyValueClient.getValue(keyToModify, consistentOpts).flatMap(Value::getValueAsString);
        assertTrue(initialRead.isPresent() && initialValueFromYaml.equals(initialRead.get()), "Initial value mismatch for " + keyToModify);

        // Modify the value
        LOG.info("Modifying key '{}' to value '{}'", keyToModify, modifiedValue);
        assertTrue(keyValueClient.putValue(keyToModify, modifiedValue), "Modifying value failed");
        // Verify modification with consistent read
        Optional<String> modifiedRead = keyValueClient.getValue(keyToModify, consistentOpts).flatMap(Value::getValueAsString);
        assertTrue(modifiedRead.isPresent() && modifiedValue.equals(modifiedRead.get()), "Modification verification failed");
        LOG.info("Test key modified and verified.");

        // Explicitly trigger seeding logic again via seedData()
        LOG.info("Explicitly triggering ConsulDataSeeder logic again via seedData()...");
        // Use StepVerifier for reactive call
        StepVerifier.create(consulDataSeeder.seedData())
                .expectNext(true) // Should return true because flag exists -> already seeded/skipped now
                .as("Execute seedData() again")
                .verifyComplete();
        LOG.info("seedData() completed (should have skipped actual seeding).");

        // Verify the modified value was NOT overwritten (using consistent read)
        LOG.info("Verifying key '{}' was not overwritten...", keyToModify);
        Optional<String> finalRead = keyValueClient.getValue(keyToModify, consistentOpts).flatMap(Value::getValueAsString);
        assertTrue(finalRead.isPresent() && modifiedValue.equals(finalRead.get()), "Value was overwritten! Expected '" + modifiedValue + "', got: " + finalRead.orElse("empty"));
        LOG.info("Modified value retained. Idempotency verified.");

        // Verify the auto-added version key STILL exists and is "0"
        LOG.info("Verifying version key '{}' still exists with value '{}'...", testPipelineVersionKey, testPipelineVersionValue);
        Optional<String> versionRead = keyValueClient.getValue(testPipelineVersionKey, consistentOpts).flatMap(Value::getValueAsString);
        assertTrue(versionRead.isPresent() && testPipelineVersionValue.equals(versionRead.get()), "Version key check failed");

        LOG.info("testSeedingIsIdempotent completed successfully.");
    }
}