// File: src/test/java/com/krickert/search/config/consul/KiwiprojectConsulConfigFetcherTest.java
package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.schema.registry.model.SchemaCompatibility;
import com.krickert.search.config.schema.registry.model.SchemaType;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.cache.ConsulCache; // Listener interface
import org.kiwiproject.consul.cache.KVCache;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KiwiprojectConsulConfigFetcherTest {

    private static final String TEST_CLUSTER_NAME = "test-cluster";
    private static final String TEST_SCHEMA_SUBJECT = "test-schema";
    private static final int TEST_SCHEMA_VERSION = 1;
    private static final String DEFAULT_CLUSTER_CONFIG_KEY_PREFIX_WITH_SLASH = "pipeline-configs/clusters/";
    private static final String DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX_WITH_SLASH = "pipeline-configs/schemas/versions/";
    private static final int TEST_WATCH_SECONDS = 5;

    @Mock
    private ObjectMapper mockObjectMapper;
    @Mock
    private Consul mockConsulClient;
    @Mock
    private KeyValueClient mockKeyValueClient;
    @Mock
    private KVCache mockKVCacheInstance;

    @Captor
    private ArgumentCaptor<ConsulCache.Listener<String, org.kiwiproject.consul.model.kv.Value>> kvCacheListenerCaptor;
    @Captor
    private ArgumentCaptor<WatchCallbackResult> watchCallbackResultCaptor;

    private KiwiprojectConsulConfigFetcher consulConfigFetcher;

    @BeforeEach
    void setUp() {
        consulConfigFetcher = new KiwiprojectConsulConfigFetcher(
                mockObjectMapper,
                "localhost", 8500,
                DEFAULT_CLUSTER_CONFIG_KEY_PREFIX_WITH_SLASH,
                DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX_WITH_SLASH,
                TEST_WATCH_SECONDS,
                mockConsulClient // Pass the mock Consul client
        );
        // This lenient stubbing is important as connect() might be called multiple times
        // by ensureConnected() or directly in tests that don't focus on connect() itself.
        lenient().when(mockConsulClient.keyValueClient()).thenReturn(mockKeyValueClient);
    }

    // Helper to simulate that connect() has been successfully called
    private void simulateConnectedState() {
        // Directly call connect() which uses the mockConsulClient
        consulConfigFetcher.connect();
        // Assertions now use direct field access due to package-private visibility
        assertTrue(consulConfigFetcher.connected.get(), "Fetcher should be marked as connected.");
        assertSame(mockKeyValueClient, consulConfigFetcher.kvClient, "kvClient should be set from mockConsulClient.");
    }

    @Test
    void getClusterConfigKey_returnsCorrectPath() {
        // Access package-private method directly for testing
        assertEquals(DEFAULT_CLUSTER_CONFIG_KEY_PREFIX_WITH_SLASH + TEST_CLUSTER_NAME,
                consulConfigFetcher.getClusterConfigKey(TEST_CLUSTER_NAME));
    }

    @Test
    void getSchemaVersionKey_returnsCorrectPath() {
        // Access package-private method directly for testing
        assertEquals(DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX_WITH_SLASH + TEST_SCHEMA_SUBJECT + "/" + TEST_SCHEMA_VERSION,
                consulConfigFetcher.getSchemaVersionKey(TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION));
    }

    @Test
    void connect_success_setsUpClientsAndFlags() {
        consulConfigFetcher.connect(); // Act
        verify(mockConsulClient).keyValueClient();
        assertTrue(consulConfigFetcher.connected.get());
        assertSame(mockKeyValueClient, consulConfigFetcher.kvClient);
        assertSame(mockConsulClient, consulConfigFetcher.consulClient);
    }

    @Test
    void connect_whenConsulClientIsNull_throwsIllegalStateException() {
        KiwiprojectConsulConfigFetcher fetcherWithNullClient = new KiwiprojectConsulConfigFetcher(
                mockObjectMapper, "h", 0, "p/", "s/", 0, null
        );
        assertThrows(IllegalStateException.class, fetcherWithNullClient::connect);
    }

    @Test
    void connect_whenGetKeyValueClientThrows_throwsIllegalStateExceptionAndSetsNotConnected() {
        when(mockConsulClient.keyValueClient()).thenThrow(new RuntimeException("KV client init failed"));
        assertThrows(IllegalStateException.class, () -> consulConfigFetcher.connect());
        assertFalse(consulConfigFetcher.connected.get());
    }

    @Test
    void fetchPipelineClusterConfig_success() throws Exception {
        simulateConnectedState();
        String clusterConfigKey = consulConfigFetcher.getClusterConfigKey(TEST_CLUSTER_NAME);
        String clusterConfigJson = "{\"clusterName\":\"" + TEST_CLUSTER_NAME + "\"}";
        PipelineClusterConfig expectedConfig = new PipelineClusterConfig(TEST_CLUSTER_NAME, null, null, Collections.emptySet(), Collections.emptySet());

        when(mockKeyValueClient.getValueAsString(clusterConfigKey)).thenReturn(Optional.of(clusterConfigJson));
        when(mockObjectMapper.readValue(clusterConfigJson, PipelineClusterConfig.class)).thenReturn(expectedConfig);

        Optional<PipelineClusterConfig> result = consulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        assertTrue(result.isPresent());
        assertEquals(expectedConfig, result.get());
    }

    @Test
    void fetchPipelineClusterConfig_notFound_returnsEmpty() throws JsonProcessingException {
        simulateConnectedState();
        String clusterConfigKey = consulConfigFetcher.getClusterConfigKey(TEST_CLUSTER_NAME);
        when(mockKeyValueClient.getValueAsString(clusterConfigKey)).thenReturn(Optional.empty());
        Optional<PipelineClusterConfig> result = consulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        assertFalse(result.isPresent());
        verify(mockObjectMapper, never()).readValue(anyString(), eq(PipelineClusterConfig.class));
    }

    @Test
    void fetchPipelineClusterConfig_jsonProcessingException_returnsEmpty() throws Exception {
        simulateConnectedState();
        String clusterConfigKey = consulConfigFetcher.getClusterConfigKey(TEST_CLUSTER_NAME);
        String invalidJson = "{invalid-json}";
        when(mockKeyValueClient.getValueAsString(clusterConfigKey)).thenReturn(Optional.of(invalidJson));
        when(mockObjectMapper.readValue(invalidJson, PipelineClusterConfig.class))
                .thenThrow(new JsonProcessingException("Invalid JSON Test") {});
        Optional<PipelineClusterConfig> result = consulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        assertFalse(result.isPresent()); // Verify Optional.empty() is returned
        verify(mockObjectMapper).readValue(invalidJson, PipelineClusterConfig.class); // Verify attempt
    }

    @Test
    void fetchSchemaVersionData_success() throws Exception {
        simulateConnectedState();
        String schemaKey = consulConfigFetcher.getSchemaVersionKey(TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION);
        String schemaJson = "{\"schemaContent\":\"{}\"}";
        SchemaVersionData expectedData = new SchemaVersionData(1L, TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION, "{}", SchemaType.JSON_SCHEMA, null, Instant.now(), null);
        when(mockKeyValueClient.getValueAsString(schemaKey)).thenReturn(Optional.of(schemaJson));
        when(mockObjectMapper.readValue(schemaJson, SchemaVersionData.class)).thenReturn(expectedData);
        Optional<SchemaVersionData> result = consulConfigFetcher.fetchSchemaVersionData(TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION);
        assertTrue(result.isPresent());
        assertEquals(expectedData, result.get());
    }

    @Test
    void watchClusterConfig_setupAndStart_correctlyInitializesKVCache() throws Exception {
        simulateConnectedState();
        String clusterConfigKey = consulConfigFetcher.getClusterConfigKey(TEST_CLUSTER_NAME);
        @SuppressWarnings("unchecked")
        Consumer<WatchCallbackResult> mockUpdateHandler = mock(Consumer.class);

        try (MockedStatic<KVCache> mockedStaticKVCache = mockStatic(KVCache.class)) {
            // We expect the simpler newCache method that takes watchSeconds directly
            mockedStaticKVCache.when(() -> KVCache.newCache(
                    eq(mockKeyValueClient),
                    eq(clusterConfigKey),
                    eq(TEST_WATCH_SECONDS)
            )).thenReturn(mockKVCacheInstance);
            doNothing().when(mockKVCacheInstance).start();

            consulConfigFetcher.watchClusterConfig(TEST_CLUSTER_NAME, mockUpdateHandler);

            mockedStaticKVCache.verify(() -> KVCache.newCache(
                    eq(mockKeyValueClient), eq(clusterConfigKey), eq(TEST_WATCH_SECONDS))
            );
            verify(mockKVCacheInstance).addListener(kvCacheListenerCaptor.capture());
            verify(mockKVCacheInstance).start();
            assertTrue(consulConfigFetcher.watcherStarted.get());
            assertSame(mockKVCacheInstance, consulConfigFetcher.clusterConfigCache);
        }
    }

    @Test
    void watchClusterConfig_listener_receivesUpdateAndCallsHandlerWithSuccess() throws Exception {
        simulateConnectedState();
        String clusterConfigKey = consulConfigFetcher.getClusterConfigKey(TEST_CLUSTER_NAME);
        @SuppressWarnings("unchecked")
        Consumer<WatchCallbackResult> mockUpdateHandler = mock(Consumer.class);
        PipelineClusterConfig testConfig = new PipelineClusterConfig(TEST_CLUSTER_NAME);
        String testConfigJson = "{\"clusterName\":\"" + TEST_CLUSTER_NAME + "\"}";

        org.kiwiproject.consul.model.kv.Value consulApiValue = mock(org.kiwiproject.consul.model.kv.Value.class);
        when(consulApiValue.getValueAsString()).thenReturn(Optional.of(testConfigJson));
        // KVCache listener provides a map where key is the full path of the changed item
        Map<String, org.kiwiproject.consul.model.kv.Value> newValuesMap = Collections.singletonMap(clusterConfigKey, consulApiValue);

        try (MockedStatic<KVCache> mockedStaticKVCache = mockStatic(KVCache.class)) {
            mockedStaticKVCache.when(() -> KVCache.newCache(any(), anyString(), anyInt())).thenReturn(mockKVCacheInstance);
            doNothing().when(mockKVCacheInstance).start();

            consulConfigFetcher.watchClusterConfig(TEST_CLUSTER_NAME, mockUpdateHandler);
            verify(mockKVCacheInstance).addListener(kvCacheListenerCaptor.capture());

            when(mockObjectMapper.readValue(testConfigJson, PipelineClusterConfig.class)).thenReturn(testConfig);
            // Simulate KVCache invoking the listener
            ConsulCache.Listener<String, org.kiwiproject.consul.model.kv.Value> capturedListener = kvCacheListenerCaptor.getValue();
            capturedListener.notify(newValuesMap); // CORRECTED: Call notify() which is the method on the Listener interface

            verify(mockUpdateHandler).accept(watchCallbackResultCaptor.capture());
            WatchCallbackResult result = watchCallbackResultCaptor.getValue();
            assertTrue(result.hasValidConfig());
            assertEquals(testConfig, result.config().get());
        }
    }

    @Test
    void watchClusterConfig_listener_receivesDeleteAndCallsHandlerWithDeleted() throws Exception {
        simulateConnectedState();
        @SuppressWarnings("unchecked")
        Consumer<WatchCallbackResult> mockUpdateHandler = mock(Consumer.class);
        // When a key is deleted, newValues map passed to listener by KVCache for that key will be empty
        // or the key will be absent from the map.
        Map<String, org.kiwiproject.consul.model.kv.Value> emptySnapshotForKey = Collections.emptyMap();

        try (MockedStatic<KVCache> mockedStaticKVCache = mockStatic(KVCache.class)) {
            mockedStaticKVCache.when(() -> KVCache.newCache(any(), anyString(), anyInt())).thenReturn(mockKVCacheInstance);
            doNothing().when(mockKVCacheInstance).start();

            consulConfigFetcher.watchClusterConfig(TEST_CLUSTER_NAME, mockUpdateHandler);
            verify(mockKVCacheInstance).addListener(kvCacheListenerCaptor.capture());

            ConsulCache.Listener<String, org.kiwiproject.consul.model.kv.Value> capturedListener = kvCacheListenerCaptor.getValue();
            capturedListener.notify(emptySnapshotForKey); // CORRECTED: Call notify()

            verify(mockUpdateHandler).accept(watchCallbackResultCaptor.capture());
            WatchCallbackResult result = watchCallbackResultCaptor.getValue();
            assertTrue(result.deleted());
            assertFalse(result.hasValidConfig());
            assertFalse(result.hasError());
        }
    }

    @Test
    void watchClusterConfig_listener_handlesMalformedJsonAndCallsHandlerWithFailure() throws Exception {
        simulateConnectedState();
        String clusterConfigKey = consulConfigFetcher.getClusterConfigKey(TEST_CLUSTER_NAME);
        @SuppressWarnings("unchecked")
        Consumer<WatchCallbackResult> mockUpdateHandler = mock(Consumer.class);
        String malformedJson = "{\"invalid";

        org.kiwiproject.consul.model.kv.Value consulApiValue = mock(org.kiwiproject.consul.model.kv.Value.class);
        when(consulApiValue.getValueAsString()).thenReturn(Optional.of(malformedJson));
        Map<String, org.kiwiproject.consul.model.kv.Value> newValuesMap = Collections.singletonMap(clusterConfigKey, consulApiValue);

        JsonProcessingException mockJsonException = new JsonProcessingException("Test Malformed JSON") {};
        when(mockObjectMapper.readValue(malformedJson, PipelineClusterConfig.class)).thenThrow(mockJsonException);

        try (MockedStatic<KVCache> mockedStaticKVCache = mockStatic(KVCache.class)) {
            mockedStaticKVCache.when(() -> KVCache.newCache(any(), anyString(), anyInt())).thenReturn(mockKVCacheInstance);
            doNothing().when(mockKVCacheInstance).start();

            consulConfigFetcher.watchClusterConfig(TEST_CLUSTER_NAME, mockUpdateHandler);
            verify(mockKVCacheInstance).addListener(kvCacheListenerCaptor.capture());

            ConsulCache.Listener<String, org.kiwiproject.consul.model.kv.Value> capturedListener = kvCacheListenerCaptor.getValue();
            capturedListener.notify(newValuesMap); // CORRECTED: Call notify()

            verify(mockUpdateHandler).accept(watchCallbackResultCaptor.capture());
            WatchCallbackResult result = watchCallbackResultCaptor.getValue();
            assertFalse(result.hasValidConfig());
            assertFalse(result.deleted());
            assertTrue(result.hasError());
            assertTrue(result.error().isPresent());
            assertSame(mockJsonException, result.error().get());
        }
    }

    @Test
    void close_stopsCacheAndResetsFlags() throws Exception {
        simulateConnectedState();
        // Simulate watcher was started
        consulConfigFetcher.clusterConfigCache = mockKVCacheInstance; // Direct assignment
        consulConfigFetcher.watcherStarted.set(true); // Direct assignment

        doNothing().when(mockKVCacheInstance).stop();

        consulConfigFetcher.close();

        verify(mockKVCacheInstance).stop();
        assertFalse(consulConfigFetcher.watcherStarted.get());
        assertFalse(consulConfigFetcher.connected.get());
        assertNull(consulConfigFetcher.clusterConfigCache);
        assertNull(consulConfigFetcher.kvClient);
        //we want to keep the client alive to get a chance to re-connect
        assertNotNull(consulConfigFetcher.consulClient);
    }
}