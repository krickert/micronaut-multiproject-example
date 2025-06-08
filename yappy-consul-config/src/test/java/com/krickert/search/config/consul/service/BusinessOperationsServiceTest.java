package com.krickert.search.config.consul.service;

import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.Test;
import org.kiwiproject.consul.model.agent.FullService;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class to verify that BusinessOperationsService interface is properly designed
 * and can be mocked for testing.
 */
class BusinessOperationsServiceTest {

    @Test
    void testInterfaceCanBeMocked() {
        // Create a mock of the interface
        BusinessOperationsService mockService = mock(BusinessOperationsService.class);
        
        // Test KV operations
        when(mockService.deleteClusterConfiguration("test-cluster"))
            .thenReturn(Mono.just(true));
        
        StepVerifier.create(mockService.deleteClusterConfiguration("test-cluster"))
            .expectNext(true)
            .verifyComplete();
        
        // Test service registration
        Registration registration = ImmutableRegistration.builder()
            .id("test-service")
            .name("Test Service")
            .build();
        
        when(mockService.registerService(registration))
            .thenReturn(Mono.empty());
        
        StepVerifier.create(mockService.registerService(registration))
            .verifyComplete();
        
        // Test pipeline config retrieval
        PipelineClusterConfig testConfig = new PipelineClusterConfig(
            "test-cluster",
            new PipelineGraphConfig(Map.of()),
            new PipelineModuleMap(Map.of()),
            "default-pipeline",
            Set.of(),
            Set.of()
        );
        
        when(mockService.getPipelineClusterConfig("test-cluster"))
            .thenReturn(Mono.just(Optional.of(testConfig)));
        
        StepVerifier.create(mockService.getPipelineClusterConfig("test-cluster"))
            .assertNext(opt -> {
                assertThat(opt).isPresent();
                assertThat(opt.get().clusterName()).isEqualTo("test-cluster");
            })
            .verifyComplete();
        
        // Verify all methods were called
        verify(mockService).deleteClusterConfiguration("test-cluster");
        verify(mockService).registerService(registration);
        verify(mockService).getPipelineClusterConfig("test-cluster");
    }
    
    @Test
    void testInterfaceHasAllNecessaryMethods() {
        // This test verifies that the interface has all the methods we need
        // by checking that we can create a mock and invoke all expected operations
        
        BusinessOperationsService service = mock(BusinessOperationsService.class);
        
        // Configure mocks to return non-null values
        when(service.deleteClusterConfiguration(anyString())).thenReturn(Mono.just(true));
        when(service.deleteSchemaVersion(anyString(), anyInt())).thenReturn(Mono.just(true));
        when(service.putValue(anyString(), any())).thenReturn(Mono.just(true));
        when(service.storeClusterConfiguration(anyString(), any())).thenReturn(Mono.just(true));
        when(service.storeSchemaVersion(anyString(), anyInt(), any())).thenReturn(Mono.just(true));
        
        when(service.registerService(any(Registration.class))).thenReturn(Mono.empty());
        when(service.deregisterService(anyString())).thenReturn(Mono.empty());
        when(service.listServices()).thenReturn(Mono.just(Map.of()));
        when(service.getServiceInstances(anyString())).thenReturn(Mono.just(List.of()));
        when(service.getHealthyServiceInstances(anyString())).thenReturn(Mono.just(List.of()));
        when(service.isConsulAvailable()).thenReturn(Mono.just(true));
        
        when(service.getPipelineClusterConfig(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(service.getPipelineGraphConfig(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(service.getPipelineModuleMap(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(service.getAllowedKafkaTopics(anyString())).thenReturn(Mono.just(Set.of()));
        when(service.getAllowedGrpcServices(anyString())).thenReturn(Mono.just(Set.of()));
        when(service.getSpecificPipelineConfig(anyString(), anyString())).thenReturn(Mono.just(Optional.empty()));
        when(service.listPipelineNames(anyString())).thenReturn(Mono.just(List.of()));
        when(service.getSpecificPipelineModuleConfiguration(anyString(), anyString())).thenReturn(Mono.just(Optional.empty()));
        when(service.listAvailablePipelineModuleImplementations(anyString())).thenReturn(Mono.just(List.of()));
        
        when(service.getServiceWhitelist()).thenReturn(Mono.just(List.of()));
        when(service.getTopicWhitelist()).thenReturn(Mono.just(List.of()));
        
        when(service.getServiceChecks(anyString())).thenReturn(Mono.just(List.of()));
        when(service.getAgentServiceDetails(anyString())).thenReturn(Mono.just(Optional.empty()));
        
        when(service.getFullClusterKey(anyString())).thenReturn("test-key");
        when(service.listAvailableClusterNames()).thenReturn(Mono.just(List.of()));
        when(service.cleanupTestResources(any(), any(), any())).thenReturn(Mono.empty());
        
        // Now verify all methods can be called
        assertThat(service.deleteClusterConfiguration("test")).isNotNull();
        assertThat(service.deleteSchemaVersion("test", 1)).isNotNull();
        assertThat(service.putValue("key", "value")).isNotNull();
        assertThat(service.storeClusterConfiguration("cluster", new Object())).isNotNull();
        assertThat(service.storeSchemaVersion("subject", 1, new Object())).isNotNull();
        
        assertThat(service.registerService(mock(Registration.class))).isNotNull();
        assertThat(service.deregisterService("service")).isNotNull();
        assertThat(service.listServices()).isNotNull();
        assertThat(service.getServiceInstances("service")).isNotNull();
        assertThat(service.getHealthyServiceInstances("service")).isNotNull();
        assertThat(service.isConsulAvailable()).isNotNull();
        
        assertThat(service.getPipelineClusterConfig("cluster")).isNotNull();
        assertThat(service.getPipelineGraphConfig("cluster")).isNotNull();
        assertThat(service.getPipelineModuleMap("cluster")).isNotNull();
        assertThat(service.getAllowedKafkaTopics("cluster")).isNotNull();
        assertThat(service.getAllowedGrpcServices("cluster")).isNotNull();
        assertThat(service.getSpecificPipelineConfig("cluster", "pipeline")).isNotNull();
        assertThat(service.listPipelineNames("cluster")).isNotNull();
        assertThat(service.getSpecificPipelineModuleConfiguration("cluster", "module")).isNotNull();
        assertThat(service.listAvailablePipelineModuleImplementations("cluster")).isNotNull();
        
        assertThat(service.getServiceWhitelist()).isNotNull();
        assertThat(service.getTopicWhitelist()).isNotNull();
        
        assertThat(service.getServiceChecks("service")).isNotNull();
        assertThat(service.getAgentServiceDetails("service")).isNotNull();
        
        assertThat(service.getFullClusterKey("cluster")).isNotNull();
        assertThat(service.listAvailableClusterNames()).isNotNull();
        assertThat(service.cleanupTestResources(List.of(), List.of(), List.of())).isNotNull();
    }
}