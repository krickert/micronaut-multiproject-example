package com.krickert.search.config.schema.registry.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Describes the compatibility of a schema version with its predecessor.
 */
@Serdeable
public enum SchemaCompatibility {
    /** No compatibility guarantee. */
    NONE,
    /** New schema can read data produced by the old schema. */
    BACKWARD,
    /** Old schema can read data produced by the new schema. */
    FORWARD,
    /** Both backward and forward compatible. */
    FULL,
    /** Backward compatible up to a specific prior version. */
    BACKWARD_TRANSITIVE,
    /** Forward compatible up to a specific prior version. */
    FORWARD_TRANSITIVE,
    /** Both backward and forward compatible up to specific prior versions. */
    FULL_TRANSITIVE
}