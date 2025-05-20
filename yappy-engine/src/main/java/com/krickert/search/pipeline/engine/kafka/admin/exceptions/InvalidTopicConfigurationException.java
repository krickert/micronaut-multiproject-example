package com.krickert.search.pipeline.engine.kafka.admin.exceptions;

public class InvalidTopicConfigurationException extends KafkaAdminServiceException {
    public InvalidTopicConfigurationException(String message) {
        super(message);
    }
    public InvalidTopicConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}