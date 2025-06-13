package com.krickert.search.orchestrator.kafka.admin;

/**
 * Defines the cleanup policy for a Kafka topic.
 * A topic can have one or more policies (e.g., DELETE, COMPACT).
 */
public enum CleanupPolicy {
    DELETE("delete"),
    COMPACT("compact");

    private final String policyName;

    CleanupPolicy(String policyName) {
        this.policyName = policyName;
    }

    public String getPolicyName() {
        return policyName;
    }
}