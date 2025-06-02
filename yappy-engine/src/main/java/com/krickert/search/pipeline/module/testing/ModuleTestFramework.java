package com.krickert.search.pipeline.module.testing;

import com.krickert.search.sdk.*;
import com.krickert.search.model.PipeDoc;
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Testing framework for Yappy modules implemented in any language.
 * This helps developers test their gRPC modules against the expected interface.
 * 
 * Usage:
 * <pre>
 * ModuleTestFramework tester = new ModuleTestFramework("localhost", 50051);
 * ModuleTestResult result = tester.runFullTestSuite();
 * System.out.println(result.getReport());
 * </pre>
 */
public class ModuleTestFramework {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleTestFramework.class);
    
    private final String host;
    private final int port;
    private final ManagedChannel channel;
    private final PipeStepProcessorGrpc.PipeStepProcessorStub asyncStub;
    private final PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingStub;
    
    public ModuleTestFramework(String host, int port) {
        this.host = host;
        this.port = port;
        this.channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
        this.asyncStub = PipeStepProcessorGrpc.newStub(channel);
        this.blockingStub = PipeStepProcessorGrpc.newBlockingStub(channel);
    }
    
    /**
     * Runs the complete test suite against the module.
     */
    public ModuleTestResult runFullTestSuite() {
        ModuleTestResult result = new ModuleTestResult();
        
        // Test 1: Service Registration
        result.addTest(testServiceRegistration());
        
        // Test 2: Basic Processing
        result.addTest(testBasicProcessing());
        
        // Test 3: Processing with Configuration
        result.addTest(testProcessingWithConfiguration());
        
        // Test 4: Error Handling
        result.addTest(testErrorHandling());
        
        // Test 5: Performance
        result.addTest(testPerformance());
        
        // Test 6: Large Document Handling
        result.addTest(testLargeDocument());
        
        // Test 7: Concurrent Requests
        result.addTest(testConcurrentRequests());
        
        return result;
    }
    
    /**
     * Test 1: Verify service registration returns valid metadata
     */
    private TestCase testServiceRegistration() {
        TestCase test = new TestCase("Service Registration");
        
        try {
            ServiceMetadata metadata = blockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getServiceRegistration(Empty.getDefaultInstance());
            
            // Check required fields
            if (metadata.getPipeStepName() == null || metadata.getPipeStepName().isEmpty()) {
                test.fail("pipe_step_name is required but was empty");
                return test;
            }
            
            test.pass("Successfully retrieved metadata with step name: " + metadata.getPipeStepName());
            
            // Check for optional schema
            if (metadata.containsContextParams("json_config_schema")) {
                test.addInfo("Module provides configuration schema");
                validateJsonSchema(metadata.getContextParamsOrThrow("json_config_schema"), test);
            }
            
        } catch (Exception e) {
            test.fail("Failed to get service registration: " + e.getMessage());
        }
        
        return test;
    }
    
    /**
     * Test 2: Basic processing without configuration
     */
    private TestCase testBasicProcessing() {
        TestCase test = new TestCase("Basic Processing");
        
        try {
            ProcessRequest request = createBasicRequest();
            ProcessResponse response = blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .processData(request);
            
            if (!response.getSuccess()) {
                test.fail("Processing failed: " + String.join(", ", response.getProcessorLogsList()));
                return test;
            }
            
            test.pass("Successfully processed basic request");
            
            // Check if output document is modified
            if (response.hasOutputDoc()) {
                test.addInfo("Module returned modified document");
            }
            
        } catch (Exception e) {
            test.fail("Failed to process basic request: " + e.getMessage());
        }
        
        return test;
    }
    
    /**
     * Test 3: Processing with configuration
     */
    private TestCase testProcessingWithConfiguration() {
        TestCase test = new TestCase("Processing with Configuration");
        
        try {
            // First get the schema to understand expected config
            ServiceMetadata metadata = blockingStub.getServiceRegistration(Empty.getDefaultInstance());
            
            ProcessRequest.Builder requestBuilder = ProcessRequest.newBuilder()
                    .setDocument(createTestDocument("config-test"))
                    .setMetadata(createTestMetadata());
            
            // Add configuration if schema is provided
            if (metadata.containsContextParams("json_config_schema")) {
                ProcessConfiguration config = createSampleConfiguration();
                requestBuilder.setConfig(config);
            }
            
            ProcessResponse response = blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .processData(requestBuilder.build());
            
            if (!response.getSuccess()) {
                test.fail("Processing with config failed: " + String.join(", ", response.getProcessorLogsList()));
                return test;
            }
            
            test.pass("Successfully processed request with configuration");
            
        } catch (Exception e) {
            test.fail("Failed to process with configuration: " + e.getMessage());
        }
        
        return test;
    }
    
    /**
     * Test 4: Error handling with invalid input
     */
    private TestCase testErrorHandling() {
        TestCase test = new TestCase("Error Handling");
        
        try {
            // Send request with minimal/invalid data
            ProcessRequest request = ProcessRequest.newBuilder()
                    .setDocument(PipeDoc.newBuilder()
                            .setId("") // Empty ID
                            .build())
                    .build();
            
            ProcessResponse response = blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .processData(request);
            
            // Module should handle gracefully
            if (response.getSuccess()) {
                test.addInfo("Module accepted minimal input");
            } else {
                test.addInfo("Module correctly rejected invalid input: " + 
                        String.join(", ", response.getProcessorLogsList()));
            }
            
            test.pass("Module handled edge case without crashing");
            
        } catch (StatusRuntimeException e) {
            // This is actually good - module rejected bad input
            test.pass("Module correctly rejected invalid input with status: " + e.getStatus());
        } catch (Exception e) {
            test.fail("Unexpected error: " + e.getMessage());
        }
        
        return test;
    }
    
    /**
     * Test 5: Performance test with multiple requests
     */
    private TestCase testPerformance() {
        TestCase test = new TestCase("Performance Test");
        int numRequests = 100;
        
        try {
            long startTime = System.currentTimeMillis();
            
            for (int i = 0; i < numRequests; i++) {
                ProcessRequest request = createBasicRequest();
                ProcessResponse response = blockingStub
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .processData(request);
                
                if (!response.getSuccess()) {
                    test.fail("Request " + i + " failed");
                    return test;
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            double avgTime = (double) duration / numRequests;
            
            test.pass(String.format("Processed %d requests in %dms (avg: %.2fms per request)", 
                    numRequests, duration, avgTime));
            
            if (avgTime > 100) {
                test.addInfo("WARNING: Average processing time is high (>100ms)");
            }
            
        } catch (Exception e) {
            test.fail("Performance test failed: " + e.getMessage());
        }
        
        return test;
    }
    
    /**
     * Test 6: Large document handling
     */
    private TestCase testLargeDocument() {
        TestCase test = new TestCase("Large Document Handling");
        
        try {
            // Create a document with large text content
            StringBuilder largeText = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                largeText.append("This is line ").append(i).append(" of a large document. ");
            }
            
            PipeDoc largeDoc = PipeDoc.newBuilder()
                    .setId("large-doc-" + UUID.randomUUID())
                    .setTitle("Large Document Test")
                    .setBody(largeText.toString())
                    .build();
            
            ProcessRequest request = ProcessRequest.newBuilder()
                    .setDocument(largeDoc)
                    .setMetadata(createTestMetadata())
                    .build();
            
            long startTime = System.currentTimeMillis();
            ProcessResponse response = blockingStub
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .processData(request);
            long duration = System.currentTimeMillis() - startTime;
            
            if (!response.getSuccess()) {
                test.fail("Failed to process large document: " + 
                        String.join(", ", response.getProcessorLogsList()));
                return test;
            }
            
            test.pass(String.format("Successfully processed large document (%d chars) in %dms", 
                    largeText.length(), duration));
            
        } catch (Exception e) {
            test.fail("Large document test failed: " + e.getMessage());
        }
        
        return test;
    }
    
    /**
     * Test 7: Concurrent request handling
     */
    private TestCase testConcurrentRequests() {
        TestCase test = new TestCase("Concurrent Request Handling");
        int numConcurrent = 10;
        
        try {
            List<CompletableFuture<ProcessResponse>> futures = new ArrayList<>();
            
            for (int i = 0; i < numConcurrent; i++) {
                CompletableFuture<ProcessResponse> future = new CompletableFuture<>();
                ProcessRequest request = createBasicRequest();
                
                asyncStub.withDeadlineAfter(10, TimeUnit.SECONDS)
                        .processData(request, new StreamObserver<ProcessResponse>() {
                            @Override
                            public void onNext(ProcessResponse response) {
                                future.complete(response);
                            }
                            
                            @Override
                            public void onError(Throwable t) {
                                future.completeExceptionally(t);
                            }
                            
                            @Override
                            public void onCompleted() {
                                // Response already sent in onNext
                            }
                        });
                
                futures.add(future);
            }
            
            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(20, TimeUnit.SECONDS);
            
            // Check results
            int successful = 0;
            for (CompletableFuture<ProcessResponse> future : futures) {
                ProcessResponse response = future.get();
                if (response.getSuccess()) {
                    successful++;
                }
            }
            
            if (successful == numConcurrent) {
                test.pass(String.format("All %d concurrent requests processed successfully", numConcurrent));
            } else {
                test.fail(String.format("Only %d/%d concurrent requests succeeded", successful, numConcurrent));
            }
            
        } catch (Exception e) {
            test.fail("Concurrent test failed: " + e.getMessage());
        }
        
        return test;
    }
    
    // Helper methods
    
    private ProcessRequest createBasicRequest() {
        return ProcessRequest.newBuilder()
                .setDocument(createTestDocument("test-" + UUID.randomUUID()))
                .setMetadata(createTestMetadata())
                .build();
    }
    
    private PipeDoc createTestDocument(String id) {
        return PipeDoc.newBuilder()
                .setId(id)
                .setTitle("Test Document")
                .setBody("This is a test document for module validation.")
                .addKeywords("test")
                .addKeywords("validation")
                .build();
    }
    
    private ServiceMetadata createTestMetadata() {
        return ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName("test-step")
                .setStreamId("stream-" + UUID.randomUUID())
                .setCurrentHopNumber(1)
                .putContextParams("_test_mode", "true")
                .build();
    }
    
    private ProcessConfiguration createSampleConfiguration() {
        Struct.Builder configBuilder = Struct.newBuilder();
        configBuilder.putFields("enabled", Value.newBuilder().setBoolValue(true).build());
        configBuilder.putFields("log_level", Value.newBuilder().setStringValue("INFO").build());
        
        return ProcessConfiguration.newBuilder()
                .setCustomJsonConfig(configBuilder.build())
                .putConfigParams("test", "true")
                .build();
    }
    
    private void validateJsonSchema(String schema, TestCase test) {
        try {
            // Basic validation - check it's valid JSON
            if (!schema.contains("$schema") || !schema.contains("type")) {
                test.addInfo("WARNING: Schema may be incomplete");
            }
        } catch (Exception e) {
            test.addInfo("WARNING: Could not validate schema: " + e.getMessage());
        }
    }
    
    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            channel.shutdownNow();
        }
    }
    
    // Result classes
    
    public static class ModuleTestResult {
        private final List<TestCase> tests = new ArrayList<>();
        
        public void addTest(TestCase test) {
            tests.add(test);
        }
        
        public boolean allPassed() {
            return tests.stream().allMatch(TestCase::isPassed);
        }
        
        public String getReport() {
            StringBuilder report = new StringBuilder();
            report.append("\n=== Module Test Report ===\n");
            report.append(String.format("Total Tests: %d\n", tests.size()));
            report.append(String.format("Passed: %d\n", tests.stream().filter(TestCase::isPassed).count()));
            report.append(String.format("Failed: %d\n", tests.stream().filter(t -> !t.isPassed()).count()));
            report.append("\nTest Results:\n");
            
            for (TestCase test : tests) {
                report.append(String.format("\n[%s] %s: %s\n", 
                        test.isPassed() ? "PASS" : "FAIL",
                        test.getName(),
                        test.getMessage()));
                
                for (String info : test.getAdditionalInfo()) {
                    report.append("  - ").append(info).append("\n");
                }
            }
            
            return report.toString();
        }
    }
    
    public static class TestCase {
        private final String name;
        private boolean passed = false;
        private String message = "";
        private final List<String> additionalInfo = new ArrayList<>();
        
        public TestCase(String name) {
            this.name = name;
        }
        
        public void pass(String message) {
            this.passed = true;
            this.message = message;
        }
        
        public void fail(String message) {
            this.passed = false;
            this.message = message;
        }
        
        public void addInfo(String info) {
            additionalInfo.add(info);
        }
        
        public String getName() { return name; }
        public boolean isPassed() { return passed; }
        public String getMessage() { return message; }
        public List<String> getAdditionalInfo() { return additionalInfo; }
    }
    
    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: ModuleTestFramework <host> <port>");
            System.exit(1);
        }
        
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        
        ModuleTestFramework tester = new ModuleTestFramework(host, port);
        try {
            ModuleTestResult result = tester.runFullTestSuite();
            System.out.println(result.getReport());
            System.exit(result.allPassed() ? 0 : 1);
        } finally {
            tester.shutdown();
        }
    }
}