package com.krickert.search.engine.integration;

import com.krickert.search.engine.pipeline.PipelineExecutionService;
import com.krickert.search.engine.service.EngineOrchestrator;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple integration test for the engine using the new architecture.
 */
@MicronautTest(
    environments = {"test", "engine-integration"},
    propertySources = "classpath:application-engine-integration.yml"
)
public class SimpleEngineIntegrationTest {
    
    @Inject
    EngineOrchestrator orchestrator;
    
    @Inject
    PipelineExecutionService pipelineService;
    
    @Test
    void testEngineStartup() {
        // Test that engine can start
        StepVerifier.create(orchestrator.start())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isTrue();
        
        // Test health status
        var health = orchestrator.getHealthStatus();
        assertThat(health).isNotNull();
        assertThat(health.overallStatus()).isNotNull();
        
        // Test that engine can stop
        StepVerifier.create(orchestrator.stop())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isFalse();
    }
    
    @Test
    void testPipelineServiceAvailable() {
        assertThat(pipelineService).isNotNull();
        
        // Test listing pipelines (should be empty initially)
        StepVerifier.create(pipelineService.listActivePipelines())
            .expectNextCount(0)
            .verifyComplete();
    }
}