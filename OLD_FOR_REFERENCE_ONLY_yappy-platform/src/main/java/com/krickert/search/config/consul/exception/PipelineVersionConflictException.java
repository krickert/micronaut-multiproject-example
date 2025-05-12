package com.krickert.search.config.consul.exception;

import java.time.LocalDateTime;

/**
 * Exception thrown when there is a version conflict during pipeline operations.
 * This exceptions indicates that the version of the pipeline in the system has
 * changed since it was originally loaded or accessed, which creates a conflict.
 */
public class PipelineVersionConflictException extends RuntimeException {
    /**
     * Represents the name of the pipeline involved in the version conflict.
     * This field stores the name of the pipeline where the version mismatch occurred,
     * providing context for the conflict.
     */
    private final String pipelineName;
    /**
     * Represents the version of a pipeline that was expected during an operation.
     * This value is compared against the actual version in the system to determine
     * if a conflict has occurred. A mismatch between the expected and actual
     * versions typically indicates that the pipeline was updated by another process
     * or user, resulting in a potential version conflict.
     */
    private final long expectedVersion;
    /**
     * Represents the actual version of a pipeline in the system.
     * This value indicates the current state/version of the pipeline
     * and is compared against the expected version to determine if
     * a conflict exists.
     */
    private final long actualVersion;
    /**
     * The timestamp indicating when the pipeline was last updated.
     * It provides a reference to the most recent modification or update
     * made to the pipeline.
     *<br/>
     * This field is immutable and primarily used in exceptions handling
     * to specify the last update time of the pipeline during a version conflict.
     */
    private final LocalDateTime lastUpdated;
    /**
     * A JSON representation of the changes between the expected and actual versions of the pipeline.
     * This field holds the serialized JSON string that describes the differences.
     * It is primarily used to provide detailed context in version conflict scenarios.
     */
    private final String deltaJson;

    /**
     * Creates a new PipelineVersionConflictException with the specified details.
     *
     * @param pipelineName the name of the pipeline
     * @param expectedVersion the version that was expected
     * @param actualVersion the actual version in the system
     * @param lastUpdated when the pipeline was last updated
     */
    public PipelineVersionConflictException(String pipelineName, long expectedVersion, long actualVersion, LocalDateTime lastUpdated) {
        super(String.format("Pipeline '%s' has been updated since it was loaded. Expected version: %d, Actual version: %d, Last updated: %s",
                pipelineName, expectedVersion, actualVersion, lastUpdated));
        this.pipelineName = pipelineName;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.lastUpdated = lastUpdated;
        this.deltaJson = null;
    }

    /**
     * Creates a new PipelineVersionConflictException with the specified details, including delta information.
     *
     * @param pipelineName the name of the pipeline
     * @param expectedVersion the version that was expected
     * @param actualVersion the actual version in the system
     * @param lastUpdated when the pipeline was last updated
     * @param deltaJson JSON representation of the changes between the expected and actual versions
     */
    public PipelineVersionConflictException(String pipelineName, long expectedVersion, long actualVersion, LocalDateTime lastUpdated, String deltaJson) {
        super(String.format("Pipeline '%s' has been updated since it was loaded. Expected version: %d, Actual version: %d, Last updated: %s",
                pipelineName, expectedVersion, actualVersion, lastUpdated));
        this.pipelineName = pipelineName;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.lastUpdated = lastUpdated;
        this.deltaJson = deltaJson;
    }

    /**
     * Gets the name of the pipeline.
     *
     * @return the pipeline name
     */
    public String getPipelineName() {
        return pipelineName;
    }

    /**
     * Gets the version that was expected.
     *
     * @return the expected version
     */
    public long getExpectedVersion() {
        return expectedVersion;
    }

    /**
     * Gets the actual version in the system.
     *
     * @return the actual version
     */
    public long getActualVersion() {
        return actualVersion;
    }

    /**
     * Gets when the pipeline was last updated.
     *
     * @return the last updated timestamp
     */
    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    /**
     * Gets the JSON representation of the changes between the expected and actual versions.
     *
     * @return the delta JSON, or null if not available
     */
    public String getDeltaJson() {
        return deltaJson;
    }
}