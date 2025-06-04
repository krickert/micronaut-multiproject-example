package com.krickert.yappy.engine.controller.admin;

import com.krickert.search.config.pipeline.model.*;
import com.krickert.yappy.engine.controller.admin.AdminPipelineController.CreatePipelineRequest;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test to verify that CreatePipelineRequest can be properly serialized with Serde
 */
@MicronautTest
public class AdminPipelineControllerSerializationTest {

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testCreatePipelineRequestSerialization() throws Exception {
        // Create the exact same request as in the integration test
        CreatePipelineRequest request = new CreatePipelineRequest();
        request.setPipelineName("test-serialization-pipeline");
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
        
        // Test serialization
        String json = objectMapper.writeValueAsString(request);
        System.out.println("Serialized JSON: " + json);
        assertNotNull(json);
        
        // Test deserialization
        CreatePipelineRequest deserialized = objectMapper.readValue(json, CreatePipelineRequest.class);
        assertNotNull(deserialized);
        assertNotNull(deserialized.getPipelineName());
        assertNotNull(deserialized.getPipelineSteps());
        
        System.out.println("Serialization test passed!");
    }
}