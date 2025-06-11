package com.krickert.search.engine.core.test.util;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.model.PipeStream;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple unit test to verify test utilities compile and work correctly.
 * This test doesn't require any containers or external dependencies.
 */
public class TestUtilCompilationTest {
    
    @Test
    void testPipelineCreation() {
        // Create a simple pipeline
        List<String> steps = Arrays.asList("step1", "step2", "step3");
        PipelineConfig pipeline = PipelineManagementTestHelper.createSimplePipeline("test-pipeline", steps);
        
        assertThat(pipeline).isNotNull();
        assertThat(pipeline.name()).isEqualTo("test-pipeline");
        assertThat(pipeline.pipelineSteps()).hasSize(3);
    }
    
    @Test
    void testDataBuilderCreation() {
        // Create test data
        PipeStream stream = TestDataBuilder.create()
                .withTextDocument("doc1", "Test content")
                .buildSingle();
        
        assertThat(stream).isNotNull();
        assertThat(stream.getDocument()).isNotNull();
        assertThat(stream.getDocument().getId()).isEqualTo("doc1");
        assertThat(stream.getDocument().getBody()).isEqualTo("Test content");
    }
    
    @Test
    void testBatchDataCreation() {
        // Create batch data
        List<PipeStream> streams = TestDataBuilder.create()
                .withDocumentBatch("batch", 5)
                .build();
        
        assertThat(streams).hasSize(5);
        assertThat(streams.get(0).getDocument().getId()).isEqualTo("batch-0");
    }
    
    @Test
    void testErrorPipeStreamCreation() {
        // Create error stream
        PipeStream errorStream = PipelineManagementTestHelper.createErrorPipeStream(
                "error-doc", "Test error", "ERR001"
        );
        
        assertThat(errorStream).isNotNull();
        assertThat(errorStream.hasStreamErrorData()).isTrue();
        assertThat(errorStream.getStreamErrorData().getErrorCode()).isEqualTo("ERR001");
        assertThat(errorStream.getStreamErrorData().getErrorMessage()).isEqualTo("Test error");
    }
    
    @Test
    void testPipelineStepCreation() {
        // Create pipeline steps
        var grpcStep = PipelineManagementTestHelper.createPipelineStep("grpc-step", "processor", "next-step");
        var kafkaStep = PipelineManagementTestHelper.createKafkaStep("kafka-step", "output-topic", null);
        var internalStep = PipelineManagementTestHelper.createInternalStep("internal-step", "com.example.Processor", null);
        
        assertThat(grpcStep.stepName()).isEqualTo("grpc-step");
        assertThat(kafkaStep.stepName()).isEqualTo("kafka-step");
        assertThat(internalStep.stepName()).isEqualTo("internal-step");
    }
}