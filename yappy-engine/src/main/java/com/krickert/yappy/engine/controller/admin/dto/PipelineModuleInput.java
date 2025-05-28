package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Introspected
@Serdeable
public class PipelineModuleInput {

    @NotBlank(message = "Implementation ID must be provided.")
    private String implementationId;

    @NotBlank(message = "Implementation name must be provided.")
    private String implementationName;

    public PipelineModuleInput() {
    }

    public PipelineModuleInput(String implementationId, String implementationName) {
        this.implementationId = implementationId;
        this.implementationName = implementationName;
    }

}
