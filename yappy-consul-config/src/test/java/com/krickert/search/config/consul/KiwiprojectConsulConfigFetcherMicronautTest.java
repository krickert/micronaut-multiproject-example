package com.krickert.search.config.consul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;
import com.krickert.search.config.schema.registry.model.SchemaType; // For creating test data
import com.krickert.search.config.schema.registry.model.SchemaCompatibility; // For creating test data


import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.kiwiproject.consul.Consul; // For test setup client
import org.kiwiproject.consul.KeyValueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(propertySources = "classpath:config-fetcher-test.properties") // Use a specific properties file for test
@Property(name = "micronaut.config-client.enabled", value = "false") // Disable Micronaut's own config client for this test
@Property(name = "consul.enabled", value = "true") // Ensure our fetcher bean is created
class KiwiprojectConsulConfigFetcherMicronautTest {

    private static final Logger LOG = LoggerFactory.getLogger(KiwiprojectConsulConfigFetcherMicronautTest.class);

    @Inject
    KiwiprojectConsulConfigFetcher configFetcher; // The bean under test

    @Inject
    ObjectMapper objectMapper; // For serializing test data to JSON

    // These would be injected by Micronaut Test Resources from your ConsulTestResourceProvider
    @Inject
    Consul directConsulClientForTestSetup; // To PUT/DELETE data into the Testcontainer Consul

    // Read from properties file, matching what KiwiprojectConsulConfigFetcher uses
    @Value("${app.config.consul.key-prefixes.pipeline-clusters:pipeline-configs/clusters}")
    String clusterConfigKeyPrefix;
    @Value("${app.config.consul.key-prefixes.schema-versions:pipeline-configs/schemas/versions}")
    String schemaVersionsKeyPrefix;

    private KeyValueClient testKvClient;

    private final String testClusterName1 = "integTestCluster1";
    private final String testSchemaSubject1 = "integTestSchema1";
    private final int testSchemaVersion1 = 1;

    private String clusterConfigKey1;
    private String schemaVersionKey1;

    @BeforeEach
    void setUp() {
        // Ensure the fetcher's internal client is connected (it should be by Micronaut DI if PostConstruct is used)
        // Or call connect if it's not automatically done by Micronaut's lifecycle for this bean
        configFetcher.connect(); // Make sure it's connected

        testKvClient = directConsulClientForTestSetup.keyValueClient();

        // Define full keys based on prefixes
        clusterConfigKey1 = (clusterConfigKeyPrefix.endsWith("/") ? clusterConfigKeyPrefix : clusterConfigKeyPrefix + "/") + testClusterName1;
        schemaVersionKey1 = String.format("%s%s/%d",
                (schemaVersionsKeyPrefix.endsWith("/") ? schemaVersionsKeyPrefix : schemaVersionsKeyPrefix + "/"),
                testSchemaSubject1, testSchemaVersion1);

        // Clean up keys before each test
        testKvClient.deleteKey(clusterConfigKey1);
        testKvClient.deleteKey(schemaVersionKey1);
        LOG.info("Cleaned up Consul keys for test setup.");
    }

    @AfterEach
    void tearDown() {
        // Clean up keys after each test
        if (testKvClient != null) {
            testKvClient.deleteKey(clusterConfigKey1);
            testKvClient.deleteKey(schemaVersionKey1);
        }
        // configFetcher.close(); // The fetcher bean will be closed by Micronaut context shutdown
        LOG.info("Test finished, keys potentially cleaned.");
    }

    private PipelineClusterConfig createDummyClusterConfig(@SuppressWarnings("SameParameterValue") String name) {
        return new PipelineClusterConfig(name, null, null, Collections.emptySet(), Collections.emptySet());
    }

    @SuppressWarnings("SameParameterValue")
    private SchemaVersionData createDummySchemaData(String subject, int version) {
        return new SchemaVersionData(1L, subject, version, "{\"type\":\"string\"}", SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "Integ test schema");
    }

    // --- Test Cases ---

    @Test
    void fetchPipelineClusterConfig_whenKeyExists_returnsConfig() throws Exception {
        PipelineClusterConfig expectedConfig = createDummyClusterConfig(testClusterName1);
        String jsonConfig = objectMapper.writeValueAsString(expectedConfig);
        testKvClient.putValue(clusterConfigKey1, jsonConfig);
        LOG.info("PUT to Consul: {} = {}", clusterConfigKey1, jsonConfig);

        Optional<PipelineClusterConfig> fetchedConfigOpt = configFetcher.fetchPipelineClusterConfig(testClusterName1);

        assertTrue(fetchedConfigOpt.isPresent(), "Config should be fetched");
        assertEquals(expectedConfig.clusterName(), fetchedConfigOpt.get().clusterName());
    }

    @Test
    void fetchPipelineClusterConfig_whenKeyNotExists_returnsEmpty() {
        Optional<PipelineClusterConfig> fetchedConfigOpt = configFetcher.fetchPipelineClusterConfig("nonExistentCluster");
        assertFalse(fetchedConfigOpt.isPresent(), "Config should be empty for non-existent key");
    }

    @Test
    void fetchPipelineClusterConfig_whenJsonIsMalformed_returnsEmptyAndLogsError() {
        testKvClient.putValue(clusterConfigKey1, "this is not valid json");
        LOG.info("PUT malformed JSON to Consul: {}", clusterConfigKey1);

        Optional<PipelineClusterConfig> fetchedConfigOpt = configFetcher.fetchPipelineClusterConfig(testClusterName1);
        assertFalse(fetchedConfigOpt.isPresent(), "Config should be empty for malformed JSON");
        // Verify logs (manually or with a test appender) that an error was logged by the fetcher
    }


