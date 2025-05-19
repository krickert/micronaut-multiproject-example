package com.krickert.search.config.consul;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Singleton
public class InMemoryCachedConfigHolder implements CachedConfigHolder {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryCachedConfigHolder.class);
    private final AtomicReference<CachedState> currentCachedState = new AtomicReference<>(null);

    public InMemoryCachedConfigHolder() {
        LOG.info("InMemoryCachedConfigHolder initialized. Cache is currently empty.");
    }

    /**
     * Package-private method specifically for testing to get the current atomic snapshot.
     * This helps in verifying the internal consistency of a state at a point in time.
     *
     * @return The current CachedState snapshot, or null if none is set.
     */
    CachedState getCachedStateSnapshotForTest() {
        return currentCachedState.get();
    }

    @Override
    public Optional<PipelineClusterConfig> getCurrentConfig() {
        CachedState state = currentCachedState.get();
        return Optional.ofNullable(state).map(CachedState::clusterConfig);
    }

    @Override
    public Optional<String> getSchemaContent(SchemaReference schemaRef) {
        if (schemaRef == null) {
            return Optional.empty();
        }
        CachedState state = currentCachedState.get();
        if (state != null) {
            return Optional.ofNullable(state.schemaCache().get(schemaRef));
        }
        return Optional.empty();
    }

    @Override
    public void updateConfiguration(PipelineClusterConfig newConfig, Map<SchemaReference, String> newSchemaCache) {
        if (newConfig == null) {
            LOG.warn("Attempted to update configuration with null PipelineClusterConfig. Clearing configuration instead.");
            clearConfiguration();
            return;
        }
        try {
            CachedState newState = new CachedState(newConfig, newSchemaCache == null ? Collections.emptyMap() : newSchemaCache);
            currentCachedState.set(newState);
            LOG.info("Cached configuration updated for cluster: {}. Schema cache contains {} entries.",
                    newConfig.clusterName(), newState.schemaCache().size());
        } catch (IllegalArgumentException | NullPointerException e) { // Map.copyOf throws NPE for nulls in map
            LOG.error("Failed to create new CachedState during update: {}. Configuration not updated.", e.getMessage(), e);
        }
    }

    @Override
    public void clearConfiguration() {
        CachedState oldState = currentCachedState.getAndSet(null);
        if (oldState != null && oldState.clusterConfig() != null) {
            LOG.info("Cached configuration cleared for cluster: {}", oldState.clusterConfig().clusterName());
        } else {
            LOG.debug("Attempted to clear configuration, but cache was already empty or in an uninitialized state.");
        }
    }

    // Made record package-private so test class in same package can see its type
    record CachedState(
            PipelineClusterConfig clusterConfig,
            Map<SchemaReference, String> schemaCache
    ) {
        CachedState {
            if (clusterConfig == null) {
                throw new IllegalArgumentException("PipelineClusterConfig cannot be null in CachedState.");
            }
            schemaCache = (schemaCache == null) ? Collections.emptyMap() : Map.copyOf(schemaCache);
        }
    }
}