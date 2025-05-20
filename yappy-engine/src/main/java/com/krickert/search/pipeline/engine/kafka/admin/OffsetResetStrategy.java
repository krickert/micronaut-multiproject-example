package com.krickert.search.pipeline.engine.kafka.admin;

/**
 * Strategy for resetting consumer group offsets.
 */
public enum OffsetResetStrategy {
    EARLIEST,
    LATEST,
    TO_SPECIFIC_OFFSETS,
    TO_TIMESTAMP
    // NOTE: Future consideration: SHIFT_BY_N_MESSAGES (more complex)
}