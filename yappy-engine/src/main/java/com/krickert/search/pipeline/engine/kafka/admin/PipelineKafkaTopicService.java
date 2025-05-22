package com.krickert.search.pipeline.engine.kafka.admin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for creating and managing Kafka topics for pipeline steps.
 * Creates topics with the naming pattern: pipeline.[pipelineName].step.[stepName].(input|output|error|dead-letter)
 */
public interface PipelineKafkaTopicService {

    /**
     * Topic type enum for pipeline step topics.
     */
    enum TopicType {
        INPUT("input"),
        OUTPUT("output"),
        ERROR("error"),
        DEAD_LETTER("dead-letter");

        private final String suffix;

        TopicType(String suffix) {
            this.suffix = suffix;
        }

        public String getSuffix() {
            return suffix;
        }
    }

    /**
     * Creates a Kafka topic for a pipeline step with the specified type.
     * The topic name will follow the pattern: pipeline.[pipelineName].step.[stepName].[topicType]
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @param topicType The type of the topic (input, output, error, dead-letter)
     * @return CompletableFuture<Void> that completes when the topic is created
     */
    CompletableFuture<Void> createTopicAsync(String pipelineName, String stepName, TopicType topicType);

    /**
     * Creates all standard Kafka topics for a pipeline step (input, output, error, dead-letter).
     * The topic names will follow the pattern: pipeline.[pipelineName].step.[stepName].[topicType]
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return CompletableFuture<Void> that completes when all topics are created
     */
    CompletableFuture<Void> createAllTopicsAsync(String pipelineName, String stepName);

    /**
     * Generates a topic name for a pipeline step with the specified type.
     * The topic name will follow the pattern: pipeline.[pipelineName].step.[stepName].[topicType]
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @param topicType The type of the topic (input, output, error, dead-letter)
     * @return The generated topic name
     */
    String generateTopicName(String pipelineName, String stepName, TopicType topicType);

    /**
     * Lists all topics for a pipeline step.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return CompletableFuture<List<String>> containing the names of all topics for the step
     */
    CompletableFuture<List<String>> listTopicsForStepAsync(String pipelineName, String stepName);

    /**
     * Synchronous version of createTopicAsync.
     */
    void createTopic(String pipelineName, String stepName, TopicType topicType);

    /**
     * Synchronous version of createAllTopicsAsync.
     */
    void createAllTopics(String pipelineName, String stepName);

    /**
     * Synchronous version of listTopicsForStepAsync.
     */
    List<String> listTopicsForStep(String pipelineName, String stepName);
}