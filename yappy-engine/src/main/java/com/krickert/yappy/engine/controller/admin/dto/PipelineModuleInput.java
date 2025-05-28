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
public class PipelineModuleInput {

    @NotBlank(message = "Implementation ID must be provided.")
    @JsonProperty("implementationId")
    private String implementationId;

    @NotBlank(message = "Implementation name must be provided.")
    @JsonProperty("implementationName")
    private String implementationName;

    public PipelineModuleInput() {
    }

    @JsonCreator
    public PipelineModuleInput(
            @JsonProperty("implementationId") String implementationId, 
            @JsonProperty("implementationName") String implementationName) {
        this.implementationId = implementationId;
        this.implementationName = implementationName;
    }

}
