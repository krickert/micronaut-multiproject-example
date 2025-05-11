package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.schema.registry.model.SchemaCompatibility;
import com.krickert.search.config.schema.registry.model.SchemaType;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(propertySources = "classpath:application-test.properties")
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
class KiwiprojectConsulConfigFetcherMicronautTest {

    private static final Logger LOG = LoggerFactory.getLogger(KiwiprojectConsulConfigFetcherMicronautTest.class);

    @Inject
    KiwiprojectConsulConfigFetcher configFetcher; // SUT

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Consul directConsulClientForTestSetup;

    @Property(name = "app.config.consul.key-prefixes.pipeline-clusters")
    String clusterConfigKeyPrefixWithSlash;

    @Property(name = "app.config.consul.key-prefixes.schema-versions")
    String schemaVersionsKeyPrefixWithSlash;

    @Property(name = "app.config.cluster-name")
    String defaultTestClusterNameFromProperties;

    @Property(name = "app.config.consul.watch-seconds")
    int appWatchSeconds;

    private KeyValueClient testKvClient;
    private final String testClusterForWatch = "watchTestClusterDelta"; // Unique name
    private final String testSchemaSubject1 = "integTestSchemaSubjectDelta1";
    private final int testSchemaVersion1 = 1;

    private String fullWatchClusterKey;
    private String fullDefaultClusterKey;
    private String fullTestSchemaKey1;

    @BeforeEach
    void setUp() {
        assertNotNull(directConsulClientForTestSetup, "Test Consul client should be injected by TestResources");
        testKvClient = directConsulClientForTestSetup.keyValueClient();
        assertNotNull(testKvClient, "KeyValueClient for test setup should not be null");

        String clusterPrefix = clusterConfigKeyPrefixWithSlash.endsWith("/") ? clusterConfigKeyPrefixWithSlash : clusterConfigKeyPrefixWithSlash + "/";
        fullDefaultClusterKey = clusterPrefix + defaultTestClusterNameFromProperties;
        fullWatchClusterKey = clusterPrefix + testClusterForWatch;

        String schemaPrefix = schemaVersionsKeyPrefixWithSlash.endsWith("/") ? schemaVersionsKeyPrefixWithSlash : schemaVersionsKeyPrefixWithSlash + "/";
        fullTestSchemaKey1 = String.format("%s%s/%d", schemaPrefix, testSchemaSubject1, testSchemaVersion1);

        configFetcher.connect();

        LOG.info("Deleting KV key for default cluster setup: {}", fullDefaultClusterKey);
        testKvClient.deleteKey(fullDefaultClusterKey);
        LOG.info("Deleting KV key for watch cluster setup: {}", fullWatchClusterKey);
        testKvClient.deleteKey(fullWatchClusterKey);
        LOG.info("Deleting KV key for schema setup: {}", fullTestSchemaKey1);
        testKvClient.deleteKey(fullTestSchemaKey1);
        LOG.info("Cleaned up Consul keys for test setup.");
    }

    @AfterEach
    void tearDown() {
        if (testKvClient != null) {
            if (fullDefaultClusterKey != null) testKvClient.deleteKey(fullDefaultClusterKey);
            if (fullWatchClusterKey != null) testKvClient.deleteKey(fullWatchClusterKey);
            if (fullTestSchemaKey1 != null) testKvClient.deleteKey(fullTestSchemaKey1);
        }
        LOG.info("Test finished, keys potentially cleaned.");
    }

    private PipelineClusterConfig createDummyClusterConfig(String name) {
        return new PipelineClusterConfig(
                name,
                new PipelineGraphConfig(Collections.emptyMap()),
                new PipelineModuleMap(Collections.emptyMap()),
                Collections.emptySet(),
                Collections.emptySet()
        );
    }

    private SchemaVersionData createDummySchemaData(String subject, int version, String content) {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new SchemaVersionData(
                1L, subject, version, content,
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, createdAt, "Integration test schema"
        );
    }

    private void seedConsulKv(String key, Object object) throws JsonProcessingException {
        String jsonValue = objectMapper.writeValueAsString(object);
        LOG.info("Seeding Consul KV: {} = {}", key, jsonValue.length() > 200 ? jsonValue.substring(0, 200) + "..." : jsonValue);
        assertTrue(testKvClient.putValue(key, jsonValue), "Failed to seed Consul KV for key: " + key);
    }

    @Test
    @DisplayName("Fetcher should be injected and connected to TestContainers Consul")
    void fetcherInjectedAndConsulPropertiesCorrect() {
        assertNotNull(configFetcher, "KiwiprojectConsulConfigFetcher should be injected.");
        Optional<PipelineClusterConfig> result = configFetcher.fetchPipelineClusterConfig("someNonExistentClusterForConnectionTest");
        assertFalse(result.isPresent());
        LOG.info("Connection test: fetch for non-existent key completed.");
    }

