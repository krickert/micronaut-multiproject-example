package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;

@Data
@Introspected
@Serdeable
public class SelectClusterResponse {

    private boolean success;
    private String message;

    public SelectClusterResponse() {
    }

    public SelectClusterResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

}
