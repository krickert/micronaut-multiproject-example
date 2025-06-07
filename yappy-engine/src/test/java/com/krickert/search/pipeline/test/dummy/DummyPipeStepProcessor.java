package com.krickert.search.pipeline.test.dummy;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import com.google.protobuf.Empty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dummy PipeStepProcessor implementation for self-contained engine testing.
 * This processor simply echoes the input document and adds some test metadata.
 * It can be configured to simulate different behaviors like delays or errors.
 */
// Not annotated with @GrpcService to prevent Micronaut from managing it
// This allows us to create our own isolated gRPC server for testing
public class DummyPipeStepProcessor extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
    
    private static final Logger LOG = LoggerFactory.getLogger(DummyPipeStepProcessor.class);
    private final AtomicInteger processCount = new AtomicInteger(0);
    
    private final String defaultBehavior;
    private final String defaultSuffix;
    
    public DummyPipeStepProcessor() {
        this("append", " [PROCESSED]");
    }
    
    public DummyPipeStepProcessor(String defaultBehavior, String defaultSuffix) {
        this.defaultBehavior = defaultBehavior;
        this.defaultSuffix = defaultSuffix;
    }
    
    @Override
    public void getServiceRegistration(Empty request, StreamObserver<ServiceRegistrationData> responseObserver) {
        // Return dummy module registration info
        String configSchema = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "DummyProcessorConfig",
              "description": "Configuration for the dummy test processor",
              "type": "object",
              "properties": {
                "behavior": {
                  "type": "string",
                  "enum": ["echo", "append", "uppercase", "clear"],
                  "default": "append",
                  "description": "Processing behavior: echo (no change), append (add suffix), uppercase (convert to uppercase), clear (empty body)"
                },
                "simulate_error": {
                  "type": "boolean",
                  "default": false,
                  "description": "If true, processor will simulate an error"
                },
                "delay_ms": {
                  "type": "integer",
                  "minimum": 0,
                  "default": 0,
                  "description": "Artificial delay in milliseconds to simulate slow processing"
                }
              }
            }
            """;
        
        ServiceRegistrationData registration = ServiceRegistrationData.newBuilder()
                .setModuleName("dummy-processor")
                .setJsonConfigSchema(configSchema)
                .build();
        
        LOG.info("Returning service registration for dummy-processor");
        responseObserver.onNext(registration);
        responseObserver.onCompleted();
    }
    
    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        int count = processCount.incrementAndGet();
        LOG.info("DummyPipeStepProcessor processing request #{} for document: {}", count, request.getDocument().getId());
        
        try {
            // Extract configuration
            ProcessConfiguration config = request.getConfig();
            Struct customConfig = config.getCustomJsonConfig();
            
            // Check for test behaviors in config
            boolean simulateError = false;
            int delayMs = 0;
            String testBehavior = defaultBehavior; // use configured default
            
            if (customConfig != null && customConfig.getFieldsCount() > 0) {
                if (customConfig.containsFields("simulate_error")) {
                    simulateError = customConfig.getFieldsOrThrow("simulate_error").getBoolValue();
                }
                if (customConfig.containsFields("delay_ms")) {
                    delayMs = (int) customConfig.getFieldsOrThrow("delay_ms").getNumberValue();
                }
                if (customConfig.containsFields("behavior")) {
                    testBehavior = customConfig.getFieldsOrThrow("behavior").getStringValue();
                }
            }
            
            // Simulate delay if configured
            if (delayMs > 0) {
                LOG.info("Simulating delay of {}ms", delayMs);
                Thread.sleep(delayMs);
            }
            
            // Simulate error if configured
            if (simulateError) {
                LOG.error("Simulating error as configured");
                responseObserver.onError(new RuntimeException("Simulated error for testing"));
                return;
            }
            
            // Process based on behavior
            PipeDoc outputDoc = processDocument(request.getDocument(), testBehavior, request.getMetadata());
            
            // Build response
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .setOutputDoc(outputDoc)
                    .addProcessorLogs("DummyProcessor: Processed document " + request.getDocument().getId())
                    .addProcessorLogs("DummyProcessor: Behavior=" + testBehavior + ", ProcessCount=" + count)
                    .build();
            
            LOG.info("DummyPipeStepProcessor completed processing for document: {}", request.getDocument().getId());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.error("Error in DummyPipeStepProcessor", e);
            responseObserver.onError(e);
        }
    }
    
    private PipeDoc processDocument(PipeDoc inputDoc, String behavior, ServiceMetadata metadata) {
        PipeDoc.Builder outputDoc = inputDoc.toBuilder();
        
        // Add test metadata to custom_data to show processing happened
        Struct.Builder customDataBuilder = Struct.newBuilder();
        if (inputDoc.hasCustomData()) {
            customDataBuilder.mergeFrom(inputDoc.getCustomData());
        }
        
        // Add our processing metadata
        customDataBuilder
            .putFields("dummy_processed", Value.newBuilder().setStringValue("true").build())
            .putFields("dummy_behavior", Value.newBuilder().setStringValue(behavior).build())
            .putFields("dummy_process_count", Value.newBuilder().setNumberValue(processCount.get()).build())
            .putFields("dummy_pipeline", Value.newBuilder().setStringValue(metadata.getPipelineName()).build())
            .putFields("dummy_step", Value.newBuilder().setStringValue(metadata.getPipeStepName()).build());
        
        outputDoc.setCustomData(customDataBuilder.build());
        
        // Behavior-specific processing
        switch (behavior) {
            case "echo":
                // Just return with metadata updates
                break;
                
            case "append":
                // Append to title (to match test expectations)
                if (inputDoc.hasTitle()) {
                    outputDoc.setTitle(inputDoc.getTitle() + defaultSuffix);
                }
                // Also append to body
                if (inputDoc.hasBody()) {
                    String currentBody = inputDoc.getBody();
                    outputDoc.setBody(currentBody + "\n[Processed by DummyPipeStepProcessor]");
                }
                break;
                
            case "uppercase":
                // Convert body to uppercase
                outputDoc.setBody(inputDoc.getBody().toUpperCase());
                break;
                
            case "clear":
                // Clear the body (for testing error handling)
                outputDoc.setBody("");
                break;
                
            default:
                LOG.warn("Unknown behavior: {}, defaulting to echo", behavior);
        }
        
        return outputDoc.build();
    }
    
    public int getProcessCount() {
        return processCount.get();
    }
    
    public void resetProcessCount() {
        processCount.set(0);
    }
}