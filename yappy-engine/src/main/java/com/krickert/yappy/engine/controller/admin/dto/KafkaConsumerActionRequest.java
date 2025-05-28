package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaConsumerActionRequest {

    @NotBlank(message = "Pipeline name must be provided.")
    @JsonProperty("pipelineName")
    private String pipelineName;

    @NotBlank(message = "Step name must be provided.")
    @JsonProperty("stepName")
    private String stepName;

    @NotBlank(message = "Topic must be provided.")
    @JsonProperty("topic")
    private String topic;

    @NotBlank(message = "Group ID must be provided.")
    @JsonProperty("groupId")
    private String groupId;

    public KafkaConsumerActionRequest() {
    }

    @JsonCreator
    public KafkaConsumerActionRequest(
            @JsonProperty("pipelineName") String pipelineName, 
            @JsonProperty("stepName") String stepName, 
            @JsonProperty("topic") String topic, 
            @JsonProperty("groupId") String groupId) {
        this.pipelineName = pipelineName;
        this.stepName = stepName;
        this.topic = topic;
        this.groupId = groupId;
    }
}
