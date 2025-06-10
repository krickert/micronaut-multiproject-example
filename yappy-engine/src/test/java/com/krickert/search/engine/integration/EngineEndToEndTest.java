package com.krickert.search.engine.integration;

import com.krickert.search.engine.service.EngineOrchestrator;
import com.krickert.search.engine.service.ModuleRegistrationService;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.RegistrationStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test demonstrating module registration
 * and basic engine functionality.
 */
@MicronautTest(
    environments = {"test", "engine-integration"},
    propertySources = "classpath:application-engine-integration.yml"
)
public class EngineEndToEndTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineEndToEndTest.class);
    
    @Inject
    EngineOrchestrator orchestrator;
    
    @Inject
    ModuleRegistrationService registrationService;
    
    @Test
    void testModuleRegistrationFlow() {
        // Start the engine
        StepVerifier.create(orchestrator.start())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isTrue();
        
        // Register a test module
        String serviceId = "test-processor-" + UUID.randomUUID().toString().substring(0, 8);
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
            .setServiceName("test-processor")
            .setServiceId(serviceId)
            .setHost("localhost")
            .setPort(50052)
            .putMetadata("version", "1.0.0")
            .putMetadata("type", "processor")
            .build();
        
        StepVerifier.create(registrationService.registerModule(moduleInfo))
            .assertNext(status -> {
                assertThat(status.getSuccess()).isTrue();
                assertThat(status.getMessage()).contains("successfully");
                LOG.info("Module registered: {}", status.getMessage());
            })
            .verifyComplete();
        
        // Verify module is registered
        StepVerifier.create(registrationService.isModuleRegistered(serviceId))
            .expectNext(true)
            .verifyComplete();
        
        // List modules
        StepVerifier.create(registrationService.listRegisteredModules())
            .assertNext(module -> {
                assertThat(module.getServiceId()).isEqualTo(serviceId);
                assertThat(module.getServiceName()).isEqualTo("test-processor");
            })
            .verifyComplete();
        
        // Check module health
        StepVerifier.create(registrationService.getModuleHealth(serviceId))
            .assertNext(health -> {
                assertThat(health.getServiceId()).isEqualTo(serviceId);
                assertThat(health.getIsHealthy()).isTrue();
            })
            .verifyComplete();
        
        // Unregister module
        StepVerifier.create(registrationService.unregisterModule(serviceId))
            .expectNext(true)
            .verifyComplete();
        
        // Verify module is no longer registered
        StepVerifier.create(registrationService.isModuleRegistered(serviceId))
            .expectNext(false)
            .verifyComplete();
        
        // Stop the engine
        StepVerifier.create(orchestrator.stop())
            .verifyComplete();
        
        assertThat(orchestrator.isRunning()).isFalse();
    }
}