package com.krickert.search.pipeline.kafka;

import io.micronaut.serde.annotation.Serdeable; // For JSON/Kafka serialization
import java.time.Instant;

/**
 * Represents a configuration update event message sent via Kafka.
 */
@Serdeable // Make it serializable/deserializable by Micronaut Serde
public record ConfigUpdateEvent(
    String sendingMachine, // Identifier of the machine/service that initiated the change
    Instant timestamp,     // When the change event was generated
    String changeDetails   // Description of what changed (e.g., updated pipeline X, removed pipeline Y)
) {
    // No explicit constructor or methods needed for a record
}