    @Test
    @DisplayName("fetchPipelineClusterConfig - should retrieve and deserialize existing config")
    void fetchPipelineClusterConfig_whenKeyExists_returnsConfig() throws Exception {
        PipelineClusterConfig expectedConfig = new PipelineClusterConfig(
                defaultTestClusterNameFromProperties,
                new PipelineGraphConfig(Collections.emptyMap()),
                new PipelineModuleMap(Collections.emptyMap()),
                Set.of("topicA", "topicB"),
                Set.of("serviceX")
        );
        seedConsulKv(fullDefaultClusterKey, expectedConfig);
        Optional<PipelineClusterConfig> fetchedOpt = configFetcher.fetchPipelineClusterConfig(defaultTestClusterNameFromProperties);
        assertTrue(fetchedOpt.isPresent(), "Expected config to be present");
        assertEquals(expectedConfig, fetchedOpt.get(), "Fetched config should match expected");
    }

    @Test
    @DisplayName("fetchPipelineClusterConfig - should return empty for non-existent key")
    void fetchPipelineClusterConfig_whenKeyMissing_returnsEmpty() {
        Optional<PipelineClusterConfig> fetchedOpt = configFetcher.fetchPipelineClusterConfig("completelyMissingCluster");
        assertTrue(fetchedOpt.isEmpty(), "Expected empty Optional for missing key");
    }

    @Test
    @DisplayName("fetchPipelineClusterConfig - should return empty for malformed JSON and log error")
    void fetchPipelineClusterConfig_whenJsonMalformed_returnsEmpty() throws Exception {
        LOG.info("Seeding Consul with malformed JSON for test: {}", fullDefaultClusterKey);
        assertTrue(testKvClient.putValue(fullDefaultClusterKey, "{\"clusterName\":\"bad\", this_is_not_json}"), "Failed to seed malformed JSON");
        Optional<PipelineClusterConfig> fetchedOpt = configFetcher.fetchPipelineClusterConfig(defaultTestClusterNameFromProperties);
        assertTrue(fetchedOpt.isEmpty(), "Expected empty Optional for malformed JSON");
    }

    @Test
    @DisplayName("fetchSchemaVersionData - should retrieve and deserialize existing schema")
    void fetchSchemaVersionData_whenKeyExists_returnsData() throws Exception {
        SchemaVersionData expectedSchema = createDummySchemaData(testSchemaSubject1, testSchemaVersion1, "{\"type\":\"string\"}");
        seedConsulKv(fullTestSchemaKey1, expectedSchema);
        Optional<SchemaVersionData> fetchedOpt = configFetcher.fetchSchemaVersionData(testSchemaSubject1, testSchemaVersion1);
        assertTrue(fetchedOpt.isPresent(), "Expected schema data to be present");
        assertEquals(expectedSchema, fetchedOpt.get(), "Fetched schema data should match expected");
    }

    @Test
    @DisplayName("fetchSchemaVersionData - should return empty for non-existent key")
    void fetchSchemaVersionData_whenKeyMissing_returnsEmpty() {
        Optional<SchemaVersionData> fetchedOpt = configFetcher.fetchSchemaVersionData("nonExistentSubject", 99);
        assertTrue(fetchedOpt.isEmpty(), "Expected empty Optional for missing schema key");
    }

