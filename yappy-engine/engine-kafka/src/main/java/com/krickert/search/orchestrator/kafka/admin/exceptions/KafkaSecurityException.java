package com.krickert.search.orchestrator.kafka.admin.exceptions;

// For ACL issues etc.
public class KafkaSecurityException extends KafkaAdminServiceException {
    public KafkaSecurityException(String message) {
        super(message);
    }
    public KafkaSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}