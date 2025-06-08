package com.krickert.search.engine.routing.impl;

import com.google.protobuf.Timestamp;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.engine.routing.PipelineRouter;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.model.ErrorData;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class PipelineRouterImplTest {
    
    @Inject
    private PipelineRouter pipelineRouter;
    
    private PipeStream testPipeStream;
    private PipelineConfig testPipelineConfig;
    
    @BeforeEach
    void setup() {
        // Create test PipeStream
        testPipeStream = PipeStream.newBuilder()
            .setStreamId("test-stream-123")
            .setDocument(PipeDoc.newBuilder()
                .setId("doc-123")
                .setTitle("Test Document")
                .build())
            .setCurrentPipelineName("test-pipeline")
            .setCurrentHopNumber(0)
            .build();
        
        // Create test pipeline config with steps
        var step1 = new PipelineStepConfig(
            "step1",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-1", null)
        );
        
        var step2 = new PipelineStepConfig(
            "step2", 
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-2", null),
            new PipelineStepConfig.JsonConfigOptions(Map.of("dependsOn", "step1"))
        );
        
        var step3 = new PipelineStepConfig(
            "step3",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-3", null)
        );
        
        Map<String, PipelineStepConfig> steps = new TreeMap<>();
        steps.put("step1", step1);
        steps.put("step2", step2);
        steps.put("step3", step3);
        
        testPipelineConfig = new PipelineConfig(
            "test-pipeline",
            steps
        );
    }
    
    @Test
    @DisplayName("Should get next step when no steps have been executed")
    void testGetNextStepInitial() {
        Optional<PipelineStepConfig> nextStep = pipelineRouter.getNextStep(testPipeStream, testPipelineConfig);
        
        assertTrue(nextStep.isPresent());
        assertEquals("step1", nextStep.get().stepName());
    }
    
    @Test
    @DisplayName("Should get next step respecting dependencies")
    void testGetNextStepWithDependencies() {
        // Add step1 to execution history
        PipeStream streamWithHistory = testPipeStream.toBuilder()
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("step1")
                .setStatus("SUCCESS")
                .setHopNumber(1)
                .build())
            .build();
        
        Optional<PipelineStepConfig> nextStep = pipelineRouter.getNextStep(streamWithHistory, testPipelineConfig);
        
        assertTrue(nextStep.isPresent());
        // Should return step2 since step1 is complete and step2 depends on it
        assertEquals("step2", nextStep.get().stepName());
    }
    
    @Test
    @DisplayName("Should skip step with unmet dependencies")
    void testGetNextStepUnmetDependencies() {
        // Create a pipeline where step2 depends on step1, but only step3 is independent
        var step1 = new PipelineStepConfig(
            "step1",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-1", null),
            new PipelineStepConfig.JsonConfigOptions(Map.of("dependsOn", "step0")) // Depends on non-existent step
        );
        
        var step2 = new PipelineStepConfig(
            "step2",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-2", null)
        );
        
        Map<String, PipelineStepConfig> validationSteps = new TreeMap<>();
        validationSteps.put("step1", step1);
        validationSteps.put("step2", step2);
        
        PipelineConfig config = new PipelineConfig(
            "test-pipeline",
            validationSteps
        );
        
        Optional<PipelineStepConfig> nextStep = pipelineRouter.getNextStep(testPipeStream, config);
        
        assertTrue(nextStep.isPresent());
        assertEquals("step2", nextStep.get().stepName()); // Should skip step1 due to unmet dependency
    }
    
    @Test
    @DisplayName("Should detect pipeline completion")
    void testIsPipelineComplete() {
        // Complete all steps
        PipeStream completedStream = testPipeStream.toBuilder()
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("step1")
                .setStatus("SUCCESS")
                .build())
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("step2")
                .setStatus("SUCCESS")
                .build())
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("step3")
                .setStatus("SUCCESS")
                .build())
            .build();
        
        assertTrue(pipelineRouter.isPipelineComplete(completedStream, testPipelineConfig));
    }
    
    @Test
    @DisplayName("Should detect pipeline incomplete when steps remain")
    void testIsPipelineIncomplete() {
        // Only complete step1
        PipeStream partialStream = testPipeStream.toBuilder()
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("step1")
                .setStatus("SUCCESS")
                .build())
            .build();
        
        assertFalse(pipelineRouter.isPipelineComplete(partialStream, testPipelineConfig));
    }
    
    @Test
    @DisplayName("Should consider pipeline complete on critical error")
    void testIsPipelineCompleteOnError() {
        // Add critical error
        PipeStream errorStream = testPipeStream.toBuilder()
            .setStreamErrorData(ErrorData.newBuilder()
                .setErrorMessage("Critical error")
                .setOriginatingStepName("step1")
                .setTimestamp(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .build())
            .build();
        
        // Even though no steps are complete, pipeline should be considered complete due to error
        assertTrue(pipelineRouter.isPipelineComplete(errorStream, testPipelineConfig));
    }
    
    @Test
    @DisplayName("Should validate pipeline with all modules available")
    void testValidatePipelineSuccess() {
        List<String> availableModules = List.of("test-module-1", "test-module-2", "test-module-3");
        
        StepVerifier.create(pipelineRouter.validatePipeline(testPipelineConfig, availableModules))
            .assertNext(result -> {
                assertTrue(result.isValid());
                assertTrue(result.errors().isEmpty());
                assertTrue(result.warnings().isEmpty());
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should fail validation when modules are missing")
    void testValidatePipelineMissingModules() {
        List<String> availableModules = List.of("test-module-1"); // Missing module-2 and module-3
        
        StepVerifier.create(pipelineRouter.validatePipeline(testPipelineConfig, availableModules))
            .assertNext(result -> {
                assertFalse(result.isValid());
                assertEquals(2, result.errors().size());
                assertTrue(result.errors().get(0).contains("test-module-2"));
                assertTrue(result.errors().get(1).contains("test-module-3"));
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should detect circular dependencies")
    void testValidatePipelineCircularDependencies() {
        // Create circular dependency: step1 -> step2 -> step3 -> step1
        var step1 = new PipelineStepConfig(
            "step1",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-1", null),
            new PipelineStepConfig.JsonConfigOptions(Map.of("dependsOn", "step3"))
        );
        
        var step2 = new PipelineStepConfig(
            "step2",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-2", null),
            new PipelineStepConfig.JsonConfigOptions(Map.of("dependsOn", "step1"))
        );
        
        var step3 = new PipelineStepConfig(
            "step3",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-3", null),
            new PipelineStepConfig.JsonConfigOptions(Map.of("dependsOn", "step2"))
        );
        
        Map<String, PipelineStepConfig> circularSteps = new TreeMap<>();
        circularSteps.put("step1", step1);
        circularSteps.put("step2", step2);
        circularSteps.put("step3", step3);
        
        PipelineConfig circularConfig = new PipelineConfig(
            "circular-pipeline",
            circularSteps
        );
        
        List<String> availableModules = List.of("test-module-1", "test-module-2", "test-module-3");
        
        StepVerifier.create(pipelineRouter.validatePipeline(circularConfig, availableModules))
            .assertNext(result -> {
                assertFalse(result.isValid());
                assertTrue(result.errors().stream().anyMatch(e -> e.contains("circular")));
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should create execution plan")
    void testCreateExecutionPlan() {
        List<PipelineStepConfig> plan = pipelineRouter.createExecutionPlan(testPipelineConfig);
        
        assertEquals(3, plan.size());
        assertEquals("step1", plan.get(0).stepName());
        assertEquals("step2", plan.get(1).stepName());
        assertEquals("step3", plan.get(2).stepName());
    }
    
    @Test
    @DisplayName("Should check if step is executed")
    void testIsStepExecuted() {
        List<StepExecutionRecord> history = List.of(
            StepExecutionRecord.newBuilder()
                .setStepName("step1")
                .setStatus("SUCCESS")
                .build(),
            StepExecutionRecord.newBuilder()
                .setStepName("step2")
                .setStatus("FAILURE")
                .build()
        );
        
        assertTrue(pipelineRouter.isStepExecuted("step1", history));
        assertTrue(pipelineRouter.isStepExecuted("step2", history));
        assertFalse(pipelineRouter.isStepExecuted("step3", history));
    }
    
    @Test
    @DisplayName("Should get current step from execution history")
    void testGetCurrentStep() {
        // Create history with step2 still in progress (no end time)
        PipeStream streamWithCurrentStep = testPipeStream.toBuilder()
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("step1")
                .setStatus("SUCCESS")
                .setStartTime(createTimestamp())
                .setEndTime(createTimestamp())
                .build())
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("step2")
                .setStatus("IN_PROGRESS")
                .setStartTime(createTimestamp())
                // No end time - still in progress
                .build())
            .build();
        
        Optional<String> currentStep = pipelineRouter.getCurrentStep(streamWithCurrentStep);
        
        assertTrue(currentStep.isPresent());
        assertEquals("step2", currentStep.get());
    }
    
    @Test
    @DisplayName("Should handle empty pipeline")
    void testEmptyPipeline() {
        PipelineConfig emptyConfig = new PipelineConfig("empty", new TreeMap<>());
        
        Optional<PipelineStepConfig> nextStep = pipelineRouter.getNextStep(testPipeStream, emptyConfig);
        assertFalse(nextStep.isPresent());
        
        assertTrue(pipelineRouter.isPipelineComplete(testPipeStream, emptyConfig));
    }
    
    @Test
    @DisplayName("Should handle optional steps")
    void testOptionalSteps() {
        // Create pipeline with optional step
        var requiredStep = new PipelineStepConfig(
            "required-step",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-1", null)
        );
        
        var optionalStep = new PipelineStepConfig(
            "optional-step",
            StepType.PIPELINE,
            new PipelineStepConfig.ProcessorInfo("test-module-2", null),
            new PipelineStepConfig.JsonConfigOptions(Map.of("optional", "true"))
        );
        
        Map<String, PipelineStepConfig> optionalSteps = new TreeMap<>();
        optionalSteps.put("required-step", requiredStep);
        optionalSteps.put("optional-step", optionalStep);
        
        PipelineConfig configWithOptional = new PipelineConfig(
            "optional-pipeline",
            optionalSteps
        );
        
        // Complete only required step
        PipeStream streamWithRequiredComplete = testPipeStream.toBuilder()
            .addHistory(StepExecutionRecord.newBuilder()
                .setStepName("required-step")
                .setStatus("SUCCESS")
                .build())
            .build();
        
        // Pipeline should be complete even though optional step wasn't executed
        assertTrue(pipelineRouter.isPipelineComplete(streamWithRequiredComplete, configWithOptional));
    }
    
    private Timestamp createTimestamp() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
            .setSeconds(now.getEpochSecond())
            .setNanos(now.getNano())
            .build();
    }
}