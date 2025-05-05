package com.krickert.search.config.consul.cache;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Configuration for the Consul KV cache.
 * This class defines the parameters used to configure the caching behavior.
 */
@ConfigurationProperties("consul.client.cache")
@Singleton
@Context
public class CacheConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * Default duration for watching for changes.
     */
    public static final Duration DEFAULT_WATCH_DURATION = Duration.ofSeconds(10);

    /**
     * Default delay between retries when an error occurs.
     */
    public static final Duration DEFAULT_BACKOFF_DELAY = Duration.ofSeconds(10);

    /**
     * Default minimum delay between requests to avoid rate limiting.
     */
    public static final Duration DEFAULT_MIN_DELAY_BETWEEN_REQUESTS = Duration.ZERO;

    /**
     * Default minimum delay when an empty result is returned.
     */
    public static final Duration DEFAULT_MIN_DELAY_ON_EMPTY_RESULT = Duration.ZERO;

    /**
     * Default setting for whether timeout auto-adjustment is enabled.
     */
    public static final boolean DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_ENABLED = true;

    /**
     * Default margin for timeout auto-adjustment.
     */
    public static final Duration DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_MARGIN = Duration.ofSeconds(2);

    /**
     * Default consumer for logging refresh errors.
     */
    public static final RefreshErrorLogConsumer DEFAULT_REFRESH_ERROR_LOG_CONSUMER = 
        throwable -> LOG.error("Error refreshing cache", throwable);

    private Duration watchDuration = DEFAULT_WATCH_DURATION;
    private Duration minBackOffDelay = DEFAULT_BACKOFF_DELAY;
    private Duration maxBackOffDelay = DEFAULT_BACKOFF_DELAY.multipliedBy(10);
    private Duration minDelayBetweenRequests = DEFAULT_MIN_DELAY_BETWEEN_REQUESTS;
    private Duration minDelayOnEmptyResult = DEFAULT_MIN_DELAY_ON_EMPTY_RESULT;
    private boolean timeoutAutoAdjustmentEnabled = DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_ENABLED;
    private Duration timeoutAutoAdjustmentMargin = DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_MARGIN;
    private RefreshErrorLogConsumer refreshErrorLogConsumer = DEFAULT_REFRESH_ERROR_LOG_CONSUMER;

    /**
     * Creates a new CacheConfig with default values.
     */
    public CacheConfig() {
        LOG.info("Initializing CacheConfig with default values");
    }

    /**
     * Creates a new CacheConfig with the specified values.
     *
     * @param watchDuration the duration for watching for changes
     * @param minBackOffDelay the minimum delay between retries when an error occurs
     * @param maxBackOffDelay the maximum delay between retries when an error occurs
     * @param minDelayBetweenRequests the minimum delay between requests to avoid rate limiting
     * @param minDelayOnEmptyResult the minimum delay when an empty result is returned
     * @param timeoutAutoAdjustmentEnabled whether timeout auto-adjustment is enabled
     * @param timeoutAutoAdjustmentMargin the margin for timeout auto-adjustment
     * @param refreshErrorLogConsumer the consumer for logging refresh errors
     */
    private CacheConfig(Duration watchDuration,
                        Duration minBackOffDelay,
                        Duration maxBackOffDelay,
                        Duration minDelayBetweenRequests,
                        Duration minDelayOnEmptyResult,
                        boolean timeoutAutoAdjustmentEnabled,
                        Duration timeoutAutoAdjustmentMargin,
                        RefreshErrorLogConsumer refreshErrorLogConsumer) {
        this.watchDuration = watchDuration;
        this.minBackOffDelay = minBackOffDelay;
        this.maxBackOffDelay = maxBackOffDelay;
        this.minDelayBetweenRequests = minDelayBetweenRequests;
        this.minDelayOnEmptyResult = minDelayOnEmptyResult;
        this.timeoutAutoAdjustmentEnabled = timeoutAutoAdjustmentEnabled;
        this.timeoutAutoAdjustmentMargin = timeoutAutoAdjustmentMargin;
        this.refreshErrorLogConsumer = refreshErrorLogConsumer;
    }

    // Getters and setters

    public Duration getWatchDuration() {
        return watchDuration;
    }

    public void setWatchDuration(Duration watchDuration) {
        this.watchDuration = watchDuration;
    }

    public Duration getMinBackOffDelay() {
        return minBackOffDelay;
    }

    public void setMinBackOffDelay(Duration minBackOffDelay) {
        this.minBackOffDelay = minBackOffDelay;
    }

    public Duration getMaxBackOffDelay() {
        return maxBackOffDelay;
    }

    public void setMaxBackOffDelay(Duration maxBackOffDelay) {
        this.maxBackOffDelay = maxBackOffDelay;
    }

    public Duration getMinDelayBetweenRequests() {
        return minDelayBetweenRequests;
    }

    public void setMinDelayBetweenRequests(Duration minDelayBetweenRequests) {
        this.minDelayBetweenRequests = minDelayBetweenRequests;
    }

    public Duration getMinDelayOnEmptyResult() {
        return minDelayOnEmptyResult;
    }

    public void setMinDelayOnEmptyResult(Duration minDelayOnEmptyResult) {
        this.minDelayOnEmptyResult = minDelayOnEmptyResult;
    }

    public boolean isTimeoutAutoAdjustmentEnabled() {
        return timeoutAutoAdjustmentEnabled;
    }

    public void setTimeoutAutoAdjustmentEnabled(boolean timeoutAutoAdjustmentEnabled) {
        this.timeoutAutoAdjustmentEnabled = timeoutAutoAdjustmentEnabled;
    }

    public Duration getTimeoutAutoAdjustmentMargin() {
        return timeoutAutoAdjustmentMargin;
    }

    public void setTimeoutAutoAdjustmentMargin(Duration timeoutAutoAdjustmentMargin) {
        this.timeoutAutoAdjustmentMargin = timeoutAutoAdjustmentMargin;
    }

    public RefreshErrorLogConsumer getRefreshErrorLogConsumer() {
        return refreshErrorLogConsumer;
    }

    public void setRefreshErrorLogConsumer(RefreshErrorLogConsumer refreshErrorLogConsumer) {
        this.refreshErrorLogConsumer = refreshErrorLogConsumer;
    }

    /**
     * Functional interface for logging refresh errors.
     */
    @FunctionalInterface
    public interface RefreshErrorLogConsumer extends Consumer<Throwable> {
    }

    /**
     * Builder for creating CacheConfig instances.
     */
    public static class Builder {
        private Duration watchDuration = DEFAULT_WATCH_DURATION;
        private Duration minBackOffDelay = DEFAULT_BACKOFF_DELAY;
        private Duration maxBackOffDelay = DEFAULT_BACKOFF_DELAY.multipliedBy(10);
        private Duration minDelayBetweenRequests = DEFAULT_MIN_DELAY_BETWEEN_REQUESTS;
        private Duration minDelayOnEmptyResult = DEFAULT_MIN_DELAY_ON_EMPTY_RESULT;
        private boolean timeoutAutoAdjustmentEnabled = DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_ENABLED;
        private Duration timeoutAutoAdjustmentMargin = DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_MARGIN;
        private RefreshErrorLogConsumer refreshErrorLogConsumer = DEFAULT_REFRESH_ERROR_LOG_CONSUMER;

        public Builder withWatchDuration(Duration watchDuration) {
            this.watchDuration = watchDuration;
            return this;
        }

        public Builder withMinBackOffDelay(Duration minBackOffDelay) {
            this.minBackOffDelay = minBackOffDelay;
            return this;
        }

        public Builder withMaxBackOffDelay(Duration maxBackOffDelay) {
            this.maxBackOffDelay = maxBackOffDelay;
            return this;
        }

        public Builder withMinDelayBetweenRequests(Duration minDelayBetweenRequests) {
            this.minDelayBetweenRequests = minDelayBetweenRequests;
            return this;
        }

        public Builder withMinDelayOnEmptyResult(Duration minDelayOnEmptyResult) {
            this.minDelayOnEmptyResult = minDelayOnEmptyResult;
            return this;
        }

        public Builder withTimeoutAutoAdjustmentEnabled(boolean timeoutAutoAdjustmentEnabled) {
            this.timeoutAutoAdjustmentEnabled = timeoutAutoAdjustmentEnabled;
            return this;
        }

        public Builder withTimeoutAutoAdjustmentMargin(Duration timeoutAutoAdjustmentMargin) {
            this.timeoutAutoAdjustmentMargin = timeoutAutoAdjustmentMargin;
            return this;
        }

        public Builder withRefreshErrorLogConsumer(RefreshErrorLogConsumer refreshErrorLogConsumer) {
            this.refreshErrorLogConsumer = refreshErrorLogConsumer;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(
                    watchDuration,
                    minBackOffDelay,
                    maxBackOffDelay,
                    minDelayBetweenRequests,
                    minDelayOnEmptyResult,
                    timeoutAutoAdjustmentEnabled,
                    timeoutAutoAdjustmentMargin,
                    refreshErrorLogConsumer
            );
        }
    }

    /**
     * Creates a new Builder for building CacheConfig instances.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }
}
