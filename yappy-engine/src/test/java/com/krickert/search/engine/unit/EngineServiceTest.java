package com.krickert.search.engine.unit;

import com.krickert.search.engine.service.EngineHealthStatus;
import com.krickert.search.engine.service.EngineOrchestrator;
import com.krickert.search.engine.service.impl.EngineOrchestratorImpl;
import com.krickert.search.engine.kafka.KafkaConsumerService;
import com.krickert.search.engine.grpc.IModuleRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit test for EngineOrchestrator without Micronaut context.
 */
public class EngineServiceTest {
    
    @Mock
    private IModuleRegistrationService registrationService;
    
    @Mock
    private KafkaConsumerService kafkaConsumerService;
    
    private EngineOrchestrator orchestrator;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new EngineOrchestratorImpl(registrationService, kafkaConsumerService);
    }
    
    @Test
    void testEngineStart() {
        // Test starting the engine
        StepVerifier.create(orchestrator.start())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isTrue();
        verify(kafkaConsumerService).startConsumers();
    }
    
    @Test
    void testEngineStop() {
        // Start first
        orchestrator.start().block();
        
        // Then stop
        StepVerifier.create(orchestrator.stop())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isFalse();
        verify(kafkaConsumerService).stopConsumers();
    }
    
    @Test
    void testHealthStatus() {
        // Setup mocks
        when(kafkaConsumerService.isHealthy()).thenReturn(true);
        when(kafkaConsumerService.getActiveConsumerCount()).thenReturn(3);
        when(registrationService.getRegisteredModuleCount()).thenReturn(2);
        
        // Get health status
        EngineHealthStatus health = orchestrator.getHealthStatus();
        
        assertThat(health).isNotNull();
        assertThat(health.overallStatus()).isEqualTo(EngineHealthStatus.Status.HEALTHY);
        assertThat(health.componentStatuses()).containsKeys("kafka-consumer", "module-registration");
        assertThat(health.lastChecked()).isNotNull();
    }
    
    @Test
    void testUnhealthyStatus() {
        // Setup mocks - Kafka unhealthy
        when(kafkaConsumerService.isHealthy()).thenReturn(false);
        when(kafkaConsumerService.getActiveConsumerCount()).thenReturn(0);
        when(registrationService.getRegisteredModuleCount()).thenReturn(2);
        
        // Get health status
        EngineHealthStatus health = orchestrator.getHealthStatus();
        
        assertThat(health.overallStatus()).isEqualTo(EngineHealthStatus.Status.UNHEALTHY);
    }
}