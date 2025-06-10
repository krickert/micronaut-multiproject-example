package com.krickert.search.engine.kafka;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;

/**
 * Configuration properties for Kafka consumer management with slot-based partition assignment.
 */
@ConfigurationProperties("app.kafka.consumer")
@Requires(property = "app.kafka.slot-management.enabled", value = "true")
public class KafkaConsumerConfiguration {
    
    /**
     * Kafka bootstrap servers.
     */
    @NotBlank
    private String bootstrapServers = "localhost:9092";
    
    /**
     * Maximum number of records to poll at once.
     */
    @Min(1)
    private int maxPollRecords = 500;
    
    /**
     * Maximum time to block waiting for records.
     */
    @NotNull
    private Duration maxPollInterval = Duration.ofMinutes(5);
    
    /**
     * Session timeout for consumer group coordination.
     */
    @NotNull
    private Duration sessionTimeout = Duration.ofSeconds(30);
    
    /**
     * Heartbeat interval for consumer group.
     */
    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(3);
    
    /**
     * Request timeout for Kafka operations.
     */
    @NotNull
    private Duration requestTimeout = Duration.ofSeconds(30);
    
    /**
     * Minimum bytes to fetch in a request.
     */
    @Min(1)
    private int fetchMinBytes = 1;
    
    /**
     * Maximum wait time for fetch requests.
     */
    @NotNull
    private Duration fetchMaxWait = Duration.ofMillis(500);
    
    /**
     * Whether to auto-commit offsets.
     */
    private boolean enableAutoCommit = false;
    
    /**
     * Auto-commit interval if enabled.
     */
    private Duration autoCommitInterval = Duration.ofSeconds(5);
    
    /**
     * Isolation level for reading transactional messages.
     */
    private String isolationLevel = "read_uncommitted";
    
    /**
     * Additional Kafka consumer properties.
     */
    private Map<String, String> additionalProperties;
    
    // Error handling configuration
    
    /**
     * Maximum number of retries for recoverable errors.
     */
    @Min(0)
    private int maxRetries = 3;
    
    /**
     * Backoff duration between retries.
     */
    @NotNull
    private Duration retryBackoff = Duration.ofSeconds(1);
    
    /**
     * Whether to pause consumption on error.
     */
    private boolean pauseOnError = true;
    
    /**
     * Duration to pause consumption on error.
     */
    @NotNull
    private Duration errorPauseDuration = Duration.ofSeconds(30);
    
    // Getters and setters
    
    public String getBootstrapServers() {
        return bootstrapServers;
    }
    
    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }
    
    public int getMaxPollRecords() {
        return maxPollRecords;
    }
    
    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }
    
    public Duration getMaxPollInterval() {
        return maxPollInterval;
    }
    
    public void setMaxPollInterval(Duration maxPollInterval) {
        this.maxPollInterval = maxPollInterval;
    }
    
    public Duration getSessionTimeout() {
        return sessionTimeout;
    }
    
    public void setSessionTimeout(Duration sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }
    
    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }
    
    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }
    
    public Duration getRequestTimeout() {
        return requestTimeout;
    }
    
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
    
    public int getFetchMinBytes() {
        return fetchMinBytes;
    }
    
    public void setFetchMinBytes(int fetchMinBytes) {
        this.fetchMinBytes = fetchMinBytes;
    }
    
    public Duration getFetchMaxWait() {
        return fetchMaxWait;
    }
    
    public void setFetchMaxWait(Duration fetchMaxWait) {
        this.fetchMaxWait = fetchMaxWait;
    }
    
    public boolean isEnableAutoCommit() {
        return enableAutoCommit;
    }
    
    public void setEnableAutoCommit(boolean enableAutoCommit) {
        this.enableAutoCommit = enableAutoCommit;
    }
    
    public Duration getAutoCommitInterval() {
        return autoCommitInterval;
    }
    
    public void setAutoCommitInterval(Duration autoCommitInterval) {
        this.autoCommitInterval = autoCommitInterval;
    }
    
    public String getIsolationLevel() {
        return isolationLevel;
    }
    
    public void setIsolationLevel(String isolationLevel) {
        this.isolationLevel = isolationLevel;
    }
    
    public Map<String, String> getAdditionalProperties() {
        return additionalProperties;
    }
    
    public void setAdditionalProperties(Map<String, String> additionalProperties) {
        this.additionalProperties = additionalProperties;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public Duration getRetryBackoff() {
        return retryBackoff;
    }
    
    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }
    
    public boolean isPauseOnError() {
        return pauseOnError;
    }
    
    public void setPauseOnError(boolean pauseOnError) {
        this.pauseOnError = pauseOnError;
    }
    
    public Duration getErrorPauseDuration() {
        return errorPauseDuration;
    }
    
    public void setErrorPauseDuration(Duration errorPauseDuration) {
        this.errorPauseDuration = errorPauseDuration;
    }
}