package com.krickert.search.pipeline.module;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration test for module discovery with schema validation and registration.
 */
@MicronautTest(environments = {"test"})
@Property(name = "yappy.module.discovery.enabled", value = "true")
@Property(name = "yappy.module.test.enabled", value = "true")
class ModuleDiscoveryWithSchemaIT {
    
    @Inject
    ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    ModuleSchemaRegistryService schemaRegistryService;
    
    @Inject
    ModuleSchemaValidator schemaValidator;
    
    @MockBean(ConsulBusinessOperationsService.class)
    ConsulBusinessOperationsService consulService() {
        ConsulBusinessOperationsService mock = mock(ConsulBusinessOperationsService.class);
        // Setup default stubbing to prevent NPE during application startup
        lenient().when(mock.listServices()).thenReturn(Mono.just(new HashMap<>()));
        lenient().when(mock.getHealthyServiceInstances(anyString())).thenReturn(Mono.just(List.of()));
        lenient().when(mock.getPipelineClusterConfig(anyString())).thenReturn(Mono.empty());
        // Fix NPE in EngineRegistrationService
        lenient().when(mock.registerService(any())).thenReturn(Mono.empty());
        return mock;
    }
    
    @MockBean(GrpcChannelManager.class)
    GrpcChannelManager channelManager() {
        return mock(GrpcChannelManager.class);
    }
    
    @Inject
    ConsulBusinessOperationsService mockConsulService;
    
    @Inject
    GrpcChannelManager mockChannelManager;
    
    private Server grpcServer;
    private ManagedChannel testChannel;
    private int serverPort;
    
    @BeforeEach
    void setUp() throws IOException {
        // Find available port
        try (ServerSocket socket = new ServerSocket(0)) {
            serverPort = socket.getLocalPort();
        }
        
        // Create a test module with schema
        grpcServer = ServerBuilder
                .forPort(serverPort)
                .addService(new ModuleWithSchema())
                .build()
                .start();
        
        testChannel = io.grpc.ManagedChannelBuilder
                .forAddress("localhost", serverPort)
                .usePlaintext()
                .build();
    }
    
