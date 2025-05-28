package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Introspected
public class KafkaConsumerActionRequest {

    @NotBlank(message = "Pipeline name must be provided.")
    private String pipelineName;

    @NotBlank(message = "Step name must be provided.")
    private String stepName;

    @NotBlank(message = "Topic must be provided.")
    private String topic;

    @NotBlank(message = "Group ID must be provided.")
    private String groupId;

    public KafkaConsumerActionRequest() {
    }

    public KafkaConsumerActionRequest(String pipelineName, String stepName, String topic, String groupId) {
        this.pipelineName = pipelineName;
        this.stepName = stepName;
        this.topic = topic;
        this.groupId = groupId;
    }
}
