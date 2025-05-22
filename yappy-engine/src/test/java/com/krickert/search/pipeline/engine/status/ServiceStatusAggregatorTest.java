// In ServiceStatusAggregatorTest.java
package com.krickert.search.pipeline.engine.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.service.model.ServiceAggregatedStatus;
import com.krickert.search.config.service.model.ServiceOperationalStatus;
import com.krickert.search.pipeline.status.ServiceStatusAggregator;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kiwiproject.consul.model.catalog.ImmutableCatalogService;
import org.kiwiproject.consul.model.health.ImmutableNode;
import org.kiwiproject.consul.model.health.ImmutableService;
import org.kiwiproject.consul.model.health.ImmutableServiceHealth;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@MicronautTest(startApplication = false)
class ServiceStatusAggregatorTest {

    @Inject
    ServiceStatusAggregator serviceStatusAggregator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DynamicConfigurationManager mockDynamicConfigManager;
    @Inject
    ConsulBusinessOperationsService mockConsulBusinessOpsService;
    @Inject
    ConsulKvService mockConsulKvService;

    // --- Dummy objects for mocking service instance lists ---
    private org.kiwiproject.consul.model.catalog.CatalogService dummyCatalogService;
    private org.kiwiproject.consul.model.health.ServiceHealth dummyServiceHealth;

    @BeforeEach
    void setUpDummies() {
        dummyCatalogService = ImmutableCatalogService.builder()
                .node("dummy-node")
                .address("10.0.0.1")
                .serviceId("dummy-service-id")
                .serviceName("dummy-service-name")
                .serviceAddress("127.0.0.1")
                .servicePort(8080)
                .build();

        org.kiwiproject.consul.model.health.Service dummyServiceForHealth = ImmutableService.builder()
                .service("s")
                .id("id")
                .address("127.0.0.1")
                .port(8080)
                .build();
        org.kiwiproject.consul.model.health.Node dummyNodeForHealth = ImmutableNode.builder()
                .node("n")
                .address("a")
                .build();
        dummyServiceHealth = ImmutableServiceHealth.builder()
                .service(dummyServiceForHealth)
                .node(dummyNodeForHealth)
                .checks(Collections.emptyList())
                .build();
    }
    // --- End of dummy object setup ---


    @MockBean(DynamicConfigurationManager.class)
    DynamicConfigurationManager dynamicConfigurationManager() {
        return mock(DynamicConfigurationManager.class);
    }

    @MockBean(ConsulBusinessOperationsService.class)
    ConsulBusinessOperationsService consulBusinessOperationsService() {
        return mock(ConsulBusinessOperationsService.class);
    }

    @MockBean(ConsulKvService.class)
    ConsulKvService consulKvService() {
        return mock(ConsulKvService.class);
    }

    private PipelineClusterConfig createTestClusterConfig(String serviceName) {
        PipelineModuleConfiguration moduleConfig = new PipelineModuleConfiguration(serviceName, serviceName, null);
        return PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineModuleMap(new PipelineModuleMap(Map.of(serviceName, moduleConfig)))
                .build();
    }

    @Test
    void testAggregateAndStore_ActiveHealthyService() throws Exception {
        String serviceName = "echo-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyCatalogService)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyServiceHealth)));
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200); // Allow async operations

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(keyCaptor.capture(), valueCaptor.capture());

