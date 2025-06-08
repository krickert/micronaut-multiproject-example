package com.krickert.search.engine.core;

import com.krickert.search.engine.core.mock.MockServiceDiscovery;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic test to verify the engine core module can start without test resources.
 */
@MicronautTest(startApplication = false)
public class BasicEngineTest {
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.isRunning()).isTrue();
    }
    
    @Test
    void canCreateMockServiceDiscovery() {
        MockServiceDiscovery mockServiceDiscovery = applicationContext.createBean(MockServiceDiscovery.class);
        assertThat(mockServiceDiscovery).isNotNull();
    }
    
    @Test
    void canRegisterBeansManually() {
        // Register a mock service discovery manually
        applicationContext.registerSingleton(ServiceDiscovery.class, new MockServiceDiscovery());
        
        // Verify it's available
        ServiceDiscovery serviceDiscovery = applicationContext.getBean(ServiceDiscovery.class);
        assertThat(serviceDiscovery).isNotNull();
        assertThat(serviceDiscovery).isInstanceOf(MockServiceDiscovery.class);
    }
}