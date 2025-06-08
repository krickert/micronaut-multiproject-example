package com.krickert.search.engine.integration;

import com.krickert.search.engine.service.ModuleRegistrationService;
import com.krickert.search.grpc.ModuleInfo;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for module registration functionality.
 * This test verifies that modules can register, be queried, and unregister correctly.
 */
@MicronautTest(environments = {"test"})
public class ModuleRegistrationIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistrationIntegrationTest.class);
    
    @Inject
    ModuleRegistrationService registrationService;
    
    @Test
    void testModuleRegistrationLifecycle() {
        // Create a test module
        String serviceId = "test-module-" + UUID.randomUUID().toString().substring(0, 8);
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
            .setServiceName("test-module")
            .setServiceId(serviceId)
            .setHost("localhost")
            .setPort(50052)
            .putMetadata("version", "1.0.0")
            .putMetadata("type", "processor")
            .build();
        
        // Register the module
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
        
        // List modules and verify our module is in the list
        StepVerifier.create(registrationService.listRegisteredModules())
            .assertNext(module -> {
                assertThat(module.getServiceId()).isEqualTo(serviceId);
                assertThat(module.getServiceName()).isEqualTo("test-module");
                assertThat(module.getHost()).isEqualTo("localhost");
                assertThat(module.getPort()).isEqualTo(50052);
            })
            .verifyComplete();
        
        // Check module health
        StepVerifier.create(registrationService.getModuleHealth(serviceId))
            .assertNext(health -> {
                assertThat(health.getServiceId()).isEqualTo(serviceId);
                assertThat(health.getIsHealthy()).isTrue();
                assertThat(health.getHealthDetails()).contains("healthy");
            })
            .verifyComplete();
        
        // Update heartbeat
        StepVerifier.create(registrationService.updateHeartbeat(serviceId))
            .expectNext(true)
            .verifyComplete();
        
        // Unregister the module
        StepVerifier.create(registrationService.unregisterModule(serviceId))
            .expectNext(true)
            .verifyComplete();
        
        // Verify module is no longer registered
        StepVerifier.create(registrationService.isModuleRegistered(serviceId))
            .expectNext(false)
            .verifyComplete();
        
        // Verify module is not in the list
        StepVerifier.create(registrationService.listRegisteredModules())
            .verifyComplete();
        
        LOG.info("Module registration lifecycle test completed successfully");
    }
    
    @Test
    void testMultipleModuleRegistration() {
        // Register multiple modules
        String serviceId1 = "module-1-" + UUID.randomUUID().toString().substring(0, 8);
        String serviceId2 = "module-2-" + UUID.randomUUID().toString().substring(0, 8);
        
        ModuleInfo module1 = ModuleInfo.newBuilder()
            .setServiceName("module-1")
            .setServiceId(serviceId1)
            .setHost("localhost")
            .setPort(50053)
            .build();
        
        ModuleInfo module2 = ModuleInfo.newBuilder()
            .setServiceName("module-2")
            .setServiceId(serviceId2)
            .setHost("localhost")
            .setPort(50054)
            .build();
        
        // Register both modules
        StepVerifier.create(registrationService.registerModule(module1))
            .assertNext(status -> assertThat(status.getSuccess()).isTrue())
            .verifyComplete();
        
        StepVerifier.create(registrationService.registerModule(module2))
            .assertNext(status -> assertThat(status.getSuccess()).isTrue())
            .verifyComplete();
        
        // Verify both modules are listed
        StepVerifier.create(registrationService.listRegisteredModules().collectList())
            .assertNext(modules -> {
                assertThat(modules).hasSize(2);
                assertThat(modules).extracting("serviceId")
                    .containsExactlyInAnyOrder(serviceId1, serviceId2);
            })
            .verifyComplete();
        
        // Cleanup
        registrationService.unregisterModule(serviceId1).block();
        registrationService.unregisterModule(serviceId2).block();
    }
}