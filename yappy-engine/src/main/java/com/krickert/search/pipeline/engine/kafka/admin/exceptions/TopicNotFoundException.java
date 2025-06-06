package com.krickert.search.pipeline.engine.kafka.admin.exceptions;

public class TopicNotFoundException extends KafkaAdminServiceException {
    public TopicNotFoundException(String topicName) {
        super("Topic '" + topicName + "' not found.");
    }
    public TopicNotFoundException(String topicName, Throwable cause) {
        super("Topic '" + topicName + "' not found.", cause);
    }
}