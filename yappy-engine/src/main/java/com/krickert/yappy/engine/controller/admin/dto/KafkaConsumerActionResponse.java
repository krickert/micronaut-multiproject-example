package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;

@Data
@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaConsumerActionResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("message")
    private String message;

    public KafkaConsumerActionResponse() {
    }

    @JsonCreator
    public KafkaConsumerActionResponse(
            @JsonProperty("success") boolean success, 
            @JsonProperty("message") String message) {
        this.success = success;
        this.message = message;
    }
}
