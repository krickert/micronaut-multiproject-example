package com.krickert.search.engine.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Engine Core module.
 * This test verifies that Micronaut test resources are properly configured
 * and that required dependencies (Consul, Kafka) are injected correctly.
 */
@MicronautTest
class EngineIntegrationTest {

    @Inject
    ApplicationContext applicationContext;

    @Test
    void testApplicationContextStarts() {
        // Simple smoke test to verify the application context starts
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.isRunning()).isTrue();
    }

    @Test
    void testConsulPropertiesInjected() {
        // Verify Consul properties are injected from test resources
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse(null);
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(null);
        
        assertThat(consulHost)
                .as("Consul host should be injected by test resources")
                .isNotNull()
                .isNotEmpty();
        
        assertThat(consulPort)
                .as("Consul port should be injected by test resources")
                .isNotNull()
                .isPositive();
        
        // Log the values for debugging
        System.out.println("Consul configuration injected by test resources:");
        System.out.println("  Host: " + consulHost);
        System.out.println("  Port: " + consulPort);
    }

    @Test
    void testKafkaPropertiesInjected() {
        // Verify Kafka properties are injected from test resources
        String kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null);
        
        assertThat(kafkaServers)
                .as("Kafka bootstrap servers should be injected by test resources")
                .isNotNull()
                .isNotEmpty()
                .contains("localhost");
        
        // Log the values for debugging
        System.out.println("Kafka configuration injected by test resources:");
        System.out.println("  Bootstrap servers: " + kafkaServers);
    }

    @Test
    void testServiceDiscoveryAvailable() {
        // Verify that the ServiceDiscovery bean is available
        boolean hasServiceDiscovery = applicationContext.containsBean(ServiceDiscovery.class);
        
        assertThat(hasServiceDiscovery)
                .as("ServiceDiscovery should be available in the application context")
                .isTrue();
        
        if (hasServiceDiscovery) {
            ServiceDiscovery serviceDiscovery = applicationContext.getBean(ServiceDiscovery.class);
            assertThat(serviceDiscovery).isNotNull();
            System.out.println("ServiceDiscovery implementation: " + serviceDiscovery.getClass().getSimpleName());
        }
    }

    @Test
    void testPipelineEngineAvailable() {
        // Verify that the PipelineEngine bean is available
        boolean hasPipelineEngine = applicationContext.containsBean(PipelineEngine.class);
        
        assertThat(hasPipelineEngine)
                .as("PipelineEngine should be available in the application context")
                .isTrue();
        
        if (hasPipelineEngine) {
            PipelineEngine pipelineEngine = applicationContext.getBean(PipelineEngine.class);
            assertThat(pipelineEngine).isNotNull();
            System.out.println("PipelineEngine implementation: " + pipelineEngine.getClass().getSimpleName());
        }
    }

    @Test
    void testMessageRouterAvailable() {
        // Verify that the MessageRouter bean is available
        boolean hasMessageRouter = applicationContext.containsBean(MessageRouter.class);
        
        assertThat(hasMessageRouter)
                .as("MessageRouter should be available in the application context")
                .isTrue();
        
        if (hasMessageRouter) {
            MessageRouter messageRouter = applicationContext.getBean(MessageRouter.class);
            assertThat(messageRouter).isNotNull();
            System.out.println("MessageRouter implementation: " + messageRouter.getClass().getSimpleName());
        }
    }

    @Test
    void testEnvironmentConfiguration() {
        // Verify that we're running in test environment
        String environment = applicationContext.getProperty("micronaut.environments", String.class).orElse("");
        
        assertThat(environment)
                .as("Should be running in test environment")
                .contains("test");
        
        // Check test resources configuration
        Boolean testResourcesEnabled = applicationContext.getProperty("micronaut.test-resources.enabled", Boolean.class).orElse(false);
        
        assertThat(testResourcesEnabled)
                .as("Test resources should be enabled")
                .isTrue();
        
        System.out.println("Environment configuration:");
        System.out.println("  Environments: " + environment);
        System.out.println("  Test resources enabled: " + testResourcesEnabled);
    }
}