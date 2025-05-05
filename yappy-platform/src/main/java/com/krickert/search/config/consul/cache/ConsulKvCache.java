package com.krickert.search.config.consul.cache;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Cache for Consul KV values.
 * This class provides caching functionality to reduce the number of requests to Consul
 * and prevent rate limiting errors.
 */
@Singleton
public class ConsulKvCache {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvCache.class);
    
    private final CacheConfig cacheConfig;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Pattern to match pipeline config keys
    private static final Pattern PIPELINE_CONFIG_PATTERN = Pattern.compile("^.*pipeline\\.configs\\.([^.]+).*$");

    /**
     * Creates a new ConsulKvCache with the specified CacheConfig.
     *
     * @param cacheConfig the cache configuration
     */
    public ConsulKvCache(CacheConfig cacheConfig) {
        this.cacheConfig = cacheConfig;
        LOG.info("Initializing ConsulKvCache with config: watchDuration={}, minDelayBetweenRequests={}",
                cacheConfig.getWatchDuration(), cacheConfig.getMinDelayBetweenRequests());
        
        // Schedule cache cleanup task
        scheduler.scheduleAtFixedRate(this::cleanupExpiredEntries, 
                cacheConfig.getWatchDuration().toMillis(), 
                cacheConfig.getWatchDuration().toMillis(), 
                TimeUnit.MILLISECONDS);
    }

    /**
     * Gets a value from the cache.
     *
     * @param key the key to get
     * @return an Optional containing the value if found and not expired, or empty if not found or expired
     */
    public Optional<String> getValue(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            LOG.debug("Cache hit for key: {}", key);
            return Optional.of(entry.getValue());
        }
        LOG.debug("Cache miss for key: {}", key);
        return Optional.empty();
    }

    /**
     * Puts a value into the cache.
     *
     * @param key the key to put
     * @param value the value to put
     */
    public void putValue(String key, String value) {
        LOG.debug("Caching value for key: {}", key);
        cache.put(key, new CacheEntry(value, cacheConfig.getWatchDuration()));
    }

    /**
     * Invalidates a key in the cache.
     *
     * @param key the key to invalidate
     */
    public void invalidateKey(String key) {
        LOG.debug("Invalidating cache for key: {}", key);
        cache.remove(key);
    }

    /**
     * Invalidates all keys with a given prefix in the cache.
     *
     * @param prefix the prefix of the keys to invalidate
     */
    public void invalidateKeysWithPrefix(String prefix) {
        LOG.debug("Invalidating cache for keys with prefix: {}", prefix);
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Invalidates all keys related to a specific pipeline in the cache.
     *
     * @param pipelineName the name of the pipeline
     */
    public void invalidatePipelineKeys(String pipelineName) {
        LOG.debug("Invalidating cache for pipeline: {}", pipelineName);
        String pipelinePrefix = "pipeline.configs." + pipelineName;
        invalidateKeysWithPrefix(pipelinePrefix);
    }

    /**
     * Cleans up expired entries from the cache.
     */
    private void cleanupExpiredEntries() {
        LOG.debug("Cleaning up expired cache entries");
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * Shuts down the cache scheduler.
     */
    public void shutdown() {
        LOG.info("Shutting down ConsulKvCache");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    /**
     * A cache entry with a value and an expiration time.
     */
    private static class CacheEntry {
        private final String value;
        private final Instant expirationTime;

        /**
         * Creates a new CacheEntry with the specified value and TTL.
         *
         * @param value the value to cache
         * @param ttl the time-to-live for the entry
         */
        public CacheEntry(String value, Duration ttl) {
            this.value = value;
            this.expirationTime = Instant.now().plus(ttl);
        }

        /**
         * Gets the cached value.
         *
         * @return the cached value
         */
        public String getValue() {
            return value;
        }

        /**
         * Checks if the entry has expired.
         *
         * @return true if the entry has expired, false otherwise
         */
        public boolean isExpired() {
            return Instant.now().isAfter(expirationTime);
        }
    }
}