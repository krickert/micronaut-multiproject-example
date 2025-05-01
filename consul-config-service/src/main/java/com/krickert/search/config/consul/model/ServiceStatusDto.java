package com.krickert.search.config.consul.model;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * DTO representing the status and details of a discovered service.
 * Using a record for concise immutable data representation.
 */
@Serdeable // Make it serializable for API responses
public record ServiceStatusDto(
    String name,
    boolean running,
    String address, // Can be null if service instance not found/healthy
    Integer port,   // Can be null
    List<String> tags // Can be null or empty
) {
    // Records automatically generate constructor, getters, equals, hashCode, toString
}