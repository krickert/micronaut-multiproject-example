// File: micronaut-multiproject-example-new/consul-config-service/src/main/java/com/krickert/search/config/consul/exception/ErrorBodyUtil.java
package com.krickert.search.config.consul.exception;

import io.micronaut.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ErrorBodyUtil {

    public static Map<String, Object> createErrorBody(HttpStatus status, String message, String path) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", status.getCode());
        errorResponse.put("error", status.getReason());
        errorResponse.put("message", message);
        errorResponse.put("path", path);
        return errorResponse;
    }

     public static Map<String, Object> createConflictErrorBody(PipelineVersionConflictException e, String path) {
         Map<String, Object> errorResponse = createErrorBody(HttpStatus.CONFLICT, e.getMessage(), path);
         errorResponse.put("pipelineName", e.getPipelineName());
         errorResponse.put("expectedVersion", e.getExpectedVersion());
         errorResponse.put("actualVersion", e.getActualVersion());
         errorResponse.put("lastUpdated", e.getLastUpdated().toString());
         if (e.getDeltaJson() != null) {
              errorResponse.put("delta", e.getDeltaJson());
         }
         return errorResponse;
     }
}