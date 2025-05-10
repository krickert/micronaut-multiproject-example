package com.krickert.search.config.consul.service; // Or your preferred package

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@Factory
public class ConsulCacheConfigFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulCacheConfigFactory.class);

    // --- Default values mirrored from CacheConfig for clarity ---
    private static final long DEFAULT_WATCH_SECONDS = 10L;
    private static final long DEFAULT_BACKOFF_DELAY_SECONDS = 10L;
    private static final long DEFAULT_MIN_DELAY_BETWEEN_REQUESTS_MILLIS = 0L;
    private static final long DEFAULT_MIN_DELAY_ON_EMPTY_RESULT_MILLIS = 0L;
    private static final boolean DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_ENABLED = true;
    private static final long DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_MARGIN_SECONDS = 2L;
    private static final String DEFAULT_REFRESH_ERROR_LOG_LEVEL = "error";
    // --- End Default values ---

    @Bean
    @Singleton // CacheConfig is typically stateless and reusable
    public CacheConfig consulCacheConfig(
            @Value("${consul.client.cache.watch-seconds:" + DEFAULT_WATCH_SECONDS + "}") long watchSeconds,
            @Value("${consul.client.cache.backoff-delay-seconds:" + DEFAULT_BACKOFF_DELAY_SECONDS + "}") long backoffDelaySeconds,
            // Note: For simplicity, this uses the single backoff delay.
            // You could add properties for min/max backoff if needed.
            @Value("${consul.client.cache.min-delay-between-requests-millis:" + DEFAULT_MIN_DELAY_BETWEEN_REQUESTS_MILLIS + "}") long minDelayBetweenRequestsMillis,
            @Value("${consul.client.cache.min-delay-on-empty-result-millis:" + DEFAULT_MIN_DELAY_ON_EMPTY_RESULT_MILLIS + "}") long minDelayOnEmptyResultMillis,
            @Value("${consul.client.cache.timeout-auto-adjustment.enabled:" + DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_ENABLED + "}") boolean timeoutAdjustmentEnabled,
            @Value("${consul.client.cache.timeout-auto-adjustment.margin-seconds:" + DEFAULT_TIMEOUT_AUTO_ADJUSTMENT_MARGIN_SECONDS + "}") long timeoutAdjustmentMarginSeconds,
            @Value("${consul.client.cache.refresh-error-log-level:" + DEFAULT_REFRESH_ERROR_LOG_LEVEL + "}") String refreshErrorLogLevel
    ) {
        LOG.info("Building Consul CacheConfig:");
        LOG.info("  watchDuration: {} seconds", watchSeconds);
        LOG.info("  backOffDelay: {} seconds", backoffDelaySeconds);
        LOG.info("  minDelayBetweenRequests: {} ms", minDelayBetweenRequestsMillis);
        LOG.info("  minDelayOnEmptyResult: {} ms", minDelayOnEmptyResultMillis);
        LOG.info("  timeoutAutoAdjustmentEnabled: {}", timeoutAdjustmentEnabled);
        LOG.info("  timeoutAutoAdjustmentMargin: {} seconds", timeoutAdjustmentMarginSeconds);
        LOG.info("  refreshErrorLogLevel: {}", refreshErrorLogLevel);


        CacheConfig.Builder builder = CacheConfig.builder()
                .withWatchDuration(Duration.ofSeconds(watchSeconds))
                .withBackOffDelay(Duration.ofSeconds(backoffDelaySeconds)) // Sets both min and max backoff
                .withMinDelayBetweenRequests(Duration.ofMillis(minDelayBetweenRequestsMillis))
                .withMinDelayOnEmptyResult(Duration.ofMillis(minDelayOnEmptyResultMillis))
                .withTimeoutAutoAdjustmentEnabled(timeoutAdjustmentEnabled)
                .withTimeoutAutoAdjustmentMargin(Duration.ofSeconds(timeoutAdjustmentMarginSeconds));

        // Configure logging based on property
        if ("warn".equalsIgnoreCase(refreshErrorLogLevel)) {
            builder.withRefreshErrorLoggedAsWarning();
        } else if ("error".equalsIgnoreCase(refreshErrorLogLevel)) {
            builder.withRefreshErrorLoggedAsError(); // Default in CacheConfig
        } else {
            LOG.warn("Invalid value for consul.client.cache.refresh-error-log-level: '{}'. Defaulting to 'error'.", refreshErrorLogLevel);
            builder.withRefreshErrorLoggedAsError();
        }

        return builder.build();
    }
}