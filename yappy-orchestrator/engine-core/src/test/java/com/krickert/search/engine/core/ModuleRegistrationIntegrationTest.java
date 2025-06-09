package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.health.model.HealthService;
import com.google.protobuf.Empty;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.ModuleRegistrationGrpc;
import com.krickert.search.grpc.RegistrationStatus;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the module registration flow.
 * Demonstrates:
 * 1. Creating isolated test clusters
 * 2. Engine's ModuleRegistration service
 * 3. Module discovery and registration in Consul
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ModuleRegistrationIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ModuleRegistrationIntegrationTest.class);
    private static final int ENGINE_GRPC_PORT = 50050;
    
    @Inject
    ApplicationContext applicationContext;
    
    private ConsulClient consulClient;
    private String testClusterName;
    private Server grpcServer;
    private MockModuleRegistrationService registrationService;
    
    @BeforeAll
    void setupTestCluster() throws IOException {
        // Get Consul configuration
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse("localhost");
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(8500);
        consulClient = new ConsulClient(consulHost, consulPort);
        
        // Create unique test cluster
        testClusterName = TestClusterHelper.createTestCluster("module-reg-test");
        
        // Start mock engine registration service
        registrationService = new MockModuleRegistrationService(consulClient, testClusterName);
        grpcServer = ServerBuilder.forPort(ENGINE_GRPC_PORT)
                .addService(registrationService)
                .build()
                .start();
        
        logger.info("Started mock engine registration service on port {}", ENGINE_GRPC_PORT);
    }
    
    @AfterAll
    void cleanupTestCluster() throws InterruptedException {
        if (grpcServer != null) {
            grpcServer.shutdown();
            grpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        if (consulClient != null && testClusterName != null) {
            TestClusterHelper.cleanupTestCluster(consulClient, testClusterName);
        }
    }
    
    @Test
    void testModuleRegistrationFlow() throws InterruptedException {
        // This test simulates what the registration CLI would do:
        // 1. Connect to module to get its metadata
        // 2. Connect to engine to register the module
        // 3. Verify module appears in Consul
        
        // Step 1: Simulate getting module info (in real scenario, this comes from module's GetServiceRegistration)
        String moduleName = "test-chunker";
        String moduleHost = "chunker.example.com";
        int modulePort = 50051;
        
        // Step 2: Register module with engine
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", ENGINE_GRPC_PORT)
                .usePlaintext()
                .build();
        
        try {
            ModuleRegistrationGrpc.ModuleRegistrationBlockingStub stub = 
                ModuleRegistrationGrpc.newBlockingStub(channel);
            
            ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                .setServiceName(moduleName)
                .setServiceId(moduleName + "-instance-1")
                .setHost(moduleHost)
                .setPort(modulePort)
                .setHealthEndpoint("grpc.health.v1.Health/Check")
                .putMetadata("module-type", "text-processor")
                .putMetadata("version", "1.0.0")
                .addTags("grpc")
                .addTags("module")
                .build();
            
            RegistrationStatus status = stub.registerModule(moduleInfo);
            
            assertThat(status.getSuccess()).isTrue();
            assertThat(status.getMessage()).contains("Successfully registered");
            assertThat(status.getConsulServiceId()).isNotEmpty();
            
            logger.info("Module registered successfully: {}", status.getMessage());
            
            // Step 3: Verify module appears in Consul
            Thread.sleep(500); // Give Consul time to update
            
            // Query for services in our test cluster
            List<HealthService> services = consulClient.getHealthServices(
                testClusterName + "-" + moduleName, 
                false, 
                null
            ).getValue();
            
            assertThat(services).isNotEmpty();
            
            HealthService registeredService = services.get(0);
            assertThat(registeredService.getService().getAddress()).isEqualTo(moduleHost);
            assertThat(registeredService.getService().getPort()).isEqualTo(modulePort);
            assertThat(registeredService.getService().getMeta())
                .containsEntry("cluster", testClusterName)
                .containsEntry("module-type", "text-processor");
            
            logger.info("Verified module in Consul: {} at {}:{}", 
                registeredService.getService().getId(),
                registeredService.getService().getAddress(),
                registeredService.getService().getPort());
            
        } finally {
            channel.shutdown();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void testMultipleModuleRegistration() throws InterruptedException {
        // Test registering multiple modules in the same cluster
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", ENGINE_GRPC_PORT)
                .usePlaintext()
                .build();
        
        try {
            ModuleRegistrationGrpc.ModuleRegistrationBlockingStub stub = 
                ModuleRegistrationGrpc.newBlockingStub(channel);
            
            // Register chunker
            ModuleInfo chunker = ModuleInfo.newBuilder()
                .setServiceName("chunker")
                .setServiceId("chunker-1")
                .setHost("chunker.test")
                .setPort(50051)
                .putMetadata("module-type", "text-processor")
                .build();
            
            RegistrationStatus chunkerStatus = stub.registerModule(chunker);
            assertThat(chunkerStatus.getSuccess()).isTrue();
            
            // Register tika-parser
            ModuleInfo tikaParser = ModuleInfo.newBuilder()
                .setServiceName("tika-parser")
                .setServiceId("tika-1")
                .setHost("tika.test")
                .setPort(50052)
                .putMetadata("module-type", "document-parser")
                .build();
            
            RegistrationStatus tikaStatus = stub.registerModule(tikaParser);
            assertThat(tikaStatus.getSuccess()).isTrue();
            
            // Verify both are registered
            Thread.sleep(500);
            
            // List all modules
            var moduleList = stub.listModules(Empty.getDefaultInstance());
            assertThat(moduleList.getModulesCount()).isGreaterThanOrEqualTo(2);
            
            logger.info("Registered {} modules in cluster {}", 
                moduleList.getModulesCount(), testClusterName);
            
        } finally {
            channel.shutdown();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Mock implementation of the engine's ModuleRegistration service.
     * In the real implementation, this would be part of the engine.
     */
    static class MockModuleRegistrationService extends ModuleRegistrationGrpc.ModuleRegistrationImplBase {
        
        private final ConsulClient consulClient;
        private final String clusterName;
        
        MockModuleRegistrationService(ConsulClient consulClient, String clusterName) {
            this.consulClient = consulClient;
            this.clusterName = clusterName;
        }
        
        @Override
        public void registerModule(ModuleInfo request, StreamObserver<RegistrationStatus> responseObserver) {
            try {
                // Register in Consul using our test cluster helper
                TestClusterHelper.registerServiceInCluster(
                    consulClient,
                    clusterName,
                    request.getServiceName(),
                    request.getServiceId(),
                    request.getHost(),
                    request.getPort(),
                    request.getMetadataMap()
                );
                
                RegistrationStatus status = RegistrationStatus.newBuilder()
                    .setSuccess(true)
                    .setMessage("Successfully registered " + request.getServiceName() + " in cluster " + clusterName)
                    .setConsulServiceId(clusterName + "-" + request.getServiceId())
                    .build();
                
                responseObserver.onNext(status);
                responseObserver.onCompleted();
                
            } catch (Exception e) {
                logger.error("Failed to register module", e);
                
                RegistrationStatus errorStatus = RegistrationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Registration failed: " + e.getMessage())
                    .build();
                
                responseObserver.onNext(errorStatus);
                responseObserver.onCompleted();
            }
        }
        
        @Override
        public void listModules(Empty request, StreamObserver<com.krickert.search.grpc.ModuleList> responseObserver) {
            try {
                var moduleListBuilder = com.krickert.search.grpc.ModuleList.newBuilder();
                
                // Get all services in this cluster
                consulClient.getAgentServices().getValue().forEach((id, service) -> {
                    if (service.getMeta() != null && clusterName.equals(service.getMeta().get("cluster"))) {
                        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                            .setServiceId(id)
                            .setServiceName(service.getService())
                            .setHost(service.getAddress())
                            .setPort(service.getPort())
                            .putAllMetadata(service.getMeta())
                            .build();
                        
                        moduleListBuilder.addModules(moduleInfo);
                    }
                });
                
                responseObserver.onNext(moduleListBuilder.build());
                responseObserver.onCompleted();
                
            } catch (Exception e) {
                logger.error("Failed to list modules", e);
                responseObserver.onError(e);
            }
        }
    }
}