        assertEquals("yappy/status/services/" + serviceName, keyCaptor.getValue());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals(serviceName, capturedStatus.serviceName());
        assertEquals(ServiceOperationalStatus.ACTIVE_HEALTHY, capturedStatus.operationalStatus());
        assertEquals(1, capturedStatus.totalInstancesConsul());
        assertEquals(1, capturedStatus.healthyInstancesConsul());
        assertEquals("1 healthy instance(s) found.", capturedStatus.statusDetail());
    }

    @Test
    void testAggregateAndStore_NoInstancesFound() throws Exception {
        String serviceName = "no-instance-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals(serviceName, capturedStatus.serviceName());
        assertEquals(ServiceOperationalStatus.AWAITING_HEALTHY_REGISTRATION, capturedStatus.operationalStatus());
        assertEquals(0, capturedStatus.totalInstancesConsul());
        assertEquals(0, capturedStatus.healthyInstancesConsul());
        assertEquals("No instances registered in Consul.", capturedStatus.statusDetail());
    }

    @Test
    void testAggregateAndStore_InstancesFoundButNoneHealthy() throws Exception {
        String serviceName = "unhealthy-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyCatalogService, dummyCatalogService))); // 2 total instances
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(Collections.emptyList())); // 0 healthy
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals(serviceName, capturedStatus.serviceName());
        assertEquals(ServiceOperationalStatus.UNAVAILABLE, capturedStatus.operationalStatus());
        assertEquals(2, capturedStatus.totalInstancesConsul());
        assertEquals(0, capturedStatus.healthyInstancesConsul());
        assertEquals("2 instance(s) found, but none are healthy.", capturedStatus.statusDetail());
    }

    @Test
    void testAggregateAndStore_ErrorFromGetServiceInstances() throws InterruptedException {
        String serviceName = "error-service-total";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);
        RuntimeException simulatedError = new RuntimeException("Consul down for getServiceInstances");

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName)).thenReturn(Mono.error(simulatedError));
        // getHealthyServiceInstances might still be called or might not, depending on error handling in zip
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList())); // Default for this path

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200); // Allow async processing

        // In this case, the flatMap for this service will error out.
        // The overall Flux.fromIterable(...).flatMap(...).subscribe() will log the error.
        // No status should be written to KV for this specific service.
        verify(mockConsulKvService, never()).putValue(eq("yappy/status/services/" + serviceName), anyString());
        // We can also verify that the error was logged by the aggregator's global error handler if we had a log appender.
    }

    @Test
    void testAggregateAndStore_ErrorFromGetHealthyServiceInstances() throws InterruptedException {
        String serviceName = "error-service-healthy";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);
        RuntimeException simulatedError = new RuntimeException("Consul down for getHealthyServiceInstances");

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName)).thenReturn(Mono.just(List.of(dummyCatalogService)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)).thenReturn(Mono.error(simulatedError));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        verify(mockConsulKvService, never()).putValue(eq("yappy/status/services/" + serviceName), anyString());
    }

    @Test
    void testAggregateAndStore_ErrorFromPutValue() throws Exception {
        String serviceName = "error-kv-put";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);
        RuntimeException simulatedError = new RuntimeException("KV store unavailable");

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulKvService.putValue(eq("yappy/status/services/" + serviceName), anyString()))
                .thenReturn(Mono.error(simulatedError)); // Simulate error on put

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        // putValue was called, but the error is handled by the aggregator's global error handler.
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), anyString());
        // Further assertions could involve checking logs if a test appender is set up.
    }

    @Test
    void testAggregateAndStore_PutValueReturnsFalse() throws Exception {
        String serviceName = "kv-put-returns-false";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulKvService.putValue(eq("yappy/status/services/" + serviceName), anyString()))
                .thenReturn(Mono.just(false)); // Simulate putValue returning false

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), anyString());
        // The log "Failed to store status for service..." should appear.
    }

    @Test
    void testAggregateAndStore_ObjectMapperThrowsException() throws Exception {
        String serviceName = "json-error-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        // Use a spy for ObjectMapper to make it throw an exception for a specific call
        ObjectMapper spiedObjectMapper = spy(this.objectMapper);
        JsonProcessingException simulatedJsonError = new JsonProcessingException("Simulated JSON error") {};

        // Re-create ServiceStatusAggregator with the spied ObjectMapper
        // This is a bit more involved as we need to ensure this spied instance is used.
        // For simplicity in this example, we'll assume the @Inject ObjectMapper is the one used.
        // A more robust way would be to pass it in constructor or use a @Replaces for ObjectMapper in test.
        // For now, let's assume we can make the injected one throw.
        // This requires the ObjectMapper bean to be replaceable or the test to control its creation.
        // A simpler way for this specific test:
        ServiceStatusAggregator localAggregator = new ServiceStatusAggregator(
                mockDynamicConfigManager,
                mockConsulBusinessOpsService,
                mockConsulKvService,
                spiedObjectMapper // Use the spy
        );

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        // No need to mock putValue if serialization fails first

        // Make writeValueAsString throw for any ServiceAggregatedStatus object
        doThrow(simulatedJsonError).when(spiedObjectMapper).writeValueAsString(any(ServiceAggregatedStatus.class));

        localAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        // KV put should not be called if serialization fails
        verify(mockConsulKvService, never()).putValue(anyString(), anyString());
        // The error "Failed to serialize ServiceAggregatedStatus..." should be logged.
    }

    // In ServiceStatusAggregatorTest.java