    @Test
    @DisplayName("fetchSchemaVersionData - should return empty for malformed JSON and log error")
    void fetchSchemaVersionData_whenJsonMalformed_returnsEmpty() throws Exception {
        LOG.info("Seeding Consul with malformed JSON for schema test: {}", fullTestSchemaKey1);
        assertTrue(testKvClient.putValue(fullTestSchemaKey1, "{\"subject\":\"bad\", this_is_not_json_for_schema}"), "Failed to seed malformed JSON for schema");
        Optional<SchemaVersionData> fetchedOpt = configFetcher.fetchSchemaVersionData(testSchemaSubject1, testSchemaVersion1);
        assertTrue(fetchedOpt.isEmpty(), "Expected empty Optional for malformed schema JSON");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS) // Increased timeout for watch tests
    @DisplayName("watchClusterConfig - should receive initial, updated, deleted, and error states")
    void watchClusterConfig_receivesAllStates() throws Exception {
        BlockingQueue<WatchCallbackResult> updates = new ArrayBlockingQueue<>(10); // Use WatchCallbackResult
        Consumer<WatchCallbackResult> testUpdateHandler = updateResult -> { // Use WatchCallbackResult
            LOG.info("TestUpdateHandler (watchAllStates) received: {}", updateResult);
            updates.offer(updateResult);
        };

        configFetcher.watchClusterConfig(testClusterForWatch, testUpdateHandler);
        LOG.info("Watch started for key: {}", fullWatchClusterKey);

        // 1. Consume potential initial "deleted" event if key doesn't exist
        WatchCallbackResult firstEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(firstEvent, "Should receive an initial event from KVCache (either deleted or first data)");
        if (firstEvent.config().isPresent()) {
            LOG.info("Watch Test: Initial event contained data (key might have existed briefly or cache fired fast): {}", firstEvent);
        } else {
            assertTrue(firstEvent.deleted(), "If initial event has no config and no error, it should be 'deleted'. Event: " + firstEvent);
            LOG.info("Watch Test: Consumed initial deleted/empty event for key {}: {}", fullWatchClusterKey, firstEvent);
        }

        // 2. Test Initial config PUT after watch starts
        LOG.info("Watch Test: Putting initial config for key {}...", fullWatchClusterKey);
        PipelineClusterConfig initialConfig = createDummyClusterConfig(testClusterForWatch);
        initialConfig = new PipelineClusterConfig(initialConfig.clusterName(), initialConfig.pipelineGraphConfig(), initialConfig.pipelineModuleMap(), Set.of("initialTopic"), initialConfig.allowedGrpcServices());
        seedConsulKv(fullWatchClusterKey, initialConfig);

        WatchCallbackResult receivedInitialResult = updates.poll(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(receivedInitialResult, "Handler should have received initial config from watch after PUT");
        assertEquals(initialConfig, receivedInitialResult.config().get());
        assertFalse(receivedInitialResult.deleted(), "Initial result should not be marked deleted");
        assertFalse(receivedInitialResult.hasError(), "Initial result should not have error");
        LOG.info("Watch Test: Initial config received successfully: {}", receivedInitialResult);

        // 3. Update the config
        LOG.info("Watch Test: Updating config for key {}...", fullWatchClusterKey);
        PipelineClusterConfig updatedConfig = new PipelineClusterConfig(testClusterForWatch,
                null, null, Set.of("updatedTopic"), Collections.singleton("updatedService"));
        seedConsulKv(fullWatchClusterKey, updatedConfig);

        WatchCallbackResult receivedUpdateResult = updates.poll(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(receivedUpdateResult, "Handler should have received updated config from watch");
        assertEquals(updatedConfig, receivedUpdateResult.config().get());
        LOG.info("Watch Test: Updated config received successfully: {}", receivedUpdateResult);

        // 4. Update with Malformed JSON
        LOG.info("Watch Test: Putting malformed JSON to Consul for watch: {}", fullWatchClusterKey);
        assertTrue(testKvClient.putValue(fullWatchClusterKey, "this is definitely not json {{{{"), "Failed to seed malformed JSON");

        WatchCallbackResult receivedMalformedResult = updates.poll(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(receivedMalformedResult, "Handler should have received a result after malformed JSON update.");
        assertTrue(receivedMalformedResult.hasError(), "Result after malformed JSON should indicate an error.");
        assertTrue(receivedMalformedResult.error().get() instanceof JsonProcessingException, "Error should be JsonProcessingException.");
        LOG.info("Watch Test: Malformed JSON update resulted in error callback: {}", receivedMalformedResult);

        // 5. Delete the config
        LOG.info("Watch Test: Deleting config for key {}...", fullWatchClusterKey);
        testKvClient.deleteKey(fullWatchClusterKey);

        WatchCallbackResult receivedDeleteResult = updates.poll(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(receivedDeleteResult, "Handler should have received notification for delete");
        assertTrue(receivedDeleteResult.deleted(), "Result should be marked as deleted");
        assertFalse(receivedDeleteResult.hasError(), "Deleted result should not have error");
        LOG.info("Watch Test: Deletion notification received successfully: {}", receivedDeleteResult);

        // 6. Re-put initial config
        LOG.info("Watch Test: Re-putting initial config for key {}...", fullWatchClusterKey);
        seedConsulKv(fullWatchClusterKey, initialConfig); // Use the same initialConfig object
        WatchCallbackResult receivedRecreateResult = updates.poll(appWatchSeconds + 10, TimeUnit.SECONDS);
        assertNotNull(receivedRecreateResult, "Handler should have received re-created config");
        assertEquals(initialConfig, receivedRecreateResult.config().get());
        LOG.info("Watch Test: Re-created config received successfully: {}", receivedRecreateResult);

        WatchCallbackResult spuriousUpdate = updates.poll(2, TimeUnit.SECONDS);
        assertNull(spuriousUpdate, "Should be no more spurious updates in the queue. Last received: " + receivedRecreateResult);
    }

    // The watchClusterConfig_handlesMalformedJsonUpdate test is now effectively merged into
    // the comprehensive watchClusterConfig_receivesAllStates test.
    // If kept separate, it would be a more focused version of step 4 in the above test.
}