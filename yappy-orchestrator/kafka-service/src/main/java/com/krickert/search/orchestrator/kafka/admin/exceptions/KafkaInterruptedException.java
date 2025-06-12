package com.krickert.search.orchestrator.kafka.admin.exceptions;

// If a blocking call is interrupted
public class KafkaInterruptedException extends KafkaAdminServiceException {
    public KafkaInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}