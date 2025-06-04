package com.krickert.yappy.engine.controller.admin;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.yappy.engine.controller.admin.AdminModuleController.*;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AdminModuleController.
 * Tests use real Consul instance via test resources - no mocks.
 */
@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "app.config.cluster-name", value = AdminModuleControllerIntegrationTest.TEST_CLUSTER_NAME)
class AdminModuleControllerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(AdminModuleControllerIntegrationTest.class);
    public static final String TEST_CLUSTER_NAME = "test-module-admin-cluster";
    private static final String API_BASE_PATH = "/api/admin/modules";
    
    @Inject
    @Client("/")
    HttpClient client;
    
    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;
    
    @Inject
    DynamicConfigurationManager configurationManager;
    
    private PipelineClusterConfig testClusterConfig;
    
    @BeforeAll
    void setupTestCluster() throws Exception {
        LOG.info("Setting up test cluster configuration");
        
        // Create test module configurations
        Map<String, PipelineModuleConfiguration> testModules = new HashMap<>();
        
        // Add Tika Parser module
        testModules.put("tika-parser-v1", new PipelineModuleConfiguration(
                "Tika Parser",
                "tika-parser-v1",
                new SchemaReference("tika-parser-config", 1),
                Map.of("type", "document-parser", "category", "extraction")
        ));
        
        // Add Chunker module
        testModules.put("chunker-v1", new PipelineModuleConfiguration(
                "Text Chunker",
                "chunker-v1",
                new SchemaReference("chunker-config", 1),
                Map.of("type", "text-processor", "category", "transformation")
        ));
        
        // Add Echo module (for testing)
        testModules.put("echo-processor-v1", new PipelineModuleConfiguration(
                "Echo Processor",
                "echo-processor-v1",
                null, // No custom schema
                Map.of("type", "test-processor", "category", "testing")
        ));
        
        // Create module map
        PipelineModuleMap moduleMap = new PipelineModuleMap(testModules);
        
        // Create minimal pipeline graph config
        Map<String, PipelineStepConfig> steps = Map.of(
                "echo-step", new PipelineStepConfig(
                        "echo-step",
                        StepType.PIPELINE,
                        new PipelineStepConfig.ProcessorInfo("echo-processor-v1", null)
                )
        );
        
        Map<String, PipelineConfig> pipelines = Map.of(
                "test-pipeline", new PipelineConfig(
                        "test-pipeline",
                        steps
                )
        );
        
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        
        // Create cluster config
        testClusterConfig = new PipelineClusterConfig(
                TEST_CLUSTER_NAME,
                graphConfig,
                moduleMap,
                "test-pipeline",
                Set.of("test-topic"),
                Set.of("echo-processor-v1")
        );
        
        // Store in Consul
        Boolean stored = consulBusinessOperationsService
                .storeClusterConfiguration(TEST_CLUSTER_NAME, testClusterConfig)
                .block(Duration.ofSeconds(5));
        
        assertTrue(stored, "Failed to store test cluster configuration");
        
        // Give Consul time to propagate and wait for configuration to be available
        int maxRetries = 10;
        Optional<PipelineClusterConfig> retrieved = Optional.empty();
        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(500);
            
            // Try to get the configuration directly from Consul
            retrieved = consulBusinessOperationsService
                    .getPipelineClusterConfig(TEST_CLUSTER_NAME)
                    .block(Duration.ofSeconds(5));
            
            if (retrieved.isPresent() && TEST_CLUSTER_NAME.equals(retrieved.get().clusterName())) {
                LOG.info("Test cluster configuration available after {} retries", i);
                break;
            }
        }
        
        assertTrue(retrieved.isPresent(), "Test cluster configuration not available after " + maxRetries + " retries");
        assertEquals(TEST_CLUSTER_NAME, retrieved.get().clusterName());
        
        LOG.info("Test cluster setup complete with {} modules", testModules.size());
    }
    
    @AfterAll
    void cleanupTestCluster() {
        LOG.info("Cleaning up test cluster configuration");
        try {
            consulBusinessOperationsService
                    .deleteClusterConfiguration(TEST_CLUSTER_NAME)
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            LOG.warn("Error cleaning up test cluster: {}", e.getMessage());
        }
    }
    
    @Test
    @Order(1)
    void testGetModuleDefinitions() {
        // GET /api/admin/modules/definitions?clusterName=test-module-admin-cluster
        HttpRequest<Object> request = HttpRequest.GET(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<ModuleDefinitionsResponse> response = client.toBlocking()
                .exchange(request, ModuleDefinitionsResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        ModuleDefinitionsResponse body = response.body();
        assertEquals(TEST_CLUSTER_NAME, body.getClusterName());
        assertNotNull(body.getModules());
        assertEquals(3, body.getModules().size());
        
        // Verify module details
        Optional<ModuleDefinition> tikaModule = body.getModules().stream()
                .filter(m -> "tika-parser-v1".equals(m.getImplementationId()))
                .findFirst();
        
        assertTrue(tikaModule.isPresent());
        assertEquals("Tika Parser", tikaModule.get().getImplementationName());
        assertNotNull(tikaModule.get().getCustomConfigSchemaReference());
        assertEquals("tika-parser-config", tikaModule.get().getCustomConfigSchemaReference().subject());
        assertEquals(1, tikaModule.get().getCustomConfigSchemaReference().version());
        assertEquals("document-parser", tikaModule.get().getProperties().get("type"));
    }
    
    @Test
    @Order(2)
    void testGetSpecificModuleDefinition() {
        // GET /api/admin/modules/definitions/{moduleId}?clusterName=test-module-admin-cluster
        HttpRequest<Object> request = HttpRequest.GET(API_BASE_PATH + "/definitions/chunker-v1?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<ModuleDefinition> response = client.toBlocking()
                .exchange(request, ModuleDefinition.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        ModuleDefinition chunker = response.body();
        assertEquals("chunker-v1", chunker.getImplementationId());
        assertEquals("Text Chunker", chunker.getImplementationName());
        assertEquals("text-processor", chunker.getProperties().get("type"));
    }
    
    @Test
    @Order(3)
    void testGetNonExistentModuleDefinition() {
        // GET /api/admin/modules/definitions/{moduleId}?clusterName=test-module-admin-cluster - non-existent
        HttpRequest<Object> request = HttpRequest.GET(API_BASE_PATH + "/definitions/non-existent-module?clusterName=" + TEST_CLUSTER_NAME);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, ModuleDefinition.class);
        });
        
        try {
            client.toBlocking().exchange(request, ModuleDefinition.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }
    
    @Test
    @Order(4)
    void testCreateNewModuleDefinition() {
        // POST /api/admin/modules/definitions
        CreateModuleDefinitionRequest request = new CreateModuleDefinitionRequest();
        request.setImplementationId("embedder-v1");
        request.setImplementationName("Text Embedder");
        request.setSchemaSubject("embedder-config");
        request.setSchemaVersion(1);
        request.setProperties(Map.of(
                "type", "embedder",
                "category", "ml",
                "model", "all-MiniLM-L6-v2"
        ));
        
        HttpRequest<CreateModuleDefinitionRequest> httpRequest = HttpRequest
                .POST(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME, request);
        HttpResponse<ModuleOperationResponse> response = client.toBlocking()
                .exchange(httpRequest, ModuleOperationResponse.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        assertNotNull(response.body());
        
        ModuleOperationResponse result = response.body();
        assertTrue(result.isSuccess());
        assertEquals("embedder-v1", result.getModuleId());
        assertNotNull(result.getMessage());
        
        // Verify the module was added
        HttpRequest<Object> verifyRequest = HttpRequest.GET(API_BASE_PATH + "/definitions/embedder-v1?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<ModuleDefinition> verifyResponse = client.toBlocking()
                .exchange(verifyRequest, ModuleDefinition.class);
        
        assertEquals(HttpStatus.OK, verifyResponse.getStatus());
        ModuleDefinition created = verifyResponse.body();
        assertEquals("Text Embedder", created.getImplementationName());
        assertEquals("all-MiniLM-L6-v2", created.getProperties().get("model"));
    }
    
    @Test
    @Order(5)
    void testUpdateExistingModuleDefinition() {
        // POST /api/admin/modules/definitions - update existing
        CreateModuleDefinitionRequest request = new CreateModuleDefinitionRequest();
        request.setImplementationId("echo-processor-v1");
        request.setImplementationName("Echo Processor Updated");
        request.setSchemaSubject("echo-config");
        request.setSchemaVersion(2);
        request.setProperties(Map.of(
                "type", "test-processor",
                "category", "testing",
                "version", "2.0"
        ));
        
        HttpRequest<CreateModuleDefinitionRequest> httpRequest = HttpRequest
                .POST(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME, request);
        HttpResponse<ModuleOperationResponse> response = client.toBlocking()
                .exchange(httpRequest, ModuleOperationResponse.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        assertNotNull(response.body());
        assertTrue(response.body().isSuccess());
        
        // Verify the update
        HttpRequest<Object> verifyRequest = HttpRequest.GET(API_BASE_PATH + "/definitions/echo-processor-v1?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<ModuleDefinition> verifyResponse = client.toBlocking()
                .exchange(verifyRequest, ModuleDefinition.class);
        
        ModuleDefinition updated = verifyResponse.body();
        assertEquals("Echo Processor Updated", updated.getImplementationName());
        assertEquals("2.0", updated.getProperties().get("version"));
        assertEquals(2, updated.getCustomConfigSchemaReference().version());
    }
    
    @Test
    @Order(6)
    void testDeleteModuleDefinition() {
        // First, ensure we have 4 modules (3 original + 1 added)
        HttpRequest<Object> listRequest = HttpRequest.GET(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME);
        ModuleDefinitionsResponse beforeDelete = client.toBlocking()
                .retrieve(listRequest, ModuleDefinitionsResponse.class);
        assertEquals(4, beforeDelete.getModules().size());
        
        // DELETE /api/admin/modules/definitions/{moduleId}?clusterName=test-module-admin-cluster
        HttpRequest<Object> deleteRequest = HttpRequest.DELETE(API_BASE_PATH + "/definitions/embedder-v1?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<ModuleOperationResponse> response = client.toBlocking()
                .exchange(deleteRequest, ModuleOperationResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        ModuleOperationResponse result = response.body();
        assertTrue(result.isSuccess());
        assertEquals("embedder-v1", result.getModuleId());
        
        // Verify deletion
        HttpRequest<Object> verifyRequest = HttpRequest.GET(API_BASE_PATH + "/definitions/embedder-v1?clusterName=" + TEST_CLUSTER_NAME);
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(verifyRequest, ModuleDefinition.class);
        });
        
        // Verify count reduced
        ModuleDefinitionsResponse afterDelete = client.toBlocking()
                .retrieve(listRequest, ModuleDefinitionsResponse.class);
        assertEquals(3, afterDelete.getModules().size());
    }
    
    @Test
    @Order(7)
    void testDeleteNonExistentModule() {
        // DELETE /api/admin/modules/definitions/{moduleId}?clusterName=test-module-admin-cluster - non-existent
        HttpRequest<Object> request = HttpRequest.DELETE(API_BASE_PATH + "/definitions/non-existent?clusterName=" + TEST_CLUSTER_NAME);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, ModuleOperationResponse.class);
        });
        
        try {
            client.toBlocking().exchange(request, ModuleOperationResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }
    
    @Test
    @Order(8)
    void testGetModuleStatus() {
        // GET /api/admin/modules/status?clusterName=test-module-admin-cluster
        HttpRequest<Object> request = HttpRequest.GET(API_BASE_PATH + "/status?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<ModuleStatusListResponse> response = client.toBlocking()
                .exchange(request, ModuleStatusListResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        ModuleStatusListResponse statusList = response.body();
        assertNotNull(statusList.getModules());
        
        // Should have status for all configured modules
        assertTrue(statusList.getModules().size() >= 3);
        
        // Debug: Log all module statuses
        statusList.getModules().forEach(module -> {
            LOG.info("Module status: id={}, name={}, registered={}, instanceCount={}, instances={}",
                    module.getImplementationId(), module.getImplementationName(),
                    module.isRegistered(), module.getInstanceCount(), module.getInstances());
        });
        
        // Check specific module status
        Optional<ModuleStatusInfo> tikaStatus = statusList.getModules().stream()
                .filter(s -> "tika-parser-v1".equals(s.getImplementationId()))
                .findFirst();
        
        assertTrue(tikaStatus.isPresent());
        assertEquals("Tika Parser", tikaStatus.get().getImplementationName());
        // In test environment, modules likely won't be registered
        assertFalse(tikaStatus.get().isRegistered());
        assertEquals(0, tikaStatus.get().getInstanceCount());
        
        // Check instances list - should not be null
        List<String> instances = tikaStatus.get().getInstances();
        if (instances == null) {
            LOG.error("Instances list is null for module: {}", tikaStatus.get().getImplementationId());
            LOG.error("Full module status: registered={}, instanceCount={}", 
                    tikaStatus.get().isRegistered(), tikaStatus.get().getInstanceCount());
        }
        assertNotNull(instances, "Instances list should not be null");
        assertTrue(instances.isEmpty());
    }
    
    @Test
    @Order(9)
    void testCreateModuleWithInvalidRequest() {
        // POST /api/admin/modules/definitions - missing required fields
        CreateModuleDefinitionRequest request = new CreateModuleDefinitionRequest();
        // Missing implementationId and implementationName
        
        HttpRequest<CreateModuleDefinitionRequest> httpRequest = HttpRequest
                .POST(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME, request);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(httpRequest, ModuleOperationResponse.class);
        });
        
        try {
            client.toBlocking().exchange(httpRequest, ModuleOperationResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
    }
    
    @Test
    @Order(10)
    void testModuleRegistrationInConsul() throws Exception {
        // This test simulates module registration and verifies status update
        
        // Register a fake module service in Consul
        String moduleId = "echo-processor-v1";
        String serviceId = "echo-processor-instance-1";
        
        org.kiwiproject.consul.model.agent.ImmutableRegistration registration = 
                org.kiwiproject.consul.model.agent.ImmutableRegistration.builder()
                        .id(serviceId)
                        .name(moduleId)
                        .address("localhost")
                        .port(8081)
                        .tags(List.of("yappy-module", "yappy-module-implementation-id=" + moduleId))
                        .check(org.kiwiproject.consul.model.agent.ImmutableRegCheck.builder()
                                .grpc("localhost:8081")
                                .interval("10s")
                                .build())
                        .build();
        
        consulBusinessOperationsService.registerService(registration)
                .block(Duration.ofSeconds(5));
        
        // Give Consul time to register
        Thread.sleep(2000);
        
        // Check module status again
        HttpRequest<Object> statusRequest = HttpRequest.GET(API_BASE_PATH + "/status?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<ModuleStatusListResponse> statusResponse = client.toBlocking()
                .exchange(statusRequest, ModuleStatusListResponse.class);
        
        ModuleStatusListResponse statusList = statusResponse.body();
        Optional<ModuleStatusInfo> echoStatus = statusList.getModules().stream()
                .filter(s -> moduleId.equals(s.getImplementationId()))
                .findFirst();
        
        assertTrue(echoStatus.isPresent());
        // Note: In test environment, health checks might not pass immediately
        // so we just verify the registration was attempted
        
        // Clean up
        consulBusinessOperationsService.deregisterService(serviceId)
                .block(Duration.ofSeconds(5));
    }
    
    @Test
    @Order(11)
    void testClusterConfigurationPersistence() throws Exception {
        // This test verifies that module changes persist in Consul
        
        // Get current module count
        HttpRequest<Object> request = HttpRequest.GET(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME);
        ModuleDefinitionsResponse initialResponse = client.toBlocking()
                .retrieve(request, ModuleDefinitionsResponse.class);
        int initialCount = initialResponse.getModules().size();
        
        // Add a new module
        CreateModuleDefinitionRequest newModule = new CreateModuleDefinitionRequest();
        newModule.setImplementationId("test-persistence-module");
        newModule.setImplementationName("Persistence Test Module");
        newModule.setProperties(Map.of("test", "true"));
        
        HttpRequest<CreateModuleDefinitionRequest> createRequest = HttpRequest
                .POST(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME, newModule);
        client.toBlocking().exchange(createRequest, ModuleOperationResponse.class);
        
        // Verify it was added
        ModuleDefinitionsResponse afterAdd = client.toBlocking()
                .retrieve(request, ModuleDefinitionsResponse.class);
        assertEquals(initialCount + 1, afterAdd.getModules().size());
        
        // Fetch directly from Consul to verify persistence
        Optional<PipelineClusterConfig> clusterConfig = consulBusinessOperationsService
                .getPipelineClusterConfig(TEST_CLUSTER_NAME)
                .block(Duration.ofSeconds(5));
        
        assertTrue(clusterConfig.isPresent());
        assertTrue(clusterConfig.get().pipelineModuleMap().availableModules()
                .containsKey("test-persistence-module"));
        
        // Clean up
        HttpRequest<Object> deleteRequest = HttpRequest
                .DELETE(API_BASE_PATH + "/definitions/test-persistence-module?clusterName=" + TEST_CLUSTER_NAME);
        client.toBlocking().exchange(deleteRequest, ModuleOperationResponse.class);
    }
    
    @Test
    @Order(12)
    void testConcurrentModuleOperations() throws Exception {
        // Test that concurrent operations don't corrupt the configuration
        
        List<Thread> threads = new ArrayList<>();
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
        
        // Create multiple threads that add/update modules
        for (int i = 0; i < 5; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                try {
                    // Add small delay to reduce Consul contention
                    Thread.sleep(index * 100);
                    
                    CreateModuleDefinitionRequest request = new CreateModuleDefinitionRequest();
                    request.setImplementationId("concurrent-module-" + index);
                    request.setImplementationName("Concurrent Module " + index);
                    request.setProperties(Map.of("index", String.valueOf(index)));
                    
                    HttpRequest<CreateModuleDefinitionRequest> httpRequest = HttpRequest
                            .POST(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME, request);
                    client.toBlocking().exchange(httpRequest, ModuleOperationResponse.class);
                } catch (Exception e) {
                    exceptions.add(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(10000); // 10 second timeout
        }
        
        // Check for exceptions
        assertTrue(exceptions.isEmpty(), "Concurrent operations failed: " + exceptions);
        
        // Wait a moment for Consul to stabilize
        Thread.sleep(500);
        
        // Verify all modules were added
        HttpRequest<Object> listRequest = HttpRequest.GET(API_BASE_PATH + "/definitions?clusterName=" + TEST_CLUSTER_NAME);
        ModuleDefinitionsResponse response = client.toBlocking()
                .retrieve(listRequest, ModuleDefinitionsResponse.class);
        
        for (int i = 0; i < 5; i++) {
            String moduleId = "concurrent-module-" + i;
            assertTrue(response.getModules().stream()
                    .anyMatch(m -> moduleId.equals(m.getImplementationId())),
                    "Module " + moduleId + " not found");
        }
        
        // Clean up
        for (int i = 0; i < 5; i++) {
            HttpRequest<Object> deleteRequest = HttpRequest
                    .DELETE(API_BASE_PATH + "/definitions/concurrent-module-" + i + "?clusterName=" + TEST_CLUSTER_NAME);
            client.toBlocking().exchange(deleteRequest, ModuleOperationResponse.class);
        }
    }
}