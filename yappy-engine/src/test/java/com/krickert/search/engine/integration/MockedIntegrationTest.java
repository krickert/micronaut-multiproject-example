package com.krickert.search.engine.integration;

import com.krickert.search.engine.grpc.mock.MockModuleRegistrationService;
import com.krickert.search.engine.kafka.MockKafkaConsumerService;
import com.krickert.search.engine.pipeline.PipelineExecutionService;
import com.krickert.search.engine.service.EngineOrchestrator;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test with mocked services, no test resources needed.
 */
@MicronautTest
@Property(name = "consul.client.enabled", value = "false")
@Property(name = "app.kafka.slot-management.enabled", value = "false")
@Property(name = "micronaut.test-resources.enabled", value = "false")
public class MockedIntegrationTest {
    
    @Inject
    EngineOrchestrator orchestrator;
    
    @Inject
    PipelineExecutionService pipelineService;
    
    @Inject
    MockKafkaConsumerService kafkaService;
    
    @Inject
    MockModuleRegistrationService registrationService;
    
    @Test
    void testWithMockedServices() {
        // Start engine
        StepVerifier.create(orchestrator.start())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isTrue();
        
        // Register a mock module
        registrationService.registerModule("test-module");
        assertThat(registrationService.getRegisteredModuleCount()).isEqualTo(1);
        
        // Check health
        var health = orchestrator.getHealthStatus();
        assertThat(health.overallStatus()).isNotNull();
        
        // List pipelines
        StepVerifier.create(pipelineService.listActivePipelines())
            .expectNextCount(0)
            .verifyComplete();
        
        // Stop engine
        StepVerifier.create(orchestrator.stop())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isFalse();
    }
}