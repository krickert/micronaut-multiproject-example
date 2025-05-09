package com.krickert.search.config.pipeline.model; // Adjusted package

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
// No Micronaut imports

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineStepConfig {

    @JsonProperty("pipelineStepId")
    private String pipelineStepId;

    @JsonProperty("pipelineImplementationId")
    private String pipelineImplementationId;

    @JsonProperty("customConfig")
    private JsonConfigOptions customConfig;

    @JsonProperty("kafkaListenTopics")
    private List<String> kafkaListenTopics;

    @JsonProperty("kafkaPublishTopics")
    private List<KafkaPublishTopic> kafkaPublishTopics;

    @JsonProperty("grpcForwardTo")
    private List<String> grpcForwardTo;
}