package com.krickert.search.pipeline.unit;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.ModuleConnector;
import com.krickert.search.pipeline.service.ModuleRegistry;
import com.krickert.search.pipeline.service.PipelineConfigurationService;
import com.krickert.search.pipeline.service.PipelineExecutor;
import com.krickert.search.pipeline.service.impl.InMemoryModuleRegistry;
import com.krickert.search.pipeline.service.impl.InMemoryPipelineConfigurationService;
import com.krickert.search.pipeline.service.impl.TestModuleConnector;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import org.junit.jupiter.api.*;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for pipeline execution using interface-based design.
 * This test doesn't require any external dependencies or mocking frameworks.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PipelineExecutionUnitTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(PipelineExecutionUnitTest.class);
    
    private InMemoryModuleRegistry moduleRegistry;
    private InMemoryPipelineConfigurationService pipelineConfigService;
    private TestModuleConnector moduleConnector;
    private PipelineExecutor pipelineExecutor;
    private Map<String, String> registeredModules = new HashMap<>(); // Maps module name to service ID
    
    @BeforeEach
    void setup() {
        // Create service implementations
        moduleRegistry = new InMemoryModuleRegistry();
        pipelineConfigService = new InMemoryPipelineConfigurationService();
        moduleConnector = new TestModuleConnector(moduleRegistry);
        
        // Create a simple pipeline executor implementation
        pipelineExecutor = new SimplePipelineExecutor(
                pipelineConfigService,
                moduleConnector,
                moduleRegistry
        );
    }
    
    @AfterEach
    void cleanup() {
        moduleRegistry.clear();
        pipelineConfigService.clear();
        registeredModules.clear();
    }
    
    @Test
    @DisplayName("Should register module and verify it's available")
    void testModuleRegistration() {
        // Given
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-processor-v1")
                .setInstanceServiceName("test-processor")
                .setHost("localhost")
                .setPort(50051)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setInstanceCustomConfigJson("{\"test\": true}")
                .build();
        
        // When
        RegisterModuleResponse response = moduleRegistry.registerModule(request).block();
        
        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertNotNull(response.getRegisteredServiceId());
        assertEquals("digest-1593115136", response.getCalculatedConfigDigest());
        
        // Verify module is registered
        Boolean isRegistered = moduleRegistry.isRegistered(response.getRegisteredServiceId()).block();
        assertTrue(isRegistered);
        
        // Verify module info
        ModuleRegistry.ModuleInfo moduleInfo = moduleRegistry.getModule(response.getRegisteredServiceId()).block();
        assertNotNull(moduleInfo);
        assertEquals("test-processor-v1", moduleInfo.implementationId());
        assertEquals("localhost", moduleInfo.host());
        assertEquals(50051, moduleInfo.port());
    }
    
    @Test
    @DisplayName("Should create and validate pipeline configuration")
    void testPipelineConfiguration() {
        // Given
        PipelineStepConfig step1 = new PipelineStepConfig(
                "step1",
                StepType.PIPELINE,
                new PipelineStepConfig.ProcessorInfo("echo-processor", null)
        );
        
        PipelineStepConfig step2 = new PipelineStepConfig(
                "step2",
                StepType.PIPELINE,
                new PipelineStepConfig.ProcessorInfo("uppercase-processor", null)
        );
        
        PipelineConfig pipeline = PipelineConfig.builder()
                .name("test-pipeline")
                .pipelineSteps(Map.of(
                        "step1", step1,
                        "step2", step2
                ))
                .build();
        
        // When
        pipelineConfigService.savePipelineConfig("test-pipeline", pipeline).block();
        PipelineConfig retrieved = pipelineConfigService.getPipelineConfig("test-pipeline").block();
        
        // Then
        assertNotNull(retrieved);
        assertEquals("test-pipeline", retrieved.name());
        assertEquals(2, retrieved.pipelineSteps().size());
        
        // Validate configuration
        PipelineConfigurationService.ValidationResult validation = 
                pipelineConfigService.validatePipelineConfig(retrieved).block();
        assertTrue(validation.valid());
        assertTrue(validation.errors().isEmpty());
        
        // Get ordered steps
        List<PipelineStepConfig> orderedSteps = 
                pipelineConfigService.getOrderedSteps("test-pipeline").block();
        assertEquals(2, orderedSteps.size());
        assertEquals("step1", orderedSteps.get(0).stepName());
        assertEquals("step2", orderedSteps.get(1).stepName());
    }
    
    @Test
    @DisplayName("Should process document through pipeline")
    void testDocumentProcessing() {
        // Given: Register modules
        registerTestModule("echo-processor");
        registerTestModule("uppercase-processor");
        
        // Create pipeline
        createTestPipeline("process-pipeline", List.of("echo-processor", "uppercase-processor"));
        
        // Create test document
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId("test-doc-1")
                .setBody("hello world")
                .setDocumentType("unit-test")
                .build();
        
        // When: Process document
        PipeDoc result = pipelineExecutor.processDocument("process-pipeline", inputDoc).block();
        
        // Then
        assertNotNull(result);
        assertEquals("test-doc-1", result.getId());
        assertEquals("ECHO: HELLO WORLD", result.getBody()); // Echo then uppercase
        assertEquals("unit-test", result.getDocumentType());
        // Check custom_data for processed_by metadata
        assertTrue(result.hasCustomData());
        assertEquals("uppercase-processor", 
            result.getCustomData().getFieldsOrThrow("processed_by").getStringValue());
    }
    
    @Test
    @DisplayName("Should process stream of documents")
    void testStreamProcessing() {
        // Given
        registerTestModule("echo-processor");
        createTestPipeline("stream-pipeline", List.of("echo-processor"));
        
        // Create document stream
        Flux<PipeDoc> inputDocs = Flux.range(1, 5)
                .map(i -> PipeDoc.newBuilder()
                        .setId("doc-" + i)
                        .setBody("content " + i)
                        .build());
        
        // When
        Flux<PipeDoc> processedDocs = pipelineExecutor.processDocuments("stream-pipeline", inputDocs);
        
        // Then
        StepVerifier.create(processedDocs)
                .expectNextMatches(doc -> 
                        doc.getId().equals("doc-1") && 
                        doc.getBody().equals("ECHO: content 1"))
                .expectNextMatches(doc -> 
                        doc.getId().equals("doc-2") && 
                        doc.getBody().equals("ECHO: content 2"))
                .expectNextCount(3) // Remaining 3 documents
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle module failure gracefully")
    void testModuleFailure() {
        // Given
        registerTestModule("failing-module");
        String failingServiceId = registeredModules.get("failing-module");
        moduleConnector.simulateModuleFailure(failingServiceId);
        createTestPipeline("failing-pipeline", List.of("failing-module"));
        
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId("test-doc")
                .setBody("test content")
                .build();
        
        // When/Then
        StepVerifier.create(pipelineExecutor.processDocument("failing-pipeline", inputDoc))
                .expectError(RuntimeException.class)
                .verify();
    }
    
    @Test
    @DisplayName("Should handle pipeline not found")
    void testPipelineNotFound() {
        // Given
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId("test-doc")
                .setBody("test content")
                .build();
        
        // When/Then
        StepVerifier.create(pipelineExecutor.processDocument("non-existent-pipeline", inputDoc))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
    
    @Test
    @DisplayName("Should watch pipeline configuration changes")
    void testPipelineWatching() {
        // Given
        PipelineConfig initialConfig = PipelineConfig.builder()
                .name("watch-pipeline")
                .pipelineSteps(Map.of(
                        "step1", new PipelineStepConfig(
                                "step1",
                                StepType.PIPELINE,
                                new PipelineStepConfig.ProcessorInfo("echo-processor", null)
                        )
                ))
                .build();
        
        // Start watching
        Flux<PipelineConfig> watcher = pipelineConfigService.watchPipelineConfig("watch-pipeline");
        
        // When: Save initial config and update
        StepVerifier.create(watcher)
                .then(() -> pipelineConfigService.savePipelineConfig("watch-pipeline", initialConfig).subscribe())
                .expectNextMatches(config -> config.pipelineSteps().size() == 1)
                .then(() -> {
                    // Update config
                    PipelineConfig updatedConfig = PipelineConfig.builder()
                            .name("watch-pipeline")
                            .pipelineSteps(Map.of(
                                    "step1", initialConfig.pipelineSteps().get("step1"),
                                    "step2", new PipelineStepConfig(
                                            "step2",
                                            StepType.PIPELINE,
                                            new PipelineStepConfig.ProcessorInfo("uppercase-processor", null)
                                    )
                            ))
                            .build();
                    pipelineConfigService.savePipelineConfig("watch-pipeline", updatedConfig).subscribe();
                })
                .expectNextMatches(config -> config.pipelineSteps().size() == 2)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }
    
    @Test
    @DisplayName("Should check pipeline readiness")
    void testPipelineReadiness() {
        // Given: Create a pipeline config directly without using createTestPipeline
        // (since that requires registered modules)
        PipelineStepConfig step = new PipelineStepConfig(
                "step1",
                StepType.PIPELINE,
                new PipelineStepConfig.ProcessorInfo("unregistered-module-service", null)
        );
        
        PipelineConfig config = PipelineConfig.builder()
                .name("not-ready-pipeline")
                .pipelineSteps(Map.of("step1", step))
                .build();
        
        pipelineConfigService.savePipelineConfig("not-ready-pipeline", config).block();
        
        // When/Then: Pipeline should not be ready
        Boolean isReady = pipelineExecutor.isPipelineReady("not-ready-pipeline").block();
        assertFalse(isReady);
        
        // Register the module and update the pipeline
        registerTestModule("unregistered-module");
        createTestPipeline("ready-pipeline", List.of("unregistered-module"));
        
        // Now pipeline should be ready
        isReady = pipelineExecutor.isPipelineReady("ready-pipeline").block();
        assertTrue(isReady);
    }
    
    @Test
    @DisplayName("Should track pipeline statistics")
    void testPipelineStatistics() {
        // Given
        registerTestModule("echo-processor");
        createTestPipeline("stats-pipeline", List.of("echo-processor"));
        
        // Process some documents
        for (int i = 0; i < 5; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("doc-" + i)
                    .setBody("content " + i)
                    .build();
            pipelineExecutor.processDocument("stats-pipeline", doc).block();
        }
        
        // When
        PipelineExecutor.PipelineStats stats = pipelineExecutor.getPipelineStats("stats-pipeline").block();
        
        // Then
        assertNotNull(stats);
        assertEquals("stats-pipeline", stats.pipelineId());
        assertEquals(5, stats.documentsProcessed());
        assertEquals(0, stats.errorCount());
        assertTrue(stats.averageProcessingTimeMs() >= 0);
        assertTrue(stats.lastProcessedTimestamp() > 0);
    }
    
    // Helper methods
    
    private void registerTestModule(String moduleId) {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId(moduleId + "-impl")
                .setInstanceServiceName(moduleId)
                .setHost("localhost")
                .setPort(50000)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setInstanceCustomConfigJson("{}")
                .build();
        
        RegisterModuleResponse response = moduleRegistry.registerModule(request).block();
        String serviceId = response.getRegisteredServiceId();
        
        // Store the mapping from module name to actual service ID
        registeredModules.put(moduleId, serviceId);
        
        // Register the processor in TestModuleConnector with the service ID
        if ("echo-processor".equals(moduleId)) {
            moduleConnector.registerTestProcessor(serviceId, doc -> {
                // Build custom_data with metadata
                Struct.Builder customData = doc.hasCustomData() ? 
                        doc.getCustomData().toBuilder() : 
                        Struct.newBuilder();
                customData.putFields("processed_by", 
                        Value.newBuilder().setStringValue("echo-processor").build());
                
                return PipeDoc.newBuilder(doc)
                        .setBody("ECHO: " + doc.getBody())
                        .setCustomData(customData.build())
                        .build();
            });
        } else if ("uppercase-processor".equals(moduleId)) {
            moduleConnector.registerTestProcessor(serviceId, doc -> {
                // Build custom_data with metadata
                Struct.Builder customData = doc.hasCustomData() ? 
                        doc.getCustomData().toBuilder() : 
                        Struct.newBuilder();
                customData.putFields("processed_by", 
                        Value.newBuilder().setStringValue("uppercase-processor").build());
                
                return PipeDoc.newBuilder(doc)
                        .setBody(doc.getBody().toUpperCase())
                        .setCustomData(customData.build())
                        .build();
            });
        }
        
        // Update health status to healthy
        moduleRegistry.updateHealthStatus(serviceId, 
                ModuleRegistry.HealthStatus.HEALTHY).block();
    }
    
    private void createTestPipeline(String pipelineId, List<String> moduleIds) {
        Map<String, PipelineStepConfig> steps = new java.util.HashMap<>();
        
        for (int i = 0; i < moduleIds.size(); i++) {
            String stepId = "step" + (i + 1);
            String moduleName = moduleIds.get(i);
            String serviceId = registeredModules.get(moduleName);
            if (serviceId == null) {
                throw new IllegalArgumentException("Module not registered: " + moduleName);
            }
            
            steps.put(stepId, new PipelineStepConfig(
                    stepId,
                    StepType.PIPELINE,
                    new PipelineStepConfig.ProcessorInfo(serviceId, null)
            ));
        }
        
        PipelineConfig config = PipelineConfig.builder()
                .name(pipelineId)
                .pipelineSteps(steps)
                .build();
        
        pipelineConfigService.savePipelineConfig(pipelineId, config).block();
    }
    
    /**
     * Simple implementation of PipelineExecutor for testing.
     */
    private static class SimplePipelineExecutor implements PipelineExecutor {
        
        private final PipelineConfigurationService configService;
        private final ModuleConnector moduleConnector;
        private final ModuleRegistry moduleRegistry;
        private final Map<String, PipelineStats> stats = new ConcurrentHashMap<>();
        
        SimplePipelineExecutor(PipelineConfigurationService configService,
                             ModuleConnector moduleConnector,
                             ModuleRegistry moduleRegistry) {
            this.configService = configService;
            this.moduleConnector = moduleConnector;
            this.moduleRegistry = moduleRegistry;
        }
        
        @Override
        public Mono<PipeDoc> processDocument(String pipelineId, PipeDoc document) {
            long startTime = System.currentTimeMillis();
            
            return configService.getPipelineConfig(pipelineId)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId)))
                    .flatMap(config -> configService.getOrderedSteps(pipelineId))
                    .flatMap(steps -> {
                        // Process through each step sequentially
                        Mono<PipeDoc> result = Mono.just(document);
                        
                        for (PipelineStepConfig step : steps) {
                            result = result.flatMap(doc -> 
                                    moduleConnector.processDocument(
                                            step.processorInfo().grpcServiceName(),
                                            doc,
                                            new ModuleConnector.ModuleConfig(
                                                    step.stepName(),
                                                    pipelineId,
                                                    Map.of()
                                            )
                                    )
                            );
                        }
                        
                        return result;
                    })
                    .doOnSuccess(doc -> updateStats(pipelineId, System.currentTimeMillis() - startTime, false))
                    .doOnError(error -> updateStats(pipelineId, System.currentTimeMillis() - startTime, true));
        }
        
        @Override
        public Flux<PipeDoc> processDocuments(String pipelineId, Flux<PipeDoc> documents) {
            return documents.flatMap(doc -> processDocument(pipelineId, doc));
        }
        
        @Override
        public Mono<PipeStream> processPipeStream(PipeStream stream) {
            // Process the document in the stream
            return processDocument(stream.getCurrentPipelineName(), stream.getDocument())
                    .map(processedDoc -> PipeStream.newBuilder()
                            .setStreamId(stream.getStreamId())
                            .setDocument(processedDoc)
                            .setCurrentPipelineName(stream.getCurrentPipelineName())
                            .setTargetStepName(stream.getTargetStepName())
                            .setCurrentHopNumber(stream.getCurrentHopNumber() + 1)
                            .build());
        }
        
        @Override
        public Mono<Boolean> isPipelineReady(String pipelineId) {
            return configService.getPipelineConfig(pipelineId)
                    .flatMap(config -> configService.getOrderedSteps(pipelineId))
                    .flatMapMany(Flux::fromIterable)
                    .flatMap(step -> moduleRegistry.isRegistered(step.processorInfo().grpcServiceName()))
                    .all(exists -> exists)
                    .defaultIfEmpty(false);
        }
        
        @Override
        public Mono<PipelineStats> getPipelineStats(String pipelineId) {
            return Mono.justOrEmpty(stats.get(pipelineId))
                    .switchIfEmpty(Mono.just(new PipelineStats(pipelineId, 0, 0, 0, 0, 0)));
        }
        
        private void updateStats(String pipelineId, long processingTime, boolean error) {
            stats.compute(pipelineId, (id, existing) -> {
                if (existing == null) {
                    return new PipelineStats(
                            pipelineId,
                            error ? 0 : 1,
                            0,
                            error ? 1 : 0,
                            processingTime,
                            System.currentTimeMillis()
                    );
                } else {
                    long totalProcessed = existing.documentsProcessed() + (error ? 0 : 1);
                    long totalErrors = existing.errorCount() + (error ? 1 : 0);
                    double avgTime = ((existing.averageProcessingTimeMs() * existing.documentsProcessed()) + processingTime) 
                            / (totalProcessed + totalErrors);
                    
                    return new PipelineStats(
                            pipelineId,
                            totalProcessed,
                            0,
                            totalErrors,
                            avgTime,
                            System.currentTimeMillis()
                    );
                }
            });
        }
    }
}