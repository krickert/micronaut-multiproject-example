package com.krickert.search.orchestrator.kafka.admin.exceptions;

public class TopicAlreadyExistsException extends KafkaAdminServiceException {
    public TopicAlreadyExistsException(String topicName) {
        super("Topic '" + topicName + "' already exists.");
    }
     public TopicAlreadyExistsException(String topicName, Throwable cause) {
        super("Topic '" + topicName + "' already exists.", cause);
    }
}