    @Test
    void fetchSchemaVersionData_whenKeyExists_returnsData() throws Exception {
        SchemaVersionData expectedSchema = createDummySchemaData(testSchemaSubject1, testSchemaVersion1);
        String jsonSchema = objectMapper.writeValueAsString(expectedSchema);
        testKvClient.putValue(schemaVersionKey1, jsonSchema);
        LOG.info("PUT to Consul: {} = {}", schemaVersionKey1, jsonSchema);

        Optional<SchemaVersionData> fetchedSchemaOpt = configFetcher.fetchSchemaVersionData(testSchemaSubject1, testSchemaVersion1);

        assertTrue(fetchedSchemaOpt.isPresent(), "Schema data should be fetched");
        assertEquals(expectedSchema.subject(), fetchedSchemaOpt.get().subject());
        assertEquals(expectedSchema.version(), fetchedSchemaOpt.get().version());
        assertEquals(expectedSchema.schemaContent(), fetchedSchemaOpt.get().schemaContent());
    }

    @Test
    void fetchSchemaVersionData_whenKeyNotExists_returnsEmpty() {
        Optional<SchemaVersionData> fetchedSchemaOpt = configFetcher.fetchSchemaVersionData("nonExistentSubject", 1);
        assertFalse(fetchedSchemaOpt.isPresent(), "Schema data should be empty for non-existent key");
    }

    // --- Watch Test (Most Critical) ---

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS) // KVCache watch default is 30s, give some buffer
    void watchClusterConfig_receivesUpdatesAndDeletes() throws Exception {
        BlockingQueue<Optional<PipelineClusterConfig>> updates = new ArrayBlockingQueue<>(10);
        @SuppressWarnings("ResultOfMethodCallIgnored") Consumer<Optional<PipelineClusterConfig>> testUpdateHandler = updates::offer;

        configFetcher.watchClusterConfig(testClusterName1, testUpdateHandler);
        LOG.info("Watch started for {}", clusterConfigKey1);

        // 1. Initial state (or first update if cache fires on start with existing value)
        // KVCache often fires immediately if value exists. If not, first PUT will trigger.
        // To be safe, let's ensure an initial state.
        PipelineClusterConfig initialConfig = createDummyClusterConfig(testClusterName1);
        String initialJson = objectMapper.writeValueAsString(initialConfig);
        testKvClient.putValue(clusterConfigKey1, initialJson);
        LOG.info("PUT initial config: {}", clusterConfigKey1);

        Optional<Optional<PipelineClusterConfig>> receivedInitialOpt = Optional.ofNullable(updates.poll(15, TimeUnit.SECONDS)); // KVCache might take a moment for initial
        assertTrue(receivedInitialOpt.isPresent(), "Handler should have received initial config from watch");
        assertTrue(receivedInitialOpt.get().isPresent(), "Initial config Optional should be present");
        assertEquals(initialConfig.clusterName(), receivedInitialOpt.get().get().clusterName());

        // 2. Update the config
        PipelineClusterConfig updatedConfig = new PipelineClusterConfig(testClusterName1,
                null, null, Collections.singleton("newTopic"), Collections.emptySet());
        String updatedJson = objectMapper.writeValueAsString(updatedConfig);
        testKvClient.putValue(clusterConfigKey1, updatedJson);
        LOG.info("PUT updated config: {}", clusterConfigKey1);

        Optional<Optional<PipelineClusterConfig>> receivedUpdateOpt = Optional.ofNullable(updates.poll(watchSecondsFromConfig() + 10, TimeUnit.SECONDS)); // Add buffer to watchSeconds
        assertTrue(receivedUpdateOpt.isPresent(), "Handler should have received updated config from watch");
        assertTrue(receivedUpdateOpt.get().isPresent(), "Updated config Optional should be present");
        assertEquals(updatedConfig.clusterName(), receivedUpdateOpt.get().get().clusterName());
        assertTrue(receivedUpdateOpt.get().get().allowedKafkaTopics().contains("newTopic"));

        // 3. Delete the config
        testKvClient.deleteKey(clusterConfigKey1);
        LOG.info("DELETED config: {}", clusterConfigKey1);

        Optional<Optional<PipelineClusterConfig>> receivedDeleteOpt = Optional.ofNullable(updates.poll(watchSecondsFromConfig() + 10, TimeUnit.SECONDS));
        assertTrue(receivedDeleteOpt.isPresent(), "Handler should have received notification for delete");
        assertTrue(receivedDeleteOpt.get().isEmpty(), "Config Optional should be empty after delete");

        // 4. (Optional) Put it back to test re-creation
        testKvClient.putValue(clusterConfigKey1, initialJson);
        LOG.info("RE-PUT initial config: {}", clusterConfigKey1);
        Optional<Optional<PipelineClusterConfig>> receivedRecreateOpt = Optional.ofNullable(updates.poll(watchSecondsFromConfig() + 10, TimeUnit.SECONDS));
        assertTrue(receivedRecreateOpt.isPresent(), "Handler should have received re-created config");
        assertTrue(receivedRecreateOpt.get().isPresent(), "Re-created config Optional should be present");
        assertEquals(initialConfig.clusterName(), receivedRecreateOpt.get().get().clusterName());
    }

    private int watchSecondsFromConfig() {
        // Helper to get the configured watch seconds for timeouts
        // This assumes KiwiprojectConsulConfigFetcher is constructed with the value from properties
        // For a pure unit test, you'd mock this or pass it. Here we rely on @Value in the SUT.
        // This is a bit of an integration detail leaking into test timing.
        try {
            java.lang.reflect.Field field = KiwiprojectConsulConfigFetcher.class.getDeclaredField("watchSeconds");
            field.setAccessible(true);
            return (int) field.get(configFetcher);
        } catch (Exception e) {
            return 30; // fallback
        }
    }
}