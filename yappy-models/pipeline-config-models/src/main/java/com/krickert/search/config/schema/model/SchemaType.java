package com.krickert.search.config.schema.model;

// This is a simple enum, Jackson will handle it by default (serializing as name).
// No specific Jackson annotations needed unless you want custom representation.
public enum SchemaType {
    JSON_SCHEMA,
    AVRO,
    PROTOBUF,
    OTHER
}