package com.krickert.search.pipeline.engine.kafka.admin.exceptions;

public class KafkaOperationTimeoutException extends KafkaAdminServiceException {
    public KafkaOperationTimeoutException(String message) {
        super(message);
    }
    public KafkaOperationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}