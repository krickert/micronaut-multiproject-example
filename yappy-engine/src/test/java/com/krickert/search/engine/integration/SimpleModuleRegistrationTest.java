package com.krickert.search.engine.integration;

import com.google.protobuf.Empty;
import com.krickert.search.engine.grpc.GrpcModuleRegistrationService;
import com.krickert.search.grpc.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test for module registration using the correct gRPC service.
 */
@MicronautTest(
    environments = {"test", "engine-integration"},
    propertySources = "classpath:application-engine-integration.yml",
    rebuildContext = true
)
public class SimpleModuleRegistrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(SimpleModuleRegistrationTest.class);
    
    @Inject
    GrpcModuleRegistrationService registrationService;
    
    @Test
    void testModuleRegistration() throws InterruptedException {
        String serviceId = "test-module-" + UUID.randomUUID();
        
        // Create module info
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                .setServiceName("test-processor")
                .setServiceId(serviceId)
                .setHost("localhost")
                .setPort(8080)
                .setHealthEndpoint("/health")
                .putMetadata("version", "1.0.0")
                .addTags("test")
                .addTags("integration")
                .build();
        
        // Use CountDownLatch to wait for async response
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RegistrationStatus> statusRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        
        // Create response observer
        StreamObserver<RegistrationStatus> responseObserver = new StreamObserver<RegistrationStatus>() {
            @Override
            public void onNext(RegistrationStatus value) {
                LOG.info("Registration response: success={}, message={}", 
                        value.getSuccess(), value.getMessage());
                statusRef.set(value);
            }
            
            @Override
            public void onError(Throwable t) {
                LOG.error("Registration failed", t);
                errorRef.set(t);
                latch.countDown();
            }
            
            @Override
            public void onCompleted() {
                LOG.info("Registration completed");
                latch.countDown();
            }
        };
        
        // Register module
        registrationService.registerModule(moduleInfo, responseObserver);
        
        // Wait for response
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Registration should complete within 5 seconds");
        
        // Check results
        assertNull(errorRef.get(), "Registration should not have errors");
        assertNotNull(statusRef.get(), "Should have received status");
        assertTrue(statusRef.get().getSuccess(), "Registration should be successful");
        
        // Test listing modules
        CountDownLatch listLatch = new CountDownLatch(1);
        AtomicReference<ModuleList> listRef = new AtomicReference<>();
        
        StreamObserver<ModuleList> listObserver = new StreamObserver<ModuleList>() {
            @Override
            public void onNext(ModuleList value) {
                listRef.set(value);
            }
            
            @Override
            public void onError(Throwable t) {
                LOG.error("List failed", t);
                listLatch.countDown();
            }
            
            @Override
            public void onCompleted() {
                listLatch.countDown();
            }
        };
        
        registrationService.listModules(Empty.getDefaultInstance(), listObserver);
        assertTrue(listLatch.await(5, TimeUnit.SECONDS), "List should complete within 5 seconds");
        
        assertNotNull(listRef.get(), "Should have module list");
        assertTrue(listRef.get().getModulesList().stream()
                .anyMatch(m -> m.getServiceId().equals(serviceId)),
                "Registered module should be in list");
        
        // Clean up - unregister module
        ModuleId moduleId = ModuleId.newBuilder()
                .setServiceId(serviceId)
                .build();
        
        CountDownLatch unregLatch = new CountDownLatch(1);
        StreamObserver<UnregistrationStatus> unregObserver = new StreamObserver<UnregistrationStatus>() {
            @Override
            public void onNext(UnregistrationStatus value) {
                LOG.info("Unregistration response: success={}", value.getSuccess());
            }
            
            @Override
            public void onError(Throwable t) {
                LOG.error("Unregistration failed", t);
                unregLatch.countDown();
            }
            
            @Override
            public void onCompleted() {
                unregLatch.countDown();
            }
        };
        
        registrationService.unregisterModule(moduleId, unregObserver);
        assertTrue(unregLatch.await(5, TimeUnit.SECONDS), "Unregistration should complete within 5 seconds");
    }
}