package com.krickert.search.config.schema.registry.model;

// Simple enum, Jackson default behavior is fine.
public enum SchemaCompatibility {
    NONE,
    BACKWARD,
    FORWARD,
    FULL,
    BACKWARD_TRANSITIVE,
    FORWARD_TRANSITIVE,
    FULL_TRANSITIVE
}