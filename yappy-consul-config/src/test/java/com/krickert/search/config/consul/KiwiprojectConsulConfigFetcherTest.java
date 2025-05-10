package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.schema.registry.model.SchemaType;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.cache.KVCache;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.kiwiproject.consul.cache.KVCache.newCache;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KiwiprojectConsulConfigFetcherTest {

    // Use lenient mocking to avoid "unnecessary stubbings" errors
    @BeforeEach
    void setUpMockitoExtension() {
        //noinspection resource
        MockitoAnnotations.openMocks(this);
    }

    private static final String TEST_CLUSTER_NAME = "test-cluster";
    private static final String TEST_SCHEMA_SUBJECT = "test-schema";
    private static final int TEST_SCHEMA_VERSION = 1;
    private static final String DEFAULT_CLUSTER_CONFIG_KEY_PREFIX = "pipeline-configs/clusters/";
    private static final String DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX = "pipeline-configs/schemas/versions/";

    @Mock
    private ObjectMapper mockObjectMapper;

    @Mock
    private Consul mockConsul;

    @Mock
    private KeyValueClient mockKeyValueClient;

    @Mock
    private KVCache mockKVCache;

    // We'll use a different approach for capturing the listener

    private KiwiprojectConsulConfigFetcher consulConfigFetcher;

    @BeforeEach
    void setUp() {
        consulConfigFetcher = new KiwiprojectConsulConfigFetcher(
                mockObjectMapper,
                "localhost",
                8500,
                "",
                DEFAULT_CLUSTER_CONFIG_KEY_PREFIX,
                DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX,
                30
        );
    }

    @Test
    void testGetClusterConfigKey() throws Exception {
        // Using reflection to test private method
        java.lang.reflect.Method method = KiwiprojectConsulConfigFetcher.class.getDeclaredMethod("getClusterConfigKey", String.class);
        method.setAccessible(true);
        String key = (String) method.invoke(consulConfigFetcher, TEST_CLUSTER_NAME);
        assertEquals(DEFAULT_CLUSTER_CONFIG_KEY_PREFIX + TEST_CLUSTER_NAME, key);
    }

    @Test
    void testGetSchemaVersionKey() throws Exception {
        // Using reflection to test private method
        java.lang.reflect.Method method = KiwiprojectConsulConfigFetcher.class.getDeclaredMethod("getSchemaVersionKey", String.class, int.class);
        method.setAccessible(true);
        String key = (String) method.invoke(consulConfigFetcher, TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION);
        assertEquals(DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX + TEST_SCHEMA_SUBJECT + "/" + TEST_SCHEMA_VERSION, key);
    }

    @Test
    void testConnect() throws Exception {
        // We need to mock the Consul.Builder and its methods
        Consul.Builder mockBuilder = mock(Consul.Builder.class);

        // Use MockedStatic for Consul.builder static method
        try (MockedStatic<Consul> mockedConsul = mockStatic(Consul.class)) {
            mockedConsul.when(Consul::builder).thenReturn(mockBuilder);

            // Setup the builder chain
            when(mockBuilder.withHostAndPort(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockConsul);

            // Setup the keyValueClient
            when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);

            // Execute
            consulConfigFetcher.connect();

            // Verify
            mockedConsul.verify(Consul::builder);
            verify(mockBuilder).withHostAndPort(any());
            verify(mockBuilder).build();
            verify(mockConsul).keyValueClient();

            // Verify that the fields were set correctly
            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean connected = (java.util.concurrent.atomic.AtomicBoolean) connectedField.get(consulConfigFetcher);
            assertTrue(connected.get());

            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            assertEquals(mockConsul, consulClientField.get(consulConfigFetcher));

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            assertEquals(mockKeyValueClient, kvClientField.get(consulConfigFetcher));
        }
    }

    @Test
    void testFetchPipelineClusterConfig_Success() throws JsonProcessingException {
        // Setup
        String clusterConfigKey = DEFAULT_CLUSTER_CONFIG_KEY_PREFIX + TEST_CLUSTER_NAME;
        String clusterConfigJson = "{\"clusterName\":\"test-cluster\",\"allowedKafkaTopics\":[\"topic1\",\"topic2\"]}";
        PipelineClusterConfig expectedConfig = new PipelineClusterConfig(
                TEST_CLUSTER_NAME, 
                null, 
                null, 
                Set.of("topic1", "topic2"), 
                Collections.emptySet()
        );

        // Setup mocks with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);
        lenient().when(mockKeyValueClient.getValueAsString(clusterConfigKey)).thenReturn(Optional.of(clusterConfigJson));
        lenient().when(mockObjectMapper.readValue(clusterConfigJson, PipelineClusterConfig.class)).thenReturn(expectedConfig);

        // Use reflection to set the fields
        try {
            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            consulClientField.set(consulConfigFetcher, mockConsul);

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            kvClientField.set(consulConfigFetcher, mockKeyValueClient);

            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
        } catch (Exception e) {
            fail("Failed to set fields: " + e.getMessage());
        }

        // Execute
        Optional<PipelineClusterConfig> result = consulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME);

        // Verify
        assertTrue(result.isPresent());
        assertEquals(expectedConfig, result.get());
        verify(mockKeyValueClient).getValueAsString(clusterConfigKey);
        verify(mockObjectMapper).readValue(clusterConfigJson, PipelineClusterConfig.class);
    }

    @Test
    void testFetchPipelineClusterConfig_NotFound() throws Exception {
        // Setup
        String clusterConfigKey = DEFAULT_CLUSTER_CONFIG_KEY_PREFIX + TEST_CLUSTER_NAME;

        // Setup mocks with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);
        lenient().when(mockKeyValueClient.getValueAsString(clusterConfigKey)).thenReturn(Optional.empty());

        // Use reflection to set the fields
        try {
            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            consulClientField.set(consulConfigFetcher, mockConsul);

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            kvClientField.set(consulConfigFetcher, mockKeyValueClient);

            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
        } catch (Exception e) {
            fail("Failed to set fields: " + e.getMessage());
        }

        // Execute
        Optional<PipelineClusterConfig> result = consulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME);

        // Verify
        assertFalse(result.isPresent());
        verify(mockKeyValueClient).getValueAsString(clusterConfigKey);
        verify(mockObjectMapper, never()).readValue(anyString(), eq(PipelineClusterConfig.class));
    }

    @Test
    void testFetchPipelineClusterConfig_JsonProcessingException() throws JsonProcessingException {
        // Setup
        String clusterConfigKey = DEFAULT_CLUSTER_CONFIG_KEY_PREFIX + TEST_CLUSTER_NAME;
        String invalidJson = "{invalid-json}";

        // Setup mocks with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);
        lenient().when(mockKeyValueClient.getValueAsString(clusterConfigKey)).thenReturn(Optional.of(invalidJson));
        lenient().when(mockObjectMapper.readValue(invalidJson, PipelineClusterConfig.class))
                .thenThrow(new JsonProcessingException("Invalid JSON") {});

        // Use reflection to set the fields
        try {
            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            consulClientField.set(consulConfigFetcher, mockConsul);

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            kvClientField.set(consulConfigFetcher, mockKeyValueClient);

            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
        } catch (Exception e) {
            fail("Failed to set fields: " + e.getMessage());
        }

        // Execute
        Optional<PipelineClusterConfig> result = consulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME);

        // Verify
        assertFalse(result.isPresent());
        verify(mockKeyValueClient).getValueAsString(clusterConfigKey);
        verify(mockObjectMapper).readValue(invalidJson, PipelineClusterConfig.class);
    }

    @Test
    void testFetchSchemaVersionData_Success() throws JsonProcessingException {
        // Setup
        String schemaVersionKey = DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX + TEST_SCHEMA_SUBJECT + "/" + TEST_SCHEMA_VERSION;
        String schemaJson = "{\"subject\":\"test-schema\",\"version\":1,\"schemaContent\":\"{\\\"type\\\":\\\"object\\\"}\",\"createdAt\":\"2023-01-01T00:00:00.000Z\"}";
        SchemaVersionData expectedData = new SchemaVersionData(
                null,
                TEST_SCHEMA_SUBJECT,
                TEST_SCHEMA_VERSION,
                "{\"type\":\"object\"}",
                SchemaType.JSON_SCHEMA,
                null,
                Instant.parse("2023-01-01T00:00:00.000Z"),
                null
        );

        // Setup mocks with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);
        lenient().when(mockKeyValueClient.getValueAsString(schemaVersionKey)).thenReturn(Optional.of(schemaJson));
        lenient().when(mockObjectMapper.readValue(schemaJson, SchemaVersionData.class)).thenReturn(expectedData);

        // Use reflection to set the fields
        try {
            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            consulClientField.set(consulConfigFetcher, mockConsul);

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            kvClientField.set(consulConfigFetcher, mockKeyValueClient);

            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
        } catch (Exception e) {
            fail("Failed to set fields: " + e.getMessage());
        }

        // Execute
        Optional<SchemaVersionData> result = consulConfigFetcher.fetchSchemaVersionData(TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION);

        // Verify
        assertTrue(result.isPresent());
        assertEquals(expectedData, result.get());
        verify(mockKeyValueClient).getValueAsString(schemaVersionKey);
        verify(mockObjectMapper).readValue(schemaJson, SchemaVersionData.class);
    }

    @Test
    void testFetchSchemaVersionData_NotFound() throws Exception {
        // Setup
        String schemaVersionKey = DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX + TEST_SCHEMA_SUBJECT + "/" + TEST_SCHEMA_VERSION;

        // Setup mocks with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);
        lenient().when(mockKeyValueClient.getValueAsString(schemaVersionKey)).thenReturn(Optional.empty());

        // Use reflection to set the fields
        try {
            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            consulClientField.set(consulConfigFetcher, mockConsul);

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            kvClientField.set(consulConfigFetcher, mockKeyValueClient);

            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
        } catch (Exception e) {
            fail("Failed to set fields: " + e.getMessage());
        }

        // Execute
        Optional<SchemaVersionData> result = consulConfigFetcher.fetchSchemaVersionData(TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION);

        // Verify
        assertFalse(result.isPresent());
        verify(mockKeyValueClient).getValueAsString(schemaVersionKey);
        verify(mockObjectMapper, never()).readValue(anyString(), eq(SchemaVersionData.class));
    }

    @Test
    void testFetchSchemaVersionData_JsonProcessingException() throws JsonProcessingException {
        // Setup
        String schemaVersionKey = DEFAULT_SCHEMA_VERSIONS_KEY_PREFIX + TEST_SCHEMA_SUBJECT + "/" + TEST_SCHEMA_VERSION;
        String invalidJson = "{invalid-json}";

        // Setup mocks with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);
        lenient().when(mockKeyValueClient.getValueAsString(schemaVersionKey)).thenReturn(Optional.of(invalidJson));
        lenient().when(mockObjectMapper.readValue(invalidJson, SchemaVersionData.class))
                .thenThrow(new JsonProcessingException("Invalid JSON") {});

        // Use reflection to set the fields
        try {
            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            consulClientField.set(consulConfigFetcher, mockConsul);

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            kvClientField.set(consulConfigFetcher, mockKeyValueClient);

            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
        } catch (Exception e) {
            fail("Failed to set fields: " + e.getMessage());
        }

        // Execute
        Optional<SchemaVersionData> result = consulConfigFetcher.fetchSchemaVersionData(TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION);

        // Verify
        assertFalse(result.isPresent());
        verify(mockKeyValueClient).getValueAsString(schemaVersionKey);
        verify(mockObjectMapper).readValue(invalidJson, SchemaVersionData.class);
    }

    @Test
    void testWatchClusterConfig_Success() throws Exception {
        // Since we can't easily mock the KVCache.addListener method, we'll test the watchClusterConfig method
        // by verifying that it calls KVCache.newCache with the correct parameters and starts the cache.
        // We'll skip testing the listener functionality directly.

        // Setup
        String clusterConfigKey = DEFAULT_CLUSTER_CONFIG_KEY_PREFIX + TEST_CLUSTER_NAME;
        var mockUpdateHandler = mock(Consumer.class);

        // Setup mocks with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);

        // Use MockedStatic for KVCache.newCache static method
        try (MockedStatic<KVCache> mockedKVCache = mockStatic(KVCache.class)) {
            mockedKVCache.when(() -> newCache(eq(mockKeyValueClient), eq(clusterConfigKey), eq(30)))
                    .thenReturn(mockKVCache);

            doNothing().when(mockKVCache).start();

            // Use reflection to set the fields
            try {
                java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
                consulClientField.setAccessible(true);
                consulClientField.set(consulConfigFetcher, mockConsul);

                java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
                kvClientField.setAccessible(true);
                kvClientField.set(consulConfigFetcher, mockKeyValueClient);

                java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
                connectedField.setAccessible(true);
                connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
            } catch (Exception e) {
                fail("Failed to set fields: " + e.getMessage());
            }

            // Execute
            //noinspection unchecked
            consulConfigFetcher.watchClusterConfig(TEST_CLUSTER_NAME, mockUpdateHandler);

            // Verify KVCache setup
            mockedKVCache.verify(() -> newCache(eq(mockKeyValueClient), eq(clusterConfigKey), eq(30)));
            verify(mockKVCache).start();

            // Verify that the clusterConfigCache field was set
            java.lang.reflect.Field clusterConfigCacheField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("clusterConfigCache");
            clusterConfigCacheField.setAccessible(true);
            assertEquals(mockKVCache, clusterConfigCacheField.get(consulConfigFetcher));

            // Verify that the watcherStarted flag was set to true
            java.lang.reflect.Field watcherStartedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("watcherStarted");
            watcherStartedField.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean watcherStarted = (java.util.concurrent.atomic.AtomicBoolean) watcherStartedField.get(consulConfigFetcher);
            assertTrue(watcherStarted.get());
        }
    }

    @Test
    void testClose() throws Exception {
        // Setup with lenient to avoid "unnecessary stubbings" errors
        lenient().when(mockConsul.keyValueClient()).thenReturn(mockKeyValueClient);

        // Use reflection to set the fields
        try {
            java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
            consulClientField.setAccessible(true);
            consulClientField.set(consulConfigFetcher, mockConsul);

            java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
            kvClientField.setAccessible(true);
            kvClientField.set(consulConfigFetcher, mockKeyValueClient);

            java.lang.reflect.Field clusterConfigCacheField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("clusterConfigCache");
            clusterConfigCacheField.setAccessible(true);
            clusterConfigCacheField.set(consulConfigFetcher, mockKVCache);

            java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));

            java.lang.reflect.Field watcherStartedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("watcherStarted");
            watcherStartedField.setAccessible(true);
            watcherStartedField.set(consulConfigFetcher, new java.util.concurrent.atomic.AtomicBoolean(true));
        } catch (Exception e) {
            fail("Failed to set fields: " + e.getMessage());
        }

        // Execute
        consulConfigFetcher.close();

        // Verify
        verify(mockKVCache).stop();

        // Verify that the fields were reset
        java.lang.reflect.Field connectedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean connected = (java.util.concurrent.atomic.AtomicBoolean) connectedField.get(consulConfigFetcher);
        assertFalse(connected.get());

        java.lang.reflect.Field watcherStartedField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("watcherStarted");
        watcherStartedField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean watcherStarted = (java.util.concurrent.atomic.AtomicBoolean) watcherStartedField.get(consulConfigFetcher);
        assertFalse(watcherStarted.get());

        java.lang.reflect.Field clusterConfigCacheField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("clusterConfigCache");
        clusterConfigCacheField.setAccessible(true);
        assertNull(clusterConfigCacheField.get(consulConfigFetcher));

        java.lang.reflect.Field kvClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("kvClient");
        kvClientField.setAccessible(true);
        assertNull(kvClientField.get(consulConfigFetcher));

        java.lang.reflect.Field consulClientField = KiwiprojectConsulConfigFetcher.class.getDeclaredField("consulClient");
        consulClientField.setAccessible(true);
        assertNull(consulClientField.get(consulConfigFetcher));
    }
}
