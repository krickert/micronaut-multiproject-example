package com.krickert.search.config.schema.registry.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum SchemaType {
    JSON_SCHEMA,
    AVRO,
    PROTOBUF,
    // Add other types as needed
    OTHER
}