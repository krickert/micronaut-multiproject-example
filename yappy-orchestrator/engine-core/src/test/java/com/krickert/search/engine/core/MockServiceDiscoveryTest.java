package com.krickert.search.engine.core;

import com.krickert.search.engine.core.mock.MockServiceDiscovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for MockServiceDiscovery to ensure it works correctly
 * for testing purposes.
 */
class MockServiceDiscoveryTest {

    private MockServiceDiscovery mockServiceDiscovery;

    @BeforeEach
    void setUp() {
        mockServiceDiscovery = new MockServiceDiscovery();
    }

    @Test
    void testRegisterService() {
        // Given
        ServiceRegistration registration = ServiceRegistration.builder()
                .serviceId("test-service-1")
                .serviceName("test-service")
                .host("localhost")
                .port(8080)
                .build();

        // When
        StepVerifier.create(mockServiceDiscovery.registerService(registration))
                .verifyComplete();

        // Then
        StepVerifier.create(mockServiceDiscovery.getAllHealthyInstances("test-service").collectList())
                .assertNext(instances -> {
                    assertThat(instances).hasSize(1);
                    ServiceInstance instance = instances.get(0);
                    assertThat(instance.serviceId()).isEqualTo("test-service-1");
                    assertThat(instance.serviceName()).isEqualTo("test-service");
                    assertThat(instance.host()).isEqualTo("localhost");
                    assertThat(instance.port()).isEqualTo(8080);
                })
                .verifyComplete();
    }

    @Test
    void testDeregisterService() {
        // Given
        ServiceRegistration registration = ServiceRegistration.builder()
                .serviceId("test-service-2")
                .serviceName("test-service")
                .host("localhost")
                .port(8081)
                .build();

        // Register first
        StepVerifier.create(mockServiceDiscovery.registerService(registration))
                .verifyComplete();
        
        // When - deregister
        StepVerifier.create(mockServiceDiscovery.deregisterService("test-service-2"))
                .verifyComplete();
        
        // Then - should be empty
        StepVerifier.create(mockServiceDiscovery.getAllHealthyInstances("test-service").collectList())
                .assertNext(instances -> assertThat(instances).isEmpty())
                .verifyComplete();
    }

    @Test
    void testGetHealthyInstance() {
        // Given
        ServiceRegistration registration = ServiceRegistration.builder()
                .serviceId("test-service-3")
                .serviceName("test-service")
                .host("localhost")
                .port(8082)
                .build();

        // Register instance
        StepVerifier.create(mockServiceDiscovery.registerService(registration))
                .verifyComplete();

        // When - get healthy instance
        StepVerifier.create(mockServiceDiscovery.getHealthyInstance("test-service"))
                .assertNext(instance -> {
                    assertThat(instance.serviceId()).isEqualTo("test-service-3");
                    assertThat(instance.serviceName()).isEqualTo("test-service");
                    assertThat(instance.healthStatus()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void testGetHealthyInstanceNotFound() {
        // When - try to get instance of non-existent service
        StepVerifier.create(mockServiceDiscovery.getHealthyInstance("non-existent-service"))
                .verifyComplete();
    }

    @Test
    void testRegisterMultipleInstancesOfSameService() {
        // Given
        ServiceRegistration registration1 = ServiceRegistration.builder()
                .serviceId("test-service-instance-1")
                .serviceName("multi-instance-service")
                .host("host1")
                .port(8080)
                .build();

        ServiceRegistration registration2 = ServiceRegistration.builder()
                .serviceId("test-service-instance-2")
                .serviceName("multi-instance-service")
                .host("host2")
                .port(8081)
                .build();

        // When - register both
        StepVerifier.create(mockServiceDiscovery.registerService(registration1))
                .verifyComplete();
        StepVerifier.create(mockServiceDiscovery.registerService(registration2))
                .verifyComplete();
        
        // Then - should have both instances
        StepVerifier.create(mockServiceDiscovery.getAllHealthyInstances("multi-instance-service").collectList())
                .assertNext(instances -> {
                    assertThat(instances).hasSize(2);
                    assertThat(instances)
                            .extracting(ServiceInstance::serviceId)
                            .containsExactlyInAnyOrder("test-service-instance-1", "test-service-instance-2");
                })
                .verifyComplete();
    }

    @Test
    void testWatchService() {
        // Given
        ServiceRegistration registration = ServiceRegistration.builder()
                .serviceId("watch-service-1")
                .serviceName("watch-service")
                .host("localhost")
                .port(8083)
                .build();

        // When - start watching before registration
        StepVerifier.create(mockServiceDiscovery.watchService("watch-service").take(1))
                .expectNextCount(0) // No updates yet
                .thenAwait()
                .then(() -> {
                    // Register a service while watching
                    mockServiceDiscovery.registerService(registration).subscribe();
                })
                .assertNext(update -> {
                    // Should receive an update
                    assertThat(update.type()).isEqualTo(ServiceInstanceUpdate.UpdateType.ADDED);
                    assertThat(update.instance().serviceId()).isEqualTo("watch-service-1");
                })
                .verifyComplete();
    }
}