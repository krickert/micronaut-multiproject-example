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

@MicronautTest(startApplication = false, environments = {"test"}) // We don't need the full app, just property resolution
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

    @Test
    @DisplayName("watchClusterConfig - re-watching same key replaces handler")
    @Timeout(value = 30, unit = TimeUnit.SECONDS) // Adjust timeout as needed
    void watchClusterConfig_rewatchSameKey_replacesHandler() throws Exception {
        BlockingQueue<WatchCallbackResult> updatesA = new ArrayBlockingQueue<>(5);
        Consumer<WatchCallbackResult> handlerA = updatesA::offer;

        BlockingQueue<WatchCallbackResult> updatesB = new ArrayBlockingQueue<>(5);
        Consumer<WatchCallbackResult> handlerB = updatesB::offer;

        PipelineClusterConfig config1 = createDummyClusterConfig(testClusterForWatch);
        config1 = new PipelineClusterConfig(config1.clusterName(), config1.pipelineGraphConfig(), config1.pipelineModuleMap(), Set.of("topic1"), config1.allowedGrpcServices());

        PipelineClusterConfig config2 = createDummyClusterConfig(testClusterForWatch);
        config2 = new PipelineClusterConfig(config2.clusterName(), config2.pipelineGraphConfig(), config2.pipelineModuleMap(), Set.of("topic2"), config2.allowedGrpcServices());

        PipelineClusterConfig config3 = createDummyClusterConfig(testClusterForWatch);
        config3 = new PipelineClusterConfig(config3.clusterName(), config3.pipelineGraphConfig(), config3.pipelineModuleMap(), Set.of("topic3"), config3.allowedGrpcServices());


        // Initial watch with Handler A
        configFetcher.watchClusterConfig(testClusterForWatch, handlerA);
        LOG.info("watchClusterConfig_rewatchSameKey: Watch A started for {}", fullWatchClusterKey);

        // Consume initial event for Handler A (could be deleted or pre-existing)
        updatesA.poll(appWatchSeconds + 5, TimeUnit.SECONDS);

        seedConsulKv(fullWatchClusterKey, config1);
        WatchCallbackResult resultA1 = updatesA.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(resultA1, "Handler A should receive config1");
        assertEquals(config1, resultA1.config().orElse(null));
        LOG.info("watchClusterConfig_rewatchSameKey: Handler A received config1");

        // Re-watch with Handler B for the SAME key
        configFetcher.watchClusterConfig(testClusterForWatch, handlerB);
        LOG.info("watchClusterConfig_rewatchSameKey: Watch B started for {}", fullWatchClusterKey);

        // Consume initial event for Handler B
        updatesB.poll(appWatchSeconds + 5, TimeUnit.SECONDS);

        seedConsulKv(fullWatchClusterKey, config2);
        WatchCallbackResult resultB2 = updatesB.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(resultB2, "Handler B should receive config2");
        assertEquals(config2, resultB2.config().orElse(null));
        LOG.info("watchClusterConfig_rewatchSameKey: Handler B received config2");

        // Handler A should NOT receive config2
        WatchCallbackResult spuriousResultA = updatesA.poll(2, TimeUnit.SECONDS); // Short poll
        assertNull(spuriousResultA, "Handler A should NOT receive config2 after re-watch. Got: " + spuriousResultA);

        // Further check: Handler B receives another update, Handler A still doesn't
        seedConsulKv(fullWatchClusterKey, config3);
        WatchCallbackResult resultB3 = updatesB.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(resultB3, "Handler B should receive config3");
        assertEquals(config3, resultB3.config().orElse(null));
        LOG.info("watchClusterConfig_rewatchSameKey: Handler B received config3");

        spuriousResultA = updatesA.poll(2, TimeUnit.SECONDS);
        assertNull(spuriousResultA, "Handler A should NOT receive config3. Got: " + spuriousResultA);
    }

    @Test
    @DisplayName("watchClusterConfig - watching a different key stops previous watch")
    @Timeout(value = 45, unit = TimeUnit.SECONDS) // May need adjustment
    void watchClusterConfig_watchDifferentKey_stopsPreviousWatch() throws Exception {
        String clusterNameA = "clusterToWatchA";
        String fullClusterKeyA = (clusterConfigKeyPrefixWithSlash.endsWith("/") ? clusterConfigKeyPrefixWithSlash : clusterConfigKeyPrefixWithSlash + "/") + clusterNameA;

        String clusterNameB = "clusterToWatchB";
        String fullClusterKeyB = (clusterConfigKeyPrefixWithSlash.endsWith("/") ? clusterConfigKeyPrefixWithSlash : clusterConfigKeyPrefixWithSlash + "/") + clusterNameB;

        // Clean up these specific keys before the test
        testKvClient.deleteKey(fullClusterKeyA);
        testKvClient.deleteKey(fullClusterKeyB);
        LOG.info("Cleaned up keys for watchDifferentKey test: {}, {}", fullClusterKeyA, fullClusterKeyB);


        BlockingQueue<WatchCallbackResult> updatesA = new ArrayBlockingQueue<>(5);
        Consumer<WatchCallbackResult> handlerA = updatesA::offer;

        BlockingQueue<WatchCallbackResult> updatesB = new ArrayBlockingQueue<>(5);
        Consumer<WatchCallbackResult> handlerB = updatesB::offer;

        PipelineClusterConfig configA1 = createDummyClusterConfig(clusterNameA);
        configA1 = new PipelineClusterConfig(configA1.clusterName(), configA1.pipelineGraphConfig(), configA1.pipelineModuleMap(), Set.of("topicA1"), configA1.allowedGrpcServices());
        PipelineClusterConfig configA2 = createDummyClusterConfig(clusterNameA); // A second config for cluster A
        configA2 = new PipelineClusterConfig(configA2.clusterName(), configA2.pipelineGraphConfig(), configA2.pipelineModuleMap(), Set.of("topicA2"), configA2.allowedGrpcServices());


        PipelineClusterConfig configB1 = createDummyClusterConfig(clusterNameB);
        configB1 = new PipelineClusterConfig(configB1.clusterName(), configB1.pipelineGraphConfig(), configB1.pipelineModuleMap(), Set.of("topicB1"), configB1.allowedGrpcServices());

        // 1. Watch Cluster A
        configFetcher.watchClusterConfig(clusterNameA, handlerA);
        LOG.info("watchDifferentKey: Watch A started for {}", fullClusterKeyA);

        // Consume initial event for Handler A (likely deleted)
        WatchCallbackResult initialA = updatesA.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(initialA, "Handler A should receive an initial event");
        LOG.info("watchDifferentKey: Handler A initial event: {}", initialA);


        // Seed and verify config for Cluster A
        seedConsulKv(fullClusterKeyA, configA1);
        WatchCallbackResult resultA1 = updatesA.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(resultA1, "Handler A should receive configA1");
        assertEquals(configA1, resultA1.config().orElse(null));
        LOG.info("watchDifferentKey: Handler A received configA1");

        // 2. Now, watch Cluster B. This should implicitly stop the watch on Cluster A.
        configFetcher.watchClusterConfig(clusterNameB, handlerB);
        LOG.info("watchDifferentKey: Watch B started for {}", fullClusterKeyB);

        // Consume initial event for Handler B (likely deleted)
        WatchCallbackResult initialB = updatesB.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(initialB, "Handler B should receive an initial event");
        LOG.info("watchDifferentKey: Handler B initial event: {}", initialB);

        // Seed and verify config for Cluster B
        seedConsulKv(fullClusterKeyB, configB1);
        WatchCallbackResult resultB1 = updatesB.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(resultB1, "Handler B should receive configB1");
        assertEquals(configB1, resultB1.config().orElse(null));
        LOG.info("watchDifferentKey: Handler B received configB1");

        // 3. Now, update Cluster A again. Handler A should NOT receive this update.
        LOG.info("watchDifferentKey: Seeding {} with configA2, Handler A should NOT receive this.", fullClusterKeyA);
        seedConsulKv(fullClusterKeyA, configA2);

        WatchCallbackResult spuriousResultA = updatesA.poll(appWatchSeconds + 2, TimeUnit.SECONDS); // Poll for a bit longer than a very short poll
        assertNull(spuriousResultA, "Handler A should NOT receive configA2 after watch switched to B. Got: " + spuriousResultA);
        LOG.info("watchDifferentKey: Confirmed Handler A did not receive configA2 (as expected).");

        // Ensure Handler B is still active and not affected
        assertTrue(updatesB.isEmpty(), "Handler B's queue should be empty before next update to B");

        // Cleanup specific keys at the end of this test as well
        testKvClient.deleteKey(fullClusterKeyA);
        testKvClient.deleteKey(fullClusterKeyB);
    }

    @Test
    @DisplayName("close - stops active watch and allows subsequent fetches")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void close_stopsWatchAndAllowsSubsequentFetches() throws Exception {
        BlockingQueue<WatchCallbackResult> updates = new ArrayBlockingQueue<>(5);
        Consumer<WatchCallbackResult> handler = updates::offer;

        PipelineClusterConfig config1 = createDummyClusterConfig(testClusterForWatch);
        config1 = new PipelineClusterConfig(config1.clusterName(), config1.pipelineGraphConfig(), config1.pipelineModuleMap(), Set.of("topicClose1"), config1.allowedGrpcServices());
        PipelineClusterConfig config2 = createDummyClusterConfig(testClusterForWatch);
        config2 = new PipelineClusterConfig(config2.clusterName(), config2.pipelineGraphConfig(), config2.pipelineModuleMap(), Set.of("topicClose2"), config2.allowedGrpcServices());

        // 1. Establish a watch and get an initial update
        configFetcher.watchClusterConfig(testClusterForWatch, handler);
        LOG.info("close_test: Watch started for {}", fullWatchClusterKey);

        // Consume initial event (likely deleted)
        WatchCallbackResult initialEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(initialEvent, "Should receive an initial event");
        LOG.info("close_test: Initial event: {}", initialEvent);

        seedConsulKv(fullWatchClusterKey, config1);
        WatchCallbackResult result1 = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(result1, "Handler should receive config1");
        assertEquals(config1, result1.config().orElse(null));
        LOG.info("close_test: Handler received config1: {}", result1);

        // 2. Close the config fetcher
        LOG.info("close_test: Calling configFetcher.close()");
        configFetcher.close();
        LOG.info("close_test: configFetcher.close() completed");

        // Assertions about the closed state (optional, but good)
        assertNull(configFetcher.clusterConfigCache, "KVCache should be null after close");
        assertFalse(configFetcher.watcherStarted.get(), "WatcherStarted flag should be false after close");
        assertFalse(configFetcher.connected.get(), "Connected flag should be false after close");
        assertNull(configFetcher.kvClient, "kvClient should be null after close (as per current close logic)");


        // 3. Try to update the key in Consul. The handler should NOT receive this.
        LOG.info("close_test: Seeding {} with config2 AFTER close. Handler should NOT receive this.", fullWatchClusterKey);
        seedConsulKv(fullWatchClusterKey, config2);

        WatchCallbackResult spuriousResult = updates.poll(appWatchSeconds + 2, TimeUnit.SECONDS);
        assertNull(spuriousResult, "Handler should NOT receive config2 after close. Got: " + spuriousResult);
        LOG.info("close_test: Confirmed handler did not receive config2 after close (as expected).");

        // 4. Attempt to fetch the configuration again. This should succeed.
        // The `ensureConnected()` within `fetchPipelineClusterConfig` should re-establish the kvClient.
        LOG.info("close_test: Attempting to fetch {} AFTER close.", fullWatchClusterKey);
        Optional<PipelineClusterConfig> fetchedAfterCloseOpt = configFetcher.fetchPipelineClusterConfig(testClusterForWatch);
        assertTrue(fetchedAfterCloseOpt.isPresent(), "Should be able to fetch config2 after close (and re-connect)");
        assertEquals(config2, fetchedAfterCloseOpt.get(), "Fetched config after close should be config2");
        LOG.info("close_test: Successfully fetched config2 after close: {}", fetchedAfterCloseOpt.get());

        // Verify internal state after fetch (kvClient should be re-initialized)
        assertTrue(configFetcher.connected.get(), "Connected flag should be true after successful fetch post-close");
        assertNotNull(configFetcher.kvClient, "kvClient should be re-initialized after successful fetch post-close");
    }

    @Test
    @DisplayName("watchClusterConfig - handles empty or blank JSON values correctly")
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void watchClusterConfig_handlesEmptyOrBlankJsonValues() throws Exception {
        BlockingQueue<WatchCallbackResult> updates = new ArrayBlockingQueue<>(10);
        Consumer<WatchCallbackResult> handler = updates::offer;

        PipelineClusterConfig initialValidConfig = createDummyClusterConfig(testClusterForWatch);
        initialValidConfig = new PipelineClusterConfig(initialValidConfig.clusterName(),
                initialValidConfig.pipelineGraphConfig(), initialValidConfig.pipelineModuleMap(),
                Set.of("topicInitial"), initialValidConfig.allowedGrpcServices());

        // Start the watch
        configFetcher.watchClusterConfig(testClusterForWatch, handler);
        LOG.info("handlesEmptyOrBlankJsonValues: Watch started for {}", fullWatchClusterKey);

        // Consume initial event (likely deleted)
        WatchCallbackResult initialEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(initialEvent, "Should receive an initial event");
        LOG.info("handlesEmptyOrBlankJsonValues: Initial event: {}", initialEvent);

        // 1. Seed with a valid config first
        seedConsulKv(fullWatchClusterKey, initialValidConfig);
        WatchCallbackResult validConfigEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(validConfigEvent, "Handler should receive initial valid config");
        assertTrue(validConfigEvent.config().isPresent(), "Valid config should be present");
        assertEquals(initialValidConfig, validConfigEvent.config().get());
        LOG.info("handlesEmptyOrBlankJsonValues: Received initial valid config: {}", validConfigEvent);

        // 2. Update with an empty JSON object "{}"
        LOG.info("handlesEmptyOrBlankJsonValues: Seeding with empty JSON object {{}}");
        assertTrue(testKvClient.putValue(fullWatchClusterKey, "{}"), "Failed to seed empty JSON object");
        WatchCallbackResult emptyObjectEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(emptyObjectEvent, "Should receive event for empty JSON object");

        // Expectation for "{}":
        // If PipelineClusterConfig can be deserialized from "{}", it's a success.
        // Otherwise, it's a JsonProcessingException (error).
        // Let's assume for now it might deserialize to a default/empty PipelineClusterConfig.
        // If your ObjectMapper is configured to fail on unknown properties or if default constructor is not suitable,
        // this might be an error. Adjust assertion based on your PipelineClusterConfig and ObjectMapper.
        if (emptyObjectEvent.hasError()) {
            LOG.info("handlesEmptyOrBlankJsonValues: Empty JSON object resulted in an error: {}", emptyObjectEvent.error().get());
            assertTrue(emptyObjectEvent.error().get() instanceof JsonProcessingException, "Error should be JsonProcessingException for empty object if deserialization fails");
        } else {
            assertTrue(emptyObjectEvent.config().isPresent(), "Config should be present for empty JSON object if deserialized");
            // You might want to assert specific fields of the deserialized empty object if applicable
            // e.g., assertNotNull(emptyObjectEvent.config().get().getClusterName()); if it has a default
            LOG.info("handlesEmptyOrBlankJsonValues: Empty JSON object deserialized to: {}", emptyObjectEvent.config().get());
        }


        // 3. Update with an empty string ""
        LOG.info("handlesEmptyOrBlankJsonValues: Seeding with empty string \"\"");
        // Note: Consul might treat putting an empty string as deleting the value or the key itself.
        // The KVCache behavior might then report it as a delete.
        assertTrue(testKvClient.putValue(fullWatchClusterKey, ""), "Failed to seed empty string");
        WatchCallbackResult emptyStringEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(emptyStringEvent, "Should receive event for empty string");
        assertTrue(emptyStringEvent.deleted(), "Empty string should be treated as deleted. Event: " + emptyStringEvent);
        LOG.info("handlesEmptyOrBlankJsonValues: Empty string treated as deleted: {}", emptyStringEvent);

        // Re-seed with valid config to reset state for next sub-test
        seedConsulKv(fullWatchClusterKey, initialValidConfig);
        updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS); // Consume this update

        // 4. Update with a string of only whitespace "   "
        LOG.info("handlesEmptyOrBlankJsonValues: Seeding with whitespace string \"   \"");
        assertTrue(testKvClient.putValue(fullWatchClusterKey, "   "), "Failed to seed whitespace string");
        WatchCallbackResult whitespaceEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(whitespaceEvent, "Should receive event for whitespace string");
        assertTrue(whitespaceEvent.deleted(), "Whitespace string should be treated as deleted. Event: " + whitespaceEvent);
        LOG.info("handlesEmptyOrBlankJsonValues: Whitespace string treated as deleted: {}", whitespaceEvent);

        // 5. Delete the key (simulates value becoming null/absent)
        LOG.info("handlesEmptyOrBlankJsonValues: Deleting the key explicitly");
        testKvClient.deleteKey(fullWatchClusterKey);
        WatchCallbackResult deletedKeyEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(deletedKeyEvent, "Should receive event for explicit key deletion");
        assertTrue(deletedKeyEvent.deleted(), "Explicit key deletion should be treated as deleted. Event: " + deletedKeyEvent);
        LOG.info("handlesEmptyOrBlankJsonValues: Explicit key deletion treated as deleted: {}", deletedKeyEvent);
    }

    @Test
    @DisplayName("connect and close methods should be idempotent")
    void connectAndClose_shouldBeIdempotent() throws Exception {
        LOG.info("idempotency_test: Starting connect/close idempotency test");

        // 1. Test multiple connect() calls
        LOG.info("idempotency_test: Testing multiple connect() calls");
        configFetcher.connect(); // First call (already called in @BeforeEach, but good to be explicit)
        assertTrue(configFetcher.connected.get(), "Should be connected after first explicit connect");
        KeyValueClient firstKvClient = configFetcher.kvClient;
        assertNotNull(firstKvClient, "kvClient should be set after first connect");

        configFetcher.connect(); // Second call
        assertTrue(configFetcher.connected.get(), "Should remain connected after second connect");
        assertSame(firstKvClient, configFetcher.kvClient, "kvClient instance should not change on redundant connect");
        LOG.info("idempotency_test: Multiple connect() calls handled correctly.");

        // 2. Test multiple close() calls
        LOG.info("idempotency_test: Testing multiple close() calls");
        configFetcher.close(); // First close
        assertFalse(configFetcher.connected.get(), "Should be disconnected after first close");
        assertNull(configFetcher.kvClient, "kvClient should be null after first close");

        // Verify no exceptions on second close
        assertDoesNotThrow(() -> {
            configFetcher.close(); // Second close
        }, "Second close() call should not throw an exception");
        assertFalse(configFetcher.connected.get(), "Should remain disconnected after second close");
        assertNull(configFetcher.kvClient, "kvClient should remain null after second close");
        LOG.info("idempotency_test: Multiple close() calls handled correctly.");

        // 3. Test connect() after multiple close() calls
        LOG.info("idempotency_test: Testing connect() after multiple close() calls");
        configFetcher.connect(); // Connect again
        assertTrue(configFetcher.connected.get(), "Should be connected after connect() post-closes");
        assertNotNull(configFetcher.kvClient, "kvClient should be re-initialized after connect() post-closes");
        LOG.info("idempotency_test: connect() after multiple closes handled correctly.");

        // 4. Test close() when never explicitly connected (beyond @BeforeEach)
        // Create a new instance that hasn't had its connect() method explicitly called by the test logic yet
        // (though @BeforeEach in the main test class calls it).
        // For a truly isolated test of this, you might need a helper to get a "fresh" instance
        // or accept that @BeforeEach's connect() is the baseline.
        // Given @BeforeEach, this part is somewhat covered by the multiple close() above.
        // However, if we want to be super explicit about a "never connected then closed":
        KiwiprojectConsulConfigFetcher freshFetcher = new KiwiprojectConsulConfigFetcher(
                objectMapper,
                "localhost", 0, // Port doesn't matter as we won't connect
                clusterConfigKeyPrefixWithSlash,
                schemaVersionsKeyPrefixWithSlash,
                appWatchSeconds,
                directConsulClientForTestSetup // Use the real one to avoid null issues if it tried to connect
        );
        // Don't call freshFetcher.connect()
        LOG.info("idempotency_test: Testing close() on a fetcher where connect() was not explicitly called by test logic (beyond its own @BeforeEach if applicable)");
        assertDoesNotThrow(() -> {
            freshFetcher.close();
        }, "close() on a 'fresh' (or minimally connected) fetcher should not throw");
        assertFalse(freshFetcher.connected.get(), "Fresh fetcher should be marked not connected after close");
        LOG.info("idempotency_test: close() on 'fresh' fetcher handled correctly.");
    }

    @Test
    @DisplayName("watchClusterConfig - when kvClient is null, ensureConnected re-initializes and watch succeeds")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void watchClusterConfig_whenKvClientIsNull_reconnectsAndWatchesSuccessfully() throws Exception {
        BlockingQueue<WatchCallbackResult> updates = new ArrayBlockingQueue<>(5);
        Consumer<WatchCallbackResult> handler = updates::offer;

        PipelineClusterConfig config1 = createDummyClusterConfig(testClusterForWatch);
        config1 = new PipelineClusterConfig(config1.clusterName(), config1.pipelineGraphConfig(),
                config1.pipelineModuleMap(), Set.of("topicKvNullTest"), config1.allowedGrpcServices());

        // Initial state: connected via @BeforeEach
        assertTrue(configFetcher.connected.get(), "Should be connected initially from @BeforeEach");
        assertNotNull(configFetcher.kvClient, "kvClient should be non-null initially");

        // 1. Artificially nullify kvClient to simulate an unexpected state
        //    (but consulClient remains valid)
        LOG.info("kvClient_null_test: Artificially setting kvClient to null");
        configFetcher.kvClient = null;
        configFetcher.connected.set(false); // Also mark as not connected to ensure connect() logic runs fully

        // 2. Call watchClusterConfig. It should trigger ensureConnected -> connect -> re-init kvClient
        LOG.info("kvClient_null_test: Calling watchClusterConfig for {}", fullWatchClusterKey);
        configFetcher.watchClusterConfig(testClusterForWatch, handler);

        // Verify that connect() was indeed called and re-initialized kvClient
        assertTrue(configFetcher.connected.get(), "Should be re-connected after watchClusterConfig");
        assertNotNull(configFetcher.kvClient, "kvClient should be re-initialized by watchClusterConfig");
        assertTrue(configFetcher.watcherStarted.get(), "Watcher should be started");
        assertNotNull(configFetcher.clusterConfigCache, "KVCache should be created");
        LOG.info("kvClient_null_test: Watch started successfully after kvClient was null.");

        // 3. Consume initial event (likely deleted)
        WatchCallbackResult initialEvent = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(initialEvent, "Should receive an initial event after watch setup");
        LOG.info("kvClient_null_test: Initial event: {}", initialEvent);

        // 4. Seed data and verify the watch receives it
        seedConsulKv(fullWatchClusterKey, config1);
        WatchCallbackResult result1 = updates.poll(appWatchSeconds + 5, TimeUnit.SECONDS);
        assertNotNull(result1, "Handler should receive config1 even after kvClient was initially null");
        assertTrue(result1.config().isPresent(), "Config should be present in the received update");
        assertEquals(config1, result1.config().get());
        LOG.info("kvClient_null_test: Handler received config1: {}", result1);
    }
    // The watchClusterConfig_handlesMalformedJsonUpdate test is now effectively merged into
    // the comprehensive watchClusterConfig_receivesAllStates test.
    // If kept separate, it would be a more focused version of step 4 in the above test.
}