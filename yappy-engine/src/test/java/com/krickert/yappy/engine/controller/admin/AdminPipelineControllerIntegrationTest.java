package com.krickert.yappy.engine.controller.admin;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.yappy.engine.controller.admin.AdminPipelineController.*;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AdminPipelineController.
 * Tests use real Consul instance via test resources - no mocks.
 */
@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "app.config.cluster-name", value = AdminPipelineControllerIntegrationTest.TEST_CLUSTER_NAME)
class AdminPipelineControllerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(AdminPipelineControllerIntegrationTest.class);
    public static final String TEST_CLUSTER_NAME = "test-pipeline-admin-cluster";
    private static final String API_BASE_PATH = "/api/admin/pipelines";
    
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
        
        // Create test pipelines
        Map<String, PipelineStepConfig> simpleSteps = Map.of(
                "input", PipelineStepConfig.builder()
                        .stepName("input")
                        .stepType(StepType.INITIAL_PIPELINE)
                        .kafkaInputs(List.of(KafkaInputDefinition.builder()
                                .listenTopics(List.of("input-topic"))
                                .build()))
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("tika-parser")
                                .build())
                        .outputs(Map.of("default", 
                                PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName("processor")
                                        .transportType(TransportType.GRPC)
                                        .grpcTransport(GrpcTransportConfig.builder()
                                                .serviceName("main-engine")
                                                .build())
                                        .build()))
                        .build(),
                "processor", PipelineStepConfig.builder()
                        .stepName("processor")
                        .stepType(StepType.PIPELINE)
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("tika-parser")
                                .build())
                        .outputs(Map.of("parsed", 
                                PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName("output")
                                        .transportType(TransportType.KAFKA)
                                        .kafkaTransport(KafkaTransportConfig.builder()
                                                .topic("output-topic")
                                                .build())
                                        .build()))
                        .build(),
                "output", PipelineStepConfig.builder()
                        .stepName("output")
                        .stepType(StepType.SINK)
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("opensearch-sink")
                                .build())
                        .outputs(Map.of())
                        .build()
        );
        
        PipelineConfig simplePipeline = PipelineConfig.builder()
                .name("simple-pipeline")
                .pipelineSteps(simpleSteps)
                .build();
        
        // Create another pipeline for testing
        Map<String, PipelineStepConfig> complexSteps = Map.of(
                "kafka-input", PipelineStepConfig.builder()
                        .stepName("kafka-input")
                        .stepType(StepType.INITIAL_PIPELINE)
                        .kafkaInputs(List.of(KafkaInputDefinition.builder()
                                .listenTopics(List.of("raw-documents"))
                                .build()))
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("tika-parser")
                                .build())
                        .outputs(Map.of("raw", 
                                PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName("tika")
                                        .transportType(TransportType.GRPC)
                                        .grpcTransport(GrpcTransportConfig.builder()
                                                .serviceName("main-engine")
                                                .build())
                                        .build()))
                        .build(),
                "tika", PipelineStepConfig.builder()
                        .stepName("tika")
                        .stepType(StepType.PIPELINE)
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("tika-parser")
                                .build())
                        .outputs(Map.of("parsed", 
                                PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName("chunker")
                                        .transportType(TransportType.GRPC)
                                        .grpcTransport(GrpcTransportConfig.builder()
                                                .serviceName("main-engine")
                                                .build())
                                        .build()))
                        .build(),
                "chunker", PipelineStepConfig.builder()
                        .stepName("chunker")
                        .stepType(StepType.PIPELINE)
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("text-chunker")
                                .build())
                        .outputs(Map.of("chunks", 
                                PipelineStepConfig.OutputTarget.builder()
                                        .targetStepName("sink")
                                        .transportType(TransportType.KAFKA)
                                        .kafkaTransport(KafkaTransportConfig.builder()
                                                .topic("chunked-output")
                                                .build())
                                        .build()))
                        .build(),
                "sink", PipelineStepConfig.builder()
                        .stepName("sink")
                        .stepType(StepType.SINK)
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("opensearch-sink")
                                .build())
                        .outputs(Map.of())
                        .build()
        );
        
        PipelineConfig complexPipeline = PipelineConfig.builder()
                .name("complex-pipeline")
                .pipelineSteps(complexSteps)
                .build();
        
        // Create pipeline graph
        Map<String, PipelineConfig> pipelines = Map.of(
                "simple-pipeline", simplePipeline,
                "complex-pipeline", complexPipeline
        );
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(pipelines)
                .build();
        
        // Create module map
        Map<String, PipelineModuleConfiguration> modules = Map.of(
                "tika-parser", PipelineModuleConfiguration.builder()
                        .implementationId("tika-parser")
                        .implementationName("Tika Parser Service")
                        .build(),
                "text-chunker", PipelineModuleConfiguration.builder()
                        .implementationId("text-chunker")
                        .implementationName("Text Chunker Service")
                        .build(),
                "opensearch-sink", PipelineModuleConfiguration.builder()
                        .implementationId("opensearch-sink")
                        .implementationName("OpenSearch Sink Service")
                        .build()
        );
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(modules)
                .build();
        
        // Create cluster config
        testClusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName("simple-pipeline")
                .allowedKafkaTopics(Set.of("input-topic", "raw-documents", "output-topic", "chunked-output", "error-topic"))
                .allowedGrpcServices(Set.of("tika-parser", "text-chunker", "opensearch-sink", "main-engine"))
                .build();
        
        // Store in Consul
        Boolean stored = consulBusinessOperationsService
                .storeClusterConfiguration(TEST_CLUSTER_NAME, testClusterConfig)
                .block(Duration.ofSeconds(5));
        
        assertTrue(stored, "Failed to store test cluster configuration");
        
        // Wait for configuration to be available
        Thread.sleep(1000);
        
        LOG.info("Test cluster setup complete with {} pipelines", pipelines.size());
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
    void testListPipelines() {
        // GET /api/admin/pipelines?clusterName=test-pipeline-admin-cluster
        HttpRequest<Object> request = HttpRequest.GET(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<PipelineListResponse> response = client.toBlocking()
                .exchange(request, PipelineListResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        PipelineListResponse body = response.body();
        assertEquals(TEST_CLUSTER_NAME, body.getClusterName());
        assertNotNull(body.getPipelines());
        assertEquals(2, body.getPipelines().size());
        assertEquals("simple-pipeline", body.getDefaultPipeline());
        
        // Verify pipeline summaries
        Optional<PipelineSummary> simplePipeline = body.getPipelines().stream()
                .filter(p -> "simple-pipeline".equals(p.getName()))
                .findFirst();
        
        assertTrue(simplePipeline.isPresent());
        assertEquals(3, simplePipeline.get().getStepCount());
        
        
        assertTrue(simplePipeline.get().isDefault());
        assertTrue(simplePipeline.get().getInputSteps().contains("input"));
        assertTrue(simplePipeline.get().getOutputSteps().contains("output"));
    }
    
    @Test
    @Order(2)
    void testGetSpecificPipeline() {
        // GET /api/admin/pipelines/{pipelineName}?clusterName=test-pipeline-admin-cluster
        HttpRequest<Object> request = HttpRequest.GET(
                API_BASE_PATH + "/complex-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<PipelineDetailResponse> response = client.toBlocking()
                .exchange(request, PipelineDetailResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        PipelineDetailResponse pipeline = response.body();
        assertEquals("complex-pipeline", pipeline.getName());
        assertFalse(pipeline.isDefault());
        assertNotNull(pipeline.getConfiguration());
        assertEquals(4, pipeline.getConfiguration().pipelineSteps().size());
        
        // Verify specific steps
        assertTrue(pipeline.getConfiguration().pipelineSteps().containsKey("tika"));
        PipelineStepConfig tikaStep = pipeline.getConfiguration().pipelineSteps().get("tika");
        assertEquals(StepType.PIPELINE, tikaStep.stepType());
        assertEquals("tika-parser", tikaStep.processorInfo().grpcServiceName());
    }
    
    @Test
    @Order(3)
    void testGetNonExistentPipeline() {
        // GET /api/admin/pipelines/{pipelineName}?clusterName=test-pipeline-admin-cluster - non-existent
        HttpRequest<Object> request = HttpRequest.GET(
                API_BASE_PATH + "/non-existent-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, PipelineDetailResponse.class);
        });
        
        try {
            client.toBlocking().exchange(request, PipelineDetailResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }
    
    @Test
    @Order(4)
    void testCreateNewPipeline() {
        // POST /api/admin/pipelines
        CreatePipelineRequest request = new CreatePipelineRequest();
        request.setPipelineName("test-new-pipeline");
        request.setSetAsDefault(false);
        
        // Create processor info objects
        PipelineStepConfig.ProcessorInfo sourceProcessor = new PipelineStepConfig.ProcessorInfo(
                "tika-parser", null);
        PipelineStepConfig.ProcessorInfo sinkProcessor = new PipelineStepConfig.ProcessorInfo(
                "opensearch-sink", null);
        
        // Create transport config
        GrpcTransportConfig grpcTransport = new GrpcTransportConfig("main-engine", null);
        
        // Create output target
        PipelineStepConfig.OutputTarget outputTarget = new PipelineStepConfig.OutputTarget(
                "sink", TransportType.GRPC, grpcTransport, null);
        
        // Create the step configs using the canonical constructor
        PipelineStepConfig sourceStep = new PipelineStepConfig(
                "source",                    // stepName
                StepType.INITIAL_PIPELINE,   // stepType
                null,                        // description
                null,                        // customConfigSchemaId
                null,                        // customConfig
                null,                        // kafkaInputs
                Map.of("data", outputTarget), // outputs
                null,                        // maxRetries
                null,                        // retryBackoffMs
                null,                        // maxRetryBackoffMs
                null,                        // retryBackoffMultiplier
                null,                        // stepTimeoutMs
                sourceProcessor              // processorInfo
        );
        
        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink",                      // stepName
                StepType.SINK,               // stepType
                null,                        // description
                null,                        // customConfigSchemaId
                null,                        // customConfig
                null,                        // kafkaInputs
                Map.of(),                    // outputs
                null,                        // maxRetries
                null,                        // retryBackoffMs
                null,                        // maxRetryBackoffMs
                null,                        // retryBackoffMultiplier
                null,                        // stepTimeoutMs
                sinkProcessor                // processorInfo
        );
        
        Map<String, PipelineStepConfig> steps = Map.of(
                "source", sourceStep,
                "sink", sinkStep
        );
        
        request.setPipelineSteps(steps);
        
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest
                .POST(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME, request);
        
        try {
            HttpResponse<PipelineOperationResponse> response = client.toBlocking()
                    .exchange(httpRequest, PipelineOperationResponse.class);
            
            assertEquals(HttpStatus.CREATED, response.getStatus());
            assertNotNull(response.body());
            
            PipelineOperationResponse result = response.body();
            assertTrue(result.isSuccess());
            assertEquals("test-new-pipeline", result.getPipelineName());
            
            // Verify the pipeline was created
            HttpRequest<Object> verifyRequest = HttpRequest.GET(
                    API_BASE_PATH + "/test-new-pipeline?clusterName=" + TEST_CLUSTER_NAME);
            HttpResponse<PipelineDetailResponse> verifyResponse = client.toBlocking()
                    .exchange(verifyRequest, PipelineDetailResponse.class);
            
            assertEquals(HttpStatus.OK, verifyResponse.getStatus());
            PipelineDetailResponse created = verifyResponse.body();
            assertEquals("test-new-pipeline", created.getName());
            assertEquals(2, created.getConfiguration().pipelineSteps().size());
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            System.err.println("HTTP error during test: " + e.getStatus() + " - " + e.getMessage());
            if (e.getResponse() != null && e.getResponse().getBody().isPresent()) {
                System.err.println("Response body: " + e.getResponse().getBody().get());
            }
            throw e;
        } catch (Exception e) {
            System.err.println("Exception during test: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            throw e;
        }
    }
    
    @Test
    @Order(5)
    void testCreateDuplicatePipeline() {
        // POST /api/admin/pipelines - duplicate name
        CreatePipelineRequest request = new CreatePipelineRequest();
        request.setPipelineName("simple-pipeline"); // Already exists
        request.setPipelineSteps(Map.of());
        
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest
                .POST(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME, request);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(httpRequest, PipelineOperationResponse.class);
        });
        
        try {
            client.toBlocking().exchange(httpRequest, PipelineOperationResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.CONFLICT, e.getStatus());
        }
    }
    
    @Test
    @Order(6)
    void testUpdateExistingPipeline() {
        // PUT /api/admin/pipelines/{pipelineName}
        UpdatePipelineRequest request = new UpdatePipelineRequest();
        
        // Add a new step to the pipeline
        Map<String, PipelineStepConfig> updatedSteps = new HashMap<>();
        updatedSteps.put("source", PipelineStepConfig.builder()
                .stepName("source")
                .stepType(StepType.INITIAL_PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("tika-parser")
                        .build())
                .outputs(Map.of("data", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("processor")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("main-engine")
                                        .build())
                                .build()))
                .build());
        updatedSteps.put("processor", PipelineStepConfig.builder()
                .stepName("processor")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("text-chunker")
                        .build())
                .outputs(Map.of("processed", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("sink")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("main-engine")
                                        .build())
                                .build()))
                .build());
        updatedSteps.put("sink", PipelineStepConfig.builder()
                .stepName("sink")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("opensearch-sink")
                        .build())
                .outputs(Map.of())
                .build());
        
        request.setPipelineSteps(updatedSteps);
        
        HttpRequest<UpdatePipelineRequest> httpRequest = HttpRequest
                .PUT(API_BASE_PATH + "/test-new-pipeline?clusterName=" + TEST_CLUSTER_NAME, request);
        HttpResponse<PipelineOperationResponse> response = client.toBlocking()
                .exchange(httpRequest, PipelineOperationResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue(response.body().isSuccess());
        
        // Verify the update
        HttpRequest<Object> verifyRequest = HttpRequest.GET(
                API_BASE_PATH + "/test-new-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<PipelineDetailResponse> verifyResponse = client.toBlocking()
                .exchange(verifyRequest, PipelineDetailResponse.class);
        
        PipelineDetailResponse updated = verifyResponse.body();
        assertEquals(3, updated.getConfiguration().pipelineSteps().size());
        assertTrue(updated.getConfiguration().pipelineSteps().containsKey("processor"));
    }
    
    @Test
    @Order(7)
    void testUpdateNonExistentPipeline() {
        // PUT /api/admin/pipelines/{pipelineName} - non-existent
        UpdatePipelineRequest request = new UpdatePipelineRequest();
        request.setPipelineSteps(Map.of());
        
        HttpRequest<UpdatePipelineRequest> httpRequest = HttpRequest
                .PUT(API_BASE_PATH + "/non-existent?clusterName=" + TEST_CLUSTER_NAME, request);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(httpRequest, PipelineOperationResponse.class);
        });
        
        try {
            client.toBlocking().exchange(httpRequest, PipelineOperationResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }
    
    @Test
    @Order(8)
    void testSetPipelineAsDefault() {
        // PUT /api/admin/pipelines/{pipelineName} - set as default
        UpdatePipelineRequest request = new UpdatePipelineRequest();
        
        // Keep existing steps (we need to fetch them first)
        HttpRequest<Object> getRequest = HttpRequest.GET(
                API_BASE_PATH + "/test-new-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        PipelineDetailResponse current = client.toBlocking()
                .retrieve(getRequest, PipelineDetailResponse.class);
        
        request.setPipelineSteps(current.getConfiguration().pipelineSteps());
        request.setSetAsDefault(true);
        
        HttpRequest<UpdatePipelineRequest> httpRequest = HttpRequest
                .PUT(API_BASE_PATH + "/test-new-pipeline?clusterName=" + TEST_CLUSTER_NAME, request);
        HttpResponse<PipelineOperationResponse> response = client.toBlocking()
                .exchange(httpRequest, PipelineOperationResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        
        // Verify it's now the default
        HttpRequest<Object> listRequest = HttpRequest.GET(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME);
        PipelineListResponse list = client.toBlocking()
                .retrieve(listRequest, PipelineListResponse.class);
        
        assertEquals("test-new-pipeline", list.getDefaultPipeline());
        
        Optional<PipelineSummary> newDefault = list.getPipelines().stream()
                .filter(p -> "test-new-pipeline".equals(p.getName()))
                .findFirst();
        assertTrue(newDefault.isPresent());
        assertTrue(newDefault.get().isDefault());
    }
    
    @Test
    @Order(9)
    void testDeletePipeline() {
        // First, ensure we have 3 pipelines
        HttpRequest<Object> listRequest = HttpRequest.GET(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME);
        PipelineListResponse beforeDelete = client.toBlocking()
                .retrieve(listRequest, PipelineListResponse.class);
        assertEquals(3, beforeDelete.getPipelines().size());
        
        // DELETE /api/admin/pipelines/{pipelineName}
        HttpRequest<Object> deleteRequest = HttpRequest.DELETE(
                API_BASE_PATH + "/complex-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        HttpResponse<PipelineOperationResponse> response = client.toBlocking()
                .exchange(deleteRequest, PipelineOperationResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        PipelineOperationResponse result = response.body();
        assertTrue(result.isSuccess());
        assertEquals("complex-pipeline", result.getPipelineName());
        
        // Verify deletion
        HttpRequest<Object> verifyRequest = HttpRequest.GET(
                API_BASE_PATH + "/complex-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(verifyRequest, PipelineDetailResponse.class);
        });
        
        // Verify count reduced
        PipelineListResponse afterDelete = client.toBlocking()
                .retrieve(listRequest, PipelineListResponse.class);
        assertEquals(2, afterDelete.getPipelines().size());
    }
    
    @Test
    @Order(10)
    void testDeleteDefaultPipeline() {
        // DELETE /api/admin/pipelines/{pipelineName} - try to delete default
        HttpRequest<Object> deleteRequest = HttpRequest.DELETE(
                API_BASE_PATH + "/test-new-pipeline?clusterName=" + TEST_CLUSTER_NAME); // This is default now
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(deleteRequest, PipelineOperationResponse.class);
        });
        
        try {
            client.toBlocking().exchange(deleteRequest, PipelineOperationResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.CONFLICT, e.getStatus());
        }
    }
    
    @Test
    @Order(11)
    void testDeleteNonExistentPipeline() {
        // DELETE /api/admin/pipelines/{pipelineName} - non-existent
        HttpRequest<Object> request = HttpRequest.DELETE(
                API_BASE_PATH + "/non-existent?clusterName=" + TEST_CLUSTER_NAME);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, PipelineOperationResponse.class);
        });
        
        try {
            client.toBlocking().exchange(request, PipelineOperationResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }
    
    @Test
    @Order(12)
    void testCreatePipelineWithInvalidRequest() {
        // POST /api/admin/pipelines - missing required fields
        CreatePipelineRequest request = new CreatePipelineRequest();
        // Missing pipelineName
        
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest
                .POST(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME, request);
        
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(httpRequest, PipelineOperationResponse.class);
        });
        
        try {
            client.toBlocking().exchange(httpRequest, PipelineOperationResponse.class);
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
    }
    
    @Test
    @Order(13)
    void testPipelineConfigurationPersistence() throws Exception {
        // This test verifies that pipeline changes persist in Consul
        
        // Get current pipeline count
        HttpRequest<Object> request = HttpRequest.GET(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME);
        PipelineListResponse initialResponse = client.toBlocking()
                .retrieve(request, PipelineListResponse.class);
        int initialCount = initialResponse.getPipelines().size();
        
        // Add a new pipeline
        CreatePipelineRequest newPipeline = new CreatePipelineRequest();
        newPipeline.setPipelineName("persistence-test-pipeline");
        newPipeline.setPipelineSteps(Map.of(
                "step1", PipelineStepConfig.builder()
                        .stepName("step1")
                        .stepType(StepType.PIPELINE)
                        .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                .grpcServiceName("text-chunker")
                                .build())
                        .outputs(Map.of())
                        .build()
        ));
        
        HttpRequest<CreatePipelineRequest> createRequest = HttpRequest
                .POST(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME, newPipeline);
        client.toBlocking().exchange(createRequest, PipelineOperationResponse.class);
        
        // Verify it was added
        PipelineListResponse afterAdd = client.toBlocking()
                .retrieve(request, PipelineListResponse.class);
        assertEquals(initialCount + 1, afterAdd.getPipelines().size());
        
        // Fetch directly from Consul to verify persistence
        Optional<PipelineClusterConfig> clusterConfig = consulBusinessOperationsService
                .getPipelineClusterConfig(TEST_CLUSTER_NAME)
                .block(Duration.ofSeconds(5));
        
        assertTrue(clusterConfig.isPresent());
        assertTrue(clusterConfig.get().pipelineGraphConfig().pipelines()
                .containsKey("persistence-test-pipeline"));
        
        // Clean up
        HttpRequest<Object> deleteRequest = HttpRequest
                .DELETE(API_BASE_PATH + "/persistence-test-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        client.toBlocking().exchange(deleteRequest, PipelineOperationResponse.class);
    }
    
    @Test
    @Order(14)
    void testConcurrentPipelineOperations() throws Exception {
        // Test that concurrent operations don't corrupt the configuration
        
        List<Thread> threads = new ArrayList<>();
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
        
        // Create multiple threads that add pipelines
        for (int i = 0; i < 5; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                try {
                    // Add small delay to reduce Consul contention
                    Thread.sleep(index * 100);
                    
                    CreatePipelineRequest request = new CreatePipelineRequest();
                    request.setPipelineName("concurrent-pipeline-" + index);
                    request.setPipelineSteps(Map.of(
                            "step", PipelineStepConfig.builder()
                                    .stepName("step")
                                    .stepType(StepType.PIPELINE)
                                    .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                                            .grpcServiceName("text-chunker-" + index)
                                            .build())
                                    .outputs(Map.of())
                                    .build()
                    ));
                    
                    HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest
                            .POST(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME, request);
                    client.toBlocking().exchange(httpRequest, PipelineOperationResponse.class);
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
        
        // Verify all pipelines were added
        HttpRequest<Object> listRequest = HttpRequest.GET(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME);
        PipelineListResponse response = client.toBlocking()
                .retrieve(listRequest, PipelineListResponse.class);
        
        for (int i = 0; i < 5; i++) {
            String pipelineName = "concurrent-pipeline-" + i;
            assertTrue(response.getPipelines().stream()
                    .anyMatch(p -> pipelineName.equals(p.getName())),
                    "Pipeline " + pipelineName + " not found");
        }
        
        // Clean up
        for (int i = 0; i < 5; i++) {
            HttpRequest<Object> deleteRequest = HttpRequest
                    .DELETE(API_BASE_PATH + "/concurrent-pipeline-" + i + "?clusterName=" + TEST_CLUSTER_NAME);
            client.toBlocking().exchange(deleteRequest, PipelineOperationResponse.class);
        }
    }
    
    @Test
    @Order(15)
    void testPipelineWithComplexStepConfiguration() {
        // Test creating a pipeline with complex step configurations
        CreatePipelineRequest request = new CreatePipelineRequest();
        request.setPipelineName("complex-step-pipeline");
        
        Map<String, PipelineStepConfig> complexSteps = new HashMap<>();
        
        // Add a step with custom configuration
        complexSteps.put("enricher", PipelineStepConfig.builder()
                .stepName("enricher")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("text-chunker")
                        .build())
                .outputs(Map.of(
                        "enriched", PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("validator")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("main-engine")
                                        .build())
                                .build(),
                        "failed", PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("error-handler")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("error-topic")
                                        .build())
                                .build()
                ))
                .build());
        
        complexSteps.put("validator", PipelineStepConfig.builder()
                .stepName("validator")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("tika-parser")
                        .build())
                .outputs(Map.of())
                .build());
        
        complexSteps.put("error-handler", PipelineStepConfig.builder()
                .stepName("error-handler")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("opensearch-sink")
                        .build())
                .outputs(Map.of())
                .build());
        
        request.setPipelineSteps(complexSteps);
        
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest
                .POST(API_BASE_PATH + "?clusterName=" + TEST_CLUSTER_NAME, request);
        HttpResponse<PipelineOperationResponse> response = client.toBlocking()
                .exchange(httpRequest, PipelineOperationResponse.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        
        // Verify the complex configuration was saved correctly
        HttpRequest<Object> getRequest = HttpRequest.GET(
                API_BASE_PATH + "/complex-step-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        PipelineDetailResponse detail = client.toBlocking()
                .retrieve(getRequest, PipelineDetailResponse.class);
        
        PipelineStepConfig enricherStep = detail.getConfiguration().pipelineSteps().get("enricher");
        assertNotNull(enricherStep);
        assertEquals(2, enricherStep.outputs().size());
        
        // Clean up
        HttpRequest<Object> deleteRequest = HttpRequest
                .DELETE(API_BASE_PATH + "/complex-step-pipeline?clusterName=" + TEST_CLUSTER_NAME);
        client.toBlocking().exchange(deleteRequest, PipelineOperationResponse.class);
    }
}