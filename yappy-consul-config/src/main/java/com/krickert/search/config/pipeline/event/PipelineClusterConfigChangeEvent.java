    // Suggested location: yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/pipeline/event/PipelineClusterConfigChangeEvent.java
    package com.krickert.search.config.pipeline.event;

    import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
    import io.micronaut.core.annotation.NonNull;

    public record PipelineClusterConfigChangeEvent(
            @NonNull String clusterName,
            @NonNull PipelineClusterConfig newConfig, // Could be null if it's a deletion event
            boolean isDeletion // Flag to indicate if the config was removed
    ) {
        public PipelineClusterConfigChangeEvent(@NonNull String clusterName, @NonNull PipelineClusterConfig newConfig) {
            this(clusterName, newConfig, false);
        }

        public static PipelineClusterConfigChangeEvent deletion(@NonNull String clusterName) {
            return new PipelineClusterConfigChangeEvent(clusterName, null, true);
        }
    }
    