package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import lombok.Data;

@Data
@Introspected
public class KafkaConsumerActionResponse {

    private boolean success;
    private String message;

    public KafkaConsumerActionResponse() {
    }

    public KafkaConsumerActionResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}
