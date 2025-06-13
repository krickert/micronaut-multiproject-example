package com.krickert.search.engine.core.routing;

import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.model.PipeStream;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Default implementation of Kafka routing strategy.
 * 
 * Topic naming conventions:
 * - Main topics: {cluster}.{pipeline}.{step}
 * - DLQ topics: {cluster}.{pipeline}.{step}.dlq
 * - Retry topics: {cluster}.{pipeline}.{step}.retry.{attempt}
 * 
 * Partitioning strategy:
 * - Uses document ID by default for consistent routing
 * - Can be overridden by step configuration
 */
@Singleton
public class DefaultKafkaRoutingStrategy implements KafkaRoutingStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(DefaultKafkaRoutingStrategy.class);
    
    private static final String TOPIC_SEPARATOR = ".";
    private static final String DLQ_SUFFIX = "dlq";
    private static final String RETRY_PREFIX = "retry";
    
    private final String clusterName;
    
    @Inject
    public DefaultKafkaRoutingStrategy(@Value("${yappy.cluster.name}") String clusterName) {
        this.clusterName = clusterName;
        logger.info("Initialized DefaultKafkaRoutingStrategy for cluster: {}", clusterName);
    }
    
    @Override
    public String determineTopicName(PipelineStepConfig stepConfig, PipeStream pipeStream) {
        // Check if step has explicit topic configuration
        String explicitTopic = getExplicitTopicFromConfig(stepConfig);
        if (explicitTopic != null) {
            logger.debug("Using explicit topic {} for step {}", explicitTopic, stepConfig.stepName());
            return explicitTopic;
        }
        
        // Build topic name from convention
        String pipelineName = pipeStream.getCurrentPipelineName();
        String stepName = stepConfig.stepName();
        
        String topicName = buildTopicName(clusterName, pipelineName, stepName);
        logger.debug("Determined topic name: {} for step: {}", topicName, stepName);
        
        return topicName;
    }
    
    @Override
    public String determinePartitionKey(PipeStream pipeStream) {
        // Primary strategy: Use document ID for consistent routing
        if (pipeStream.hasDocument() && !pipeStream.getDocument().getId().isEmpty()) {
            return pipeStream.getDocument().getId();
        }
        
        // Fallback: Use stream ID
        return pipeStream.getStreamId();
    }
    
    @Override
    public String getDeadLetterTopic(String originalTopic) {
        return originalTopic + TOPIC_SEPARATOR + DLQ_SUFFIX;
    }
    
    @Override
    public String getRetryTopic(String originalTopic, int retryAttempt) {
        return String.format("%s%s%s%s%d", 
                originalTopic, 
                TOPIC_SEPARATOR, 
                RETRY_PREFIX, 
                TOPIC_SEPARATOR, 
                retryAttempt);
    }
    
    @Override
    public boolean isCompactedTopic(String topicName) {
        // Topics ending with .state or .snapshot should be compacted
        return topicName.endsWith(".state") || topicName.endsWith(".snapshot");
    }
    
    private String getExplicitTopicFromConfig(PipelineStepConfig stepConfig) {
        // Check if step has Kafka outputs configured
        if (stepConfig.outputs() != null && !stepConfig.outputs().isEmpty()) {
            for (Map.Entry<String, PipelineStepConfig.OutputTarget> entry : stepConfig.outputs().entrySet()) {
                PipelineStepConfig.OutputTarget output = entry.getValue();
                if (output.transportType() == TransportType.KAFKA && 
                    output.kafkaTransport() != null) {
                    return output.kafkaTransport().topic();
                }
            }
        }
        
        return null;
    }
    
    private String buildTopicName(String cluster, String pipeline, String step) {
        // Sanitize names to be Kafka-compliant
        cluster = sanitizeForKafka(cluster);
        pipeline = sanitizeForKafka(pipeline);
        step = sanitizeForKafka(step);
        
        return String.join(TOPIC_SEPARATOR, cluster, pipeline, step);
    }
    
    private String sanitizeForKafka(String name) {
        if (name == null) {
            return "unknown";
        }
        
        // Kafka topic names can contain alphanumeric, '.', '_', and '-'
        // Replace any other characters with underscore
        return name.replaceAll("[^a-zA-Z0-9._-]", "_")
                   .toLowerCase();
    }
}