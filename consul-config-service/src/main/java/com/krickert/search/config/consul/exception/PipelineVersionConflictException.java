package com.krickert.search.config.consul.exception;

import java.time.LocalDateTime;

/**
 * Exception thrown when there is a version conflict when updating a pipeline.
 * This happens when a pipeline has been updated by another process since it was loaded.
 */
public class PipelineVersionConflictException extends RuntimeException {
    private final String pipelineName;
    private final long expectedVersion;
    private final long actualVersion;
    private final LocalDateTime lastUpdated;
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