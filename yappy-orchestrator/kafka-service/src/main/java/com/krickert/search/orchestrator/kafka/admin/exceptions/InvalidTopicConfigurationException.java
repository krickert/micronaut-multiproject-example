package com.krickert.search.orchestrator.kafka.admin.exceptions;

public class InvalidTopicConfigurationException extends KafkaAdminServiceException {
    public InvalidTopicConfigurationException(String message) {
        super(message);
    }
    public InvalidTopicConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}