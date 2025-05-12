package com.krickert.search.pipeline.kafka.admin.exceptions;

public class ConsumerGroupNotFoundException extends KafkaAdminServiceException {
    public ConsumerGroupNotFoundException(String groupId) {
        super("Consumer group '" + groupId + "' not found.");
    }
    public ConsumerGroupNotFoundException(String groupId, Throwable cause) {
        super("Consumer group '" + groupId + "' not found.", cause);
    }
}