    @AfterEach
    void tearDown() throws InterruptedException {
        if (testChannel != null) {
            testChannel.shutdown();
            testChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        if (grpcServer != null) {
            grpcServer.shutdown();
            grpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void testModuleDiscoveryWithSchemaRegistration() throws InterruptedException {
        // Reset mocks and setup test-specific behavior
        reset(mockConsulService, mockChannelManager);
        
        // Create test data first to avoid nesting in stubbing
        var healthyInstance = createHealthyInstance();
        Map<String, List<String>> services = new HashMap<>();
        services.put("schema-test-module", List.of("yappy-module"));
        
        when(mockConsulService.listServices()).thenReturn(Mono.just(services));
        when(mockConsulService.getHealthyServiceInstances("schema-test-module"))
                .thenReturn(Mono.just(List.of(healthyInstance)));
        when(mockChannelManager.getChannel("schema-test-module")).thenReturn(testChannel);
        
        // Execute discovery
        moduleDiscoveryService.discoverAndRegisterModules();
        
        // Wait for async schema registration
        Thread.sleep(1000);
        
        // Verify module was discovered
        assertTrue(moduleDiscoveryService.isModuleAvailable("schema-test-module"));
        
        // Verify schema was registered
        assertTrue(schemaRegistryService.hasSchema("configurable-module"));
        
        // Get the schema
        String schema = schemaRegistryService.getModuleSchema("configurable-module");
        assertNotNull(schema);
        assertTrue(schema.contains("chunk_size"));
        assertTrue(schema.contains("preserve_formatting"));
        
        // Test default configuration
        String defaultConfig = schemaRegistryService.getDefaultConfiguration("configurable-module");
        assertNotNull(defaultConfig);
        assertTrue(defaultConfig.contains("\"chunk_size\":500"));
        assertTrue(defaultConfig.contains("\"preserve_formatting\":true"));
        
        // Test configuration validation
        String validConfig = """
            {
                "chunk_size": 1000,
                "preserve_formatting": false
            }
            """;
        
        var validationResult = schemaRegistryService.validateModuleConfiguration(
                "configurable-module", validConfig);
        assertTrue(validationResult.isValid());
        
        // Test invalid configuration
        String invalidConfig = """
            {
                "chunk_size": "not a number"
            }
            """;
        
        var invalidResult = schemaRegistryService.validateModuleConfiguration(
                "configurable-module", invalidConfig);
        assertFalse(invalidResult.isValid());
        assertTrue(invalidResult.getMessage().contains("validation failed"));
    }
    
    @Test
    void testModuleWithInvalidSchema() throws InterruptedException {
        // Setup module with invalid schema
        Server invalidSchemaServer = null;
        try {
            int port = serverPort + 1;
            try {
                invalidSchemaServer = ServerBuilder
                        .forPort(port)
                        .addService(new ModuleWithInvalidSchema())
                        .build()
                        .start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start test server", e);
            }
            
            ManagedChannel channel = io.grpc.ManagedChannelBuilder
                    .forAddress("localhost", port)
                    .usePlaintext()
                    .build();
            
            // Reset mocks and setup test-specific behavior
            reset(mockConsulService, mockChannelManager);
            
            // Create test data first to avoid nesting in stubbing
            var healthyInstance = createHealthyInstance();
            Map<String, List<String>> services = new HashMap<>();
            services.put("invalid-schema-module", List.of("yappy-module"));
            
            when(mockConsulService.listServices()).thenReturn(Mono.just(services));
            when(mockConsulService.getHealthyServiceInstances("invalid-schema-module"))
                    .thenReturn(Mono.just(List.of(healthyInstance)));
            when(mockChannelManager.getChannel("invalid-schema-module")).thenReturn(channel);
            
            // Execute discovery
            moduleDiscoveryService.discoverAndRegisterModules();
            
            // Wait for async processing
            Thread.sleep(1000);
            
            // Module should be discovered but schema should not be registered
            assertTrue(moduleDiscoveryService.isModuleAvailable("invalid-schema-module"));
            assertFalse(schemaRegistryService.hasSchema("invalid-schema-module"));
            
            channel.shutdown();
            channel.awaitTermination(5, TimeUnit.SECONDS);
            
        } finally {
            if (invalidSchemaServer != null) {
                invalidSchemaServer.shutdown();
                invalidSchemaServer.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }
    
    // Helper classes
    
    private static class ModuleWithSchema extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            
            String schema = """
                {
                    "$schema": "http://json-schema.org/draft-07/schema#",
                    "type": "object",
                    "properties": {
                        "chunk_size": {
                            "type": "integer",
                            "minimum": 100,
                            "maximum": 5000,
                            "default": 500
                        },
                        "preserve_formatting": {
                            "type": "boolean",
                            "default": true
                        },
                        "separator": {
                            "type": "string",
                            "default": "\\n\\n"
                        }
                    }
                }
                """;
            
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("configurable-module")
                    .putContextParams("json_config_schema", schema)
                    .putContextParams("description", "Module with configuration schema")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .addProcessorLogs("Test processing completed")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    private static class ModuleWithInvalidSchema extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            
            // Invalid schema - missing $schema property
            String invalidSchema = """
                {
                    "type": "object",
                    "properties": {}
                }
                """;
            
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("invalid-schema-module")
                    .putContextParams("json_config_schema", invalidSchema)
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    private org.kiwiproject.consul.model.health.ServiceHealth createHealthyInstance() {
        org.kiwiproject.consul.model.health.ServiceHealth health = 
                mock(org.kiwiproject.consul.model.health.ServiceHealth.class);
        org.kiwiproject.consul.model.health.Service service = 
                mock(org.kiwiproject.consul.model.health.Service.class);
        
        when(service.getAddress()).thenReturn("localhost");
        when(service.getPort()).thenReturn(serverPort);
        when(health.getService()).thenReturn(service);
        
        return health;
    }
}