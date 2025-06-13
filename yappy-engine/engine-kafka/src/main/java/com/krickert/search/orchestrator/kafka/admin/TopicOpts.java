package com.krickert.search.orchestrator.kafka.admin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// Using a record for conciseness, requires Java 16+
// If using older Java, a standard class with getters/constructor/equals/hashCode would be used.
/**
 * Options for creating or recreating a Kafka topic.
 */
public record TopicOpts(
    int partitions,
    short replicationFactor,
    List<CleanupPolicy> cleanupPolicies,
    Optional<Long> retentionMs,
    Optional<Long> retentionBytes,
    Optional<String> compressionType, // e.g., "producer", "gzip", "snappy", "lz4", "zstd"
    Optional<Integer> minInSyncReplicas,
    Map<String, String> additionalConfigs // For any other specific Kafka topic configurations
) {
    // Compact constructor for validation or defaults if needed
    public TopicOpts {
        if (partitions <= 0) {
            throw new IllegalArgumentException("Number of partitions must be positive.");
        }
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("Replication factor must be positive.");
        }
        if (cleanupPolicies == null || cleanupPolicies.isEmpty()) {
            throw new IllegalArgumentException("At least one cleanup policy must be specified.");
        }
    }

    // Convenience constructor for common cases
    public TopicOpts(int partitions, short replicationFactor, List<CleanupPolicy> cleanupPolicies) {
        this(partitions, replicationFactor, cleanupPolicies, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }
}