// ... (existing imports and setup)

    // --- NEW TESTS for enhanced ServiceStatusAggregator ---

    @Test
    void testAggregateAndStore_ServiceIsDegradedDueToStaleConfig() throws Exception {
        String serviceName = "stale-config-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName); // Your existing helper

        // Mock DynamicConfigurationManager
        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(true); // Key mock for this test
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v1.0.0-stale"));

        // Mock Consul - service is healthy otherwise
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyCatalogService))); // dummyCatalogService from @BeforeEach
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyServiceHealth))); // dummyServiceHealth from @BeforeEach
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200); // Allow async operations

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals(serviceName, capturedStatus.serviceName());
        assertEquals(ServiceOperationalStatus.DEGRADED_OPERATIONAL, capturedStatus.operationalStatus());
        assertTrue(capturedStatus.isUsingStaleClusterConfig());
        assertEquals("v1.0.0-stale", capturedStatus.activeClusterConfigVersion());
        assertTrue(capturedStatus.statusDetail().contains("STALE cluster configuration"));
        assertEquals(1, capturedStatus.healthyInstancesConsul());
    }

    @Test
    void testAggregateAndStore_PopulatesActiveConfigVersion() throws Exception {
        String serviceName = "versioned-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(false);
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v2.1.0-current"));

        when(mockConsulBusinessOpsService.getServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)).thenReturn(Mono.just(Collections.emptyList()));
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals("v2.1.0-current", capturedStatus.activeClusterConfigVersion());
        assertFalse(capturedStatus.isUsingStaleClusterConfig());
    }

    @Test
    void testAggregateAndStore_PopulatesReportedModuleConfigDigest_SingleInstance() throws Exception {
        String serviceName = "digest-service-single";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);
        String expectedDigest = "abcdef12345";

        org.kiwiproject.consul.model.catalog.CatalogService serviceWithDigest = ImmutableCatalogService.builder()
                .from(dummyCatalogService) // Copy base fields from the dummy
                .serviceId(serviceName + "-1")
                .serviceName(serviceName)
                .serviceTags(List.of("http", "yappy-config-digest=" + expectedDigest))
                .build();

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(false);
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v1"));

        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(serviceWithDigest)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName)) // Assume healthy for simplicity
                .thenReturn(Mono.just(List.of(dummyServiceHealth))); // Need to match service ID or adjust dummyServiceHealth
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals(expectedDigest, capturedStatus.reportedModuleConfigDigest());
    }

    @Test
    void testAggregateAndStore_PopulatesReportedModuleConfigDigest_MultipleInstancesSameDigest() throws Exception {
        String serviceName = "digest-service-multi-same";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);
        String expectedDigest = "xyz98765";

        org.kiwiproject.consul.model.catalog.CatalogService service1 = ImmutableCatalogService.builder()
                .from(dummyCatalogService).serviceId(serviceName + "-1").serviceName(serviceName)
                .serviceTags(List.of("yappy-config-digest=" + expectedDigest))
                .build();
        org.kiwiproject.consul.model.catalog.CatalogService service2 = ImmutableCatalogService.builder()
                .from(dummyCatalogService).serviceId(serviceName + "-2").serviceName(serviceName)
                .serviceTags(List.of(expectedDigest, "yappy-config-digest=" + expectedDigest)) // Test with other tags too
                .build();

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(false);
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v1"));

        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(service1, service2)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyServiceHealth, dummyServiceHealth))); // Assuming 2 healthy
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals(expectedDigest, capturedStatus.reportedModuleConfigDigest());
    }

    @Test
    void testAggregateAndStore_PopulatesReportedModuleConfigDigest_MultipleInstancesDifferentDigests() throws Exception {
        String serviceName = "digest-service-multi-diff";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);
        String digest1 = "abc";
        String digest2 = "def";

        org.kiwiproject.consul.model.catalog.CatalogService service1 = ImmutableCatalogService.builder()
                .from(dummyCatalogService).serviceId(serviceName + "-1").serviceName(serviceName)
                .serviceTags(List.of("yappy-config-digest=" + digest1))
                .build();
        org.kiwiproject.consul.model.catalog.CatalogService service2 = ImmutableCatalogService.builder()
                .from(dummyCatalogService).serviceId(serviceName + "-2").serviceName(serviceName)
                .serviceTags(List.of("yappy-config-digest=" + digest2))
                .build();

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(false);
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v1"));

        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(service1, service2)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyServiceHealth, dummyServiceHealth)));
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertTrue(capturedStatus.reportedModuleConfigDigest().startsWith("INCONSISTENT_DIGESTS:"));
        assertTrue(capturedStatus.reportedModuleConfigDigest().contains(digest1));
        assertTrue(capturedStatus.reportedModuleConfigDigest().contains(digest2));
    }

    @Test
    void testAggregateAndStore_NoConfigDigestTagPresent() throws Exception {
        String serviceName = "no-digest-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        org.kiwiproject.consul.model.catalog.CatalogService serviceWithoutDigest = ImmutableCatalogService.builder()
                .from(dummyCatalogService)
                .serviceId(serviceName + "-1")
                .serviceName(serviceName)
                .serviceTags(List.of("http", "other-tag")) // No digest tag
                .build();

        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(false);
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v1"));

        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(serviceWithoutDigest)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyServiceHealth)));
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertNull(capturedStatus.reportedModuleConfigDigest());
    }

    // In ServiceStatusAggregatorTest.java

    @Test
    void testAggregateAndStore_InstancesWithNullTags() throws Exception { // Consider renaming if strictly testing "null" is not possible via builder
        String serviceName = "null-tags-service";
        PipelineClusterConfig clusterConfig = createTestClusterConfig(serviceName);

        // This instance will have an empty list of tags because we don't call .serviceTags()
        // or we explicitly set it to an empty list.
        org.kiwiproject.consul.model.catalog.CatalogService serviceWithEffectivelyNoTags = ImmutableCatalogService.builder()
                .from(dummyCatalogService)
                .serviceId(serviceName + "-1")
                .serviceName(serviceName)
                // OPTION 1: Omit .serviceTags() - Immutables will default to empty list
                // OPTION 2: Explicitly set to empty list (safer if default behavior is unknown)
                .serviceTags(Collections.emptyList())
                .build();

        org.kiwiproject.consul.model.catalog.CatalogService serviceWithEmptyTags = ImmutableCatalogService.builder()
                .from(dummyCatalogService)
                .serviceId(serviceName + "-2")
                .serviceName(serviceName)
                .serviceTags(Collections.emptyList()) // Explicitly empty tags
                .build();


        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(false);
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v1"));

        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                // Use the corrected service instance(s)
                .thenReturn(Mono.just(List.of(serviceWithEffectivelyNoTags, serviceWithEmptyTags)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyServiceHealth, dummyServiceHealth)));
        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        // The assertion remains the same, as the outcome for the SUT is that no digest is found
        assertNull(capturedStatus.reportedModuleConfigDigest(), "Digest should be null if tags are null, empty, or no digest tag is present.");
    }

    // In ServiceStatusAggregatorTest.java

    // Helper method to calculate MD5 digest for a Map (simulating customConfig digest)
    // Ensure your ServiceStatusAggregator.generateDigestForCustomConfig uses a compatible hashing logic (e.g., MD5 on JSON string)
    private String calculateDigestForMap(Map<String, Object> map) throws Exception {
        if (map == null || map.isEmpty()) {
            return null;
        }
        // Use the objectMapper injected into the test for consistency if possible,
        // or ensure the logic matches SUT's objectMapper.
        String json = objectMapper.writeValueAsString(map);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void testAggregateAndStore_ConfigurationError_DigestMismatch() throws Exception {
        String serviceName = "config-mismatch-service";
        Map<String, Object> definedCustomConfig = Map.of("key", "definedValue", "version", 1);
        String expectedDigestForDefinedConfig = calculateDigestForMap(definedCustomConfig);

        // Create a PipelineModuleConfiguration that includes the customConfig
        PipelineModuleConfiguration moduleWithCustomConfig = new PipelineModuleConfiguration(
                serviceName, // implementationName
                serviceName, // implementationId
                null,        // customConfigSchemaReference (not relevant for this specific test)
                definedCustomConfig // The actual customConfig
        );

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineModuleMap(new PipelineModuleMap(Map.of(serviceName, moduleWithCustomConfig)))
                .build();

        String reportedDigestByInstance = "thisIsADifferentDigest123"; // This digest does NOT match expectedDigestForDefinedConfig
        org.kiwiproject.consul.model.catalog.CatalogService instanceWithWrongDigest =
                ImmutableCatalogService.builder().from(dummyCatalogService)
                        .serviceId(serviceName + "-id1")
                        .serviceName(serviceName)
                        .serviceTags(List.of("yappy-config-digest=" + reportedDigestByInstance))
                        .build();

        // Mock DynamicConfigurationManager
        when(mockDynamicConfigManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        when(mockDynamicConfigManager.isCurrentConfigStale()).thenReturn(false); // Assume cluster config itself is not stale
        when(mockDynamicConfigManager.getCurrentConfigVersionIdentifier()).thenReturn(Optional.of("v-ok"));

        // Mock Consul - service instance is healthy but has the wrong config digest
        when(mockConsulBusinessOpsService.getServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(instanceWithWrongDigest)));
        when(mockConsulBusinessOpsService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(dummyServiceHealth))); // Assume healthy

        when(mockConsulKvService.putValue(anyString(), anyString())).thenReturn(Mono.just(true));

        // --- Act ---
        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        Thread.sleep(200); // Allow async operations

        // --- Assert ---
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConsulKvService).putValue(eq("yappy/status/services/" + serviceName), valueCaptor.capture());
        ServiceAggregatedStatus capturedStatus = objectMapper.readValue(valueCaptor.getValue(), ServiceAggregatedStatus.class);

        assertEquals(serviceName, capturedStatus.serviceName());
        assertEquals(ServiceOperationalStatus.CONFIGURATION_ERROR, capturedStatus.operationalStatus(), "Status should be CONFIGURATION_ERROR due to digest mismatch.");
        assertEquals(reportedDigestByInstance, capturedStatus.reportedModuleConfigDigest(), "Should report the digest from the instance.");
        assertNotNull(expectedDigestForDefinedConfig, "Expected digest should have been calculated for the test.");
        assertNotEquals(expectedDigestForDefinedConfig, capturedStatus.reportedModuleConfigDigest(), "Reported and expected digests should differ.");

        assertFalse(capturedStatus.errorMessages().isEmpty(), "Error messages should not be empty.");
        String errorMessage = capturedStatus.errorMessages().getFirst();
        assertTrue(errorMessage.contains("MISMATCHED config digest"), "Error message should indicate a mismatched digest.");
        assertTrue(errorMessage.contains("Reported: " + reportedDigestByInstance), "Error message should contain the reported digest.");
        assertTrue(errorMessage.contains("Expected: " + expectedDigestForDefinedConfig), "Error message should contain the expected digest.");

        assertEquals(1, capturedStatus.totalInstancesConsul());
        assertEquals(1, capturedStatus.healthyInstancesConsul()); // Instance is healthy, but config is wrong
        assertFalse(capturedStatus.isUsingStaleClusterConfig());
        assertEquals("v-ok", capturedStatus.activeClusterConfigVersion());
    }
}