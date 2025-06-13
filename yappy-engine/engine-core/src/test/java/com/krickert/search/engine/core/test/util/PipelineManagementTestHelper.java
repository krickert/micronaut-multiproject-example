package com.krickert.search.engine.core.test.util;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.ErrorData;
import com.krickert.search.config.pipeline.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Test helper for pipeline management operations.
 * Provides utilities for creating pipelines, steps, and test data.
 */
public class PipelineManagementTestHelper {
    
    private static final Logger LOG = LoggerFactory.getLogger(PipelineManagementTestHelper.class);
    
    /**
     * Creates a simple test pipeline configuration
     */
    public static PipelineConfig createSimplePipeline(String pipelineName, List<String> stepNames) {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        
        for (int i = 0; i < stepNames.size(); i++) {
            String stepName = stepNames.get(i);
            String nextStep = (i < stepNames.size() - 1) ? stepNames.get(i + 1) : null;
            
            PipelineStepConfig step = createPipelineStep(stepName, stepName.toLowerCase(), nextStep);
            steps.put(stepName, step);
        }
        
        return PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(steps)
                .build();
    }
    
    /**
     * Creates a pipeline step configuration
     */
    public static PipelineStepConfig createPipelineStep(String stepName, String moduleType, String nextStep) {
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                moduleType + "-service", // grpcServiceName
                null  // internalProcessorBeanName
        );
        
        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        if (nextStep != null) {
            // Simple output to next step
            outputs.put("default", new PipelineStepConfig.OutputTarget(
                    nextStep,  // targetStepName
                    TransportType.INTERNAL,  // transportType
                    null,  // grpcTransport
                    null   // kafkaTransport
            ));
        }
        
        return new PipelineStepConfig(
                stepName,  // stepName
                StepType.PIPELINE,  // stepType
                "Step: " + stepName,  // description
                null,  // customConfigSchemaId
                null,  // customConfig
                null,  // kafkaInputs
                outputs,  // outputs
                null,  // maxRetries
                null,  // retryBackoffMs
                null,  // maxRetryBackoffMs
                null,  // retryBackoffMultiplier
                null,  // stepTimeoutMs
                processorInfo  // processorInfo
        );
    }
    
    /**
     * Creates a sample PipeStream for testing
     */
    public static PipeStream createSamplePipeStream(String docId, String content) {
        return createSamplePipeStream(docId, content, Map.of());
    }
    
    /**
     * Creates a sample PipeStream with metadata
     */
    public static PipeStream createSamplePipeStream(String docId, String content, Map<String, String> metadata) {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        Struct.Builder structBuilder = Struct.newBuilder();
        metadata.forEach((key, value) -> {
            structBuilder.putFields(key, Value.newBuilder().setStringValue(value).build());
        });
        
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(docId)
                .setBody(content)
                .setCreationDate(now)
                .setLastModifiedDate(now)
                .setProcessedDate(now)
                .setCustomData(structBuilder.build())
                .build();
        
        return PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setCurrentHopNumber(0)
                .build();
    }
    
    /**
     * Creates a batch of PipeStreams for testing
     */
    public static List<PipeStream> createBatchPipeStreams(int count, String contentPrefix) {
        List<PipeStream> streams = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            PipeStream stream = createSamplePipeStream(
                    "doc-" + i,
                    contentPrefix + " " + i,
                    Map.of("index", String.valueOf(i))
            );
            streams.add(stream);
        }
        
        return streams;
    }
    
    /**
     * Creates a PipeStream with an error
     */
    public static PipeStream createErrorPipeStream(String docId, String errorMessage, String errorCode) {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        ErrorData error = ErrorData.newBuilder()
                .setErrorCode(errorCode)
                .setErrorMessage(errorMessage)
                .setOriginatingStepName("test-module")
                .setTimestamp(now)
                .build();
        
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(docId)
                .setBody("[Document with error]")
                .setCreationDate(now)
                .build();
        
        return PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("error-handler")
                .setCurrentHopNumber(0)
                .setStreamErrorData(error)
                .build();
    }
    
    /**
     * Creates a complete pipeline graph configuration
     */
    public static PipelineGraphConfig createPipelineGraph(String graphName, List<PipelineConfig> pipelines) {
        Map<String, PipelineConfig> pipelineMap = new HashMap<>();
        for (PipelineConfig pipeline : pipelines) {
            pipelineMap.put(pipeline.name(), pipeline);
        }
        
        return PipelineGraphConfig.builder()
                .pipelines(pipelineMap)
                .build();
    }
    
    /**
     * Creates a Kafka-enabled pipeline step
     */
    public static PipelineStepConfig createKafkaStep(String stepName, String topicName, String nextStep) {
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                null,  // grpcServiceName
                "kafkaPublisher"  // internalProcessorBeanName
        );
        
        // Configure outputs - both Kafka topic and potential next step
        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        
        if (nextStep != null) {
            outputs.put("default", new PipelineStepConfig.OutputTarget(
                    nextStep,  // targetStepName
                    TransportType.INTERNAL,  // transportType
                    null,  // grpcTransport
                    null   // kafkaTransport
            ));
        }
        
        // Add Kafka output for the topic
        outputs.put("kafka", new PipelineStepConfig.OutputTarget(
                topicName,  // targetStepName (used as topic name)
                TransportType.KAFKA,  // transportType
                null,  // grpcTransport
                new KafkaTransportConfig(topicName, Map.of())  // kafkaTransport
        ));
        
        return new PipelineStepConfig(
                stepName,  // stepName
                StepType.PIPELINE,  // stepType
                "Kafka step: " + stepName,  // description
                null,  // customConfigSchemaId
                null,  // customConfig
                null,  // kafkaInputs
                outputs,  // outputs
                null,  // maxRetries
                null,  // retryBackoffMs
                null,  // maxRetryBackoffMs
                null,  // retryBackoffMultiplier
                null,  // stepTimeoutMs
                processorInfo  // processorInfo
        );
    }
    
    /**
     * Creates an internal (in-process) pipeline step
     */
    public static PipelineStepConfig createInternalStep(String stepName, String className, String nextStep) {
        // Extract just the bean name from the full class name
        String beanName = className.substring(className.lastIndexOf('.') + 1);
        beanName = Character.toLowerCase(beanName.charAt(0)) + beanName.substring(1);
        
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                null,  // grpcServiceName
                beanName  // internalProcessorBeanName
        );
        
        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        if (nextStep != null) {
            outputs.put("default", new PipelineStepConfig.OutputTarget(
                    nextStep,  // targetStepName
                    TransportType.INTERNAL,  // transportType
                    null,  // grpcTransport
                    null   // kafkaTransport
            ));
        }
        
        return new PipelineStepConfig(
                stepName,  // stepName
                StepType.PIPELINE,  // stepType
                "Internal step: " + stepName,  // description
                null,  // customConfigSchemaId
                null,  // customConfig
                null,  // kafkaInputs
                outputs,  // outputs
                null,  // maxRetries
                null,  // retryBackoffMs
                null,  // maxRetryBackoffMs
                null,  // retryBackoffMultiplier
                null,  // stepTimeoutMs
                processorInfo  // processorInfo
        );
    }
    
    /**
     * Utility to log pipeline configuration details
     */
    public static void logPipelineDetails(PipelineConfig pipeline) {
        LOG.info("Pipeline: {}", pipeline.name());
        LOG.info("  Total steps: {}", pipeline.pipelineSteps().size());
        
        for (Map.Entry<String, PipelineStepConfig> entry : pipeline.pipelineSteps().entrySet()) {
            LOG.info("  - Step: {} - {}", entry.getKey(), 
                    entry.getValue().description());
        }
    }
}