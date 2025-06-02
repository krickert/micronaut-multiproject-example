package com.krickert.search.pipeline.module;

import com.krickert.search.model.*;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for module discovery with gRPC forwarding.
 * Tests that modules can forward processing to other modules via gRPC.
 */
@MicronautTest(environments = {"test"})
@Property(name = "yappy.module.discovery.enabled", value = "true")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "consul.client.registration.enabled", value = "false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModuleWithGrpcForwardingIT {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleWithGrpcForwardingIT.class);
    
    @Inject
    ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    ModuleTestHelper testHelper;
    
    private ModuleTestHelper.RegisteredModule firstModule;
    private ModuleTestHelper.RegisteredModule secondModule;
    
    @BeforeAll
    void setUpClass() throws Exception {
        // Clean up any previous test data
        testHelper.cleanupAllTestData();
        
        // Register first module that will forward to second
        firstModule = testHelper.registerTestModule(
                "grpc-forwarder-module",
                "forwarder",
                new ForwarderModule(),
                List.of("forwarder", "yappy-module")
        );
        
        // Register second module that processes
        secondModule = testHelper.registerTestModule(
                "grpc-processor-module", 
                "processor",
                new ProcessorModule(),
                List.of("processor", "yappy-module")
        );
        
        // Wait for Consul registration
        testHelper.waitForServiceDiscovery("grpc-forwarder-module", 5000);
        testHelper.waitForServiceDiscovery("grpc-processor-module", 5000);
        
        // Discover modules
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(2000); // Wait for async discovery
        
        log.info("Modules registered - forwarder on port {}, processor on port {}", 
                firstModule.getPort(), secondModule.getPort());
    }
    
    @AfterAll
    void tearDownClass() {
        testHelper.cleanupAllTestData();
    }
    
    @Test
    @Order(1)
    void testModuleForwardingViaGrpc() throws Exception {
        // Get the forwarder module stub
        var forwarderInfo = moduleDiscoveryService.getModuleInfo("grpc-forwarder-module");
        assertNotNull(forwarderInfo, "Forwarder module should be discovered");
        
        // Create test document
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId("test-doc-456")
                .setTitle("Test Document")
                .setBody("This is test content for gRPC forwarding")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .setConfig(ProcessConfiguration.newBuilder()
                        .putConfigParams("forward_to", "grpc-processor-module")
                        .build())
                .setMetadata(ServiceMetadata.newBuilder()
                        .setPipeStepName("forwarder")
                        .setStreamId("stream-456")
                        .build())
                .build();
        
        // Call the forwarder module
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ProcessResponse> responseRef = new AtomicReference<>();
        
        forwarderInfo.stub().processData(request, new StreamObserver<ProcessResponse>() {
            @Override
            public void onNext(ProcessResponse response) {
                responseRef.set(response);
            }
            
            @Override
            public void onError(Throwable t) {
                log.error("Error processing", t);
                latch.countDown();
            }
            
            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Processing should complete within timeout");
        
        ProcessResponse response = responseRef.get();
        assertNotNull(response, "Should receive response");
        assertTrue(response.getSuccess(), "Processing should succeed");
        
        // Verify the document was processed by both modules
        assertTrue(response.hasOutputDoc(), "Should have output document");
        PipeDoc outputDoc = response.getOutputDoc();
        assertTrue(outputDoc.getBody().contains("FORWARDED->PROCESSED:"));
        assertEquals("TEST-DOC-456", outputDoc.getId()); // Processor uppercases ID
        
        // Check logs contain both modules
        assertTrue(response.getProcessorLogsList().stream()
                .anyMatch(log -> log.contains("Forwarding to grpc-processor-module")));
        assertTrue(response.getProcessorLogsList().stream()
                .anyMatch(log -> log.contains("Processed by processor module")));
    }
    
    @Test
    @Order(2)
    void testChainedModuleProcessing() throws Exception {
        // Test that modules can be chained together
        // This simulates a pipeline where module A -> module B -> result
        
        var forwarderInfo = moduleDiscoveryService.getModuleInfo("grpc-forwarder-module");
        assertNotNull(forwarderInfo);
        
        // Send multiple documents
        for (int i = 0; i < 3; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("doc-chain-" + i)
                    .setBody("Document " + i + " for chaining")
                    .build();
            
            ProcessRequest request = ProcessRequest.newBuilder()
                    .setDocument(doc)
                    .setConfig(ProcessConfiguration.newBuilder()
                            .putConfigParams("forward_to", "grpc-processor-module")
                            .putConfigParams("chain_id", String.valueOf(i))
                            .build())
                    .setMetadata(ServiceMetadata.newBuilder()
                            .setPipeStepName("forwarder")
                            .setStreamId("chain-stream-" + i)
                            .build())
                    .build();
            
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<ProcessResponse> responseRef = new AtomicReference<>();
            
            forwarderInfo.stub().processData(request, new StreamObserver<ProcessResponse>() {
                @Override
                public void onNext(ProcessResponse response) {
                    responseRef.set(response);
                }
                
                @Override
                public void onError(Throwable t) {
                    log.error("Error in chain processing", t);
                    latch.countDown();
                }
                
                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            });
            
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Chain " + i + " should complete");
            
            ProcessResponse response = responseRef.get();
            assertNotNull(response);
            assertTrue(response.getSuccess());
            assertEquals("DOC-CHAIN-" + i, response.getOutputDoc().getId());
        }
    }
    
    /**
     * Module that forwards processing to another module via gRPC.
     */
    private class ForwarderModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("forwarder")
                    .putContextParams("description", "Forwards processing to other modules")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            
            try {
                String forwardTo = request.getConfig().getConfigParamsOrDefault("forward_to", "");
                log.info("Forwarding request to: {}", forwardTo);
                
                // If no forwarding target specified (during testing), just echo the request
                if (forwardTo.isEmpty()) {
                    ProcessResponse response = ProcessResponse.newBuilder()
                            .setSuccess(true)
                            .setOutputDoc(request.getDocument())
                            .addProcessorLogs("Forwarder module processed (no forwarding target specified)")
                            .build();
                    
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                    return;
                }
                
                // Get the target module
                var targetModule = moduleDiscoveryService.getModuleInfo(forwardTo);
                if (targetModule == null) {
                    throw new RuntimeException("Target module not found: " + forwardTo);
                }
                
                // Forward the request
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<ProcessResponse> forwardedResponse = new AtomicReference<>();
                AtomicReference<Throwable> error = new AtomicReference<>();
                
                targetModule.stub().processData(request, new StreamObserver<ProcessResponse>() {
                    @Override
                    public void onNext(ProcessResponse response) {
                        forwardedResponse.set(response);
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                        latch.countDown();
                    }
                    
                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                });
                
                // Wait for forwarded processing
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Timeout waiting for forwarded processing");
                }
                
                if (error.get() != null) {
                    throw new RuntimeException("Error in forwarded processing", error.get());
                }
                
                ProcessResponse forward = forwardedResponse.get();
                
                // Modify the response to show it was forwarded
                PipeDoc outputDoc = forward.hasOutputDoc() ? 
                        PipeDoc.newBuilder(forward.getOutputDoc())
                                .setBody("FORWARDED->" + forward.getOutputDoc().getBody())
                                .build() :
                        request.getDocument();
                
                ProcessResponse response = ProcessResponse.newBuilder()
                        .setSuccess(forward.getSuccess())
                        .setOutputDoc(outputDoc)
                        .addProcessorLogs("Forwarding to " + forwardTo)
                        .addAllProcessorLogs(forward.getProcessorLogsList())
                        .build();
                
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("Error in forwarder module", e);
                responseObserver.onError(e);
            }
        }
        
        @Override
        public void checkHealth(com.google.protobuf.Empty request,
                StreamObserver<HealthCheckResponse> responseObserver) {
            HealthCheckResponse response = HealthCheckResponse.newBuilder()
                    .setHealthy(true)
                    .setMessage("Forwarder module is healthy")
                    .setVersion("1.0.0")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    /**
     * Module that processes documents.
     */
    private static class ProcessorModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("processor")
                    .putContextParams("description", "Processes documents")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            
            try {
                // Process the document
                PipeDoc inputDoc = request.getDocument();
                PipeDoc outputDoc = PipeDoc.newBuilder()
                        .setId(inputDoc.getId().toUpperCase())
                        .setTitle(inputDoc.hasTitle() ? inputDoc.getTitle() + " [PROCESSED]" : "")
                        .setBody("PROCESSED: " + inputDoc.getBody())
                        .build();
                
                ProcessResponse response = ProcessResponse.newBuilder()
                        .setSuccess(true)
                        .setOutputDoc(outputDoc)
                        .addProcessorLogs("Processed by processor module: " + inputDoc.getId())
                        .build();
                
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(e);
            }
        }
        
        @Override
        public void checkHealth(com.google.protobuf.Empty request,
                StreamObserver<HealthCheckResponse> responseObserver) {
            HealthCheckResponse response = HealthCheckResponse.newBuilder()
                    .setHealthy(true)
                    .setMessage("Processor module is healthy")
                    .setVersion("1.0.0")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}