package com.krickert.search.pipeline.module;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for ModuleDiscoveryController REST API endpoints.
 */
@MicronautTest(environments = {"test"})
@Property(name = "yappy.module.discovery.enabled", value = "true")
@Property(name = "yappy.module.test.enabled", value = "true")
class ModuleDiscoveryControllerIT {
    
    @Inject
    @Client("/api/modules")
    HttpClient client;
    
    @Inject
    ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    ModuleSchemaRegistryService schemaRegistryService;
    
    @MockBean(ConsulBusinessOperationsService.class)
    ConsulBusinessOperationsService consulService() {
        return mock(ConsulBusinessOperationsService.class);
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
                .addService(new TestModuleWithSchema())
                .build()
                .start();
        
        testChannel = io.grpc.ManagedChannelBuilder
                .forAddress("localhost", serverPort)
                .usePlaintext()
                .build();
        
        // Setup mock for module discovery
        Map<String, List<String>> services = new HashMap<>();
        services.put("test-api-module", List.of("yappy-module"));
        
        when(mockConsulService.listServices()).thenReturn(Mono.just(services));
        when(mockConsulService.getHealthyServiceInstances("test-api-module"))
                .thenReturn(Mono.just(List.of(createHealthyInstance())));
        when(mockChannelManager.getChannel("test-api-module")).thenReturn(testChannel);
        
        // Discover modules to populate the service
        moduleDiscoveryService.discoverAndRegisterModules();
        
        // Wait for async discovery
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
    void testDiscoverModules() {
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.POST("/discover", null),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("Module discovery initiated", response.body().get("message"));
    }
    
    @Test
    void testListModules() {
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.GET("/"),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        List<Map> modules = (List<Map>) response.body().get("modules");
        assertNotNull(modules);
        assertEquals(1, modules.size());
        
        Map module = modules.get(0);
        assertEquals("test-api-module", module.get("serviceName"));
        assertEquals("api-test-module", module.get("pipeName"));
        assertEquals("READY", module.get("status"));
    }
    
    @Test
    void testGetModuleStatus() {
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.GET("/test-api-module/status"),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("test-api-module", response.body().get("moduleName"));
        assertEquals("READY", response.body().get("status"));
        assertNotNull(response.body().get("lastHealthCheck"));
    }
    
    @Test
    void testGetModuleStatus_NotFound() {
        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/non-existent-module/status"),
                    Map.class
            );
            fail("Expected 404 Not Found");
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
            assertTrue(e.getMessage().contains("Module not found"));
        }
    }
    
    @Test
    void testTestModule() {
        Map<String, Object> testRequest = new HashMap<>();
        testRequest.put("content", "Test content");
        
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.POST("/test-api-module/test", testRequest),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue((Boolean) response.body().get("success"));
        assertNotNull(response.body().get("testResults"));
    }
    
    @Test
    void testGetModuleSchema() {
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.GET("/api-test-module/schema"),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("api-test-module", response.body().get("moduleName"));
        assertNotNull(response.body().get("schema"));
        
        String schema = (String) response.body().get("schema");
        assertTrue(schema.contains("$schema"));
        assertTrue(schema.contains("timeout"));
    }
    
    @Test
    void testGetModuleSchema_NotFound() {
        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/non-existent-module/schema"),
                    Map.class
            );
            fail("Expected 404 Not Found");
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
            assertTrue(e.getMessage().contains("No schema found"));
        }
    }
    
    @Test
    void testGetDefaultConfig() {
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.GET("/api-test-module/default-config"),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("api-test-module", response.body().get("moduleName"));
        assertNotNull(response.body().get("defaultConfig"));
        
        String config = (String) response.body().get("defaultConfig");
        assertTrue(config.contains("\"timeout\":30000"));
        assertTrue(config.contains("\"retryCount\":3"));
    }
    
    @Test
    void testValidateConfig() {
        Map<String, Object> validConfig = new HashMap<>();
        validConfig.put("timeout", 60000);
        validConfig.put("retryCount", 5);
        
        Map<String, Object> request = new HashMap<>();
        request.put("configuration", validConfig);
        
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.POST("/api-test-module/validate-config", request),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue((Boolean) response.body().get("valid"));
        assertEquals("Configuration is valid", response.body().get("message"));
    }
    
    @Test
    void testValidateConfig_Invalid() {
        Map<String, Object> invalidConfig = new HashMap<>();
        invalidConfig.put("timeout", "not a number");
        invalidConfig.put("retryCount", -1);
        
        Map<String, Object> request = new HashMap<>();
        request.put("configuration", invalidConfig);
        
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.POST("/api-test-module/validate-config", request),
                Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertFalse((Boolean) response.body().get("valid"));
        assertTrue(((String) response.body().get("message")).contains("validation failed"));
    }
    
    // Helper classes
    
    private static class TestModuleWithSchema extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            
            String schema = """
                {
                    "$schema": "http://json-schema.org/draft-07/schema#",
                    "type": "object",
                    "properties": {
                        "timeout": {
                            "type": "integer",
                            "minimum": 1000,
                            "maximum": 120000,
                            "default": 30000
                        },
                        "retryCount": {
                            "type": "integer",
                            "minimum": 0,
                            "maximum": 10,
                            "default": 3
                        }
                    },
                    "required": ["timeout"]
                }
                """;
            
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("api-test-module")
                    .putContextParams("json_config_schema", schema)
                    .putContextParams("description", "Test module for API testing")
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
                    .addProcessorLogs("API test processing completed")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
        
        @Override
        public void testMode(TestRequest request,
                StreamObserver<TestResponse> responseObserver) {
            Map<String, String> results = new HashMap<>();
            results.put("testType", "API Integration");
            results.put("status", "passed");
            
            TestResponse response = TestResponse.newBuilder()
                    .setSuccess(true)
                    .putAllTestResults(results)
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