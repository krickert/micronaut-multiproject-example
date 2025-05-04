package com.krickert.search.test.platform.kafka;

import java.util.List;

/**
 * Common interface for Kafka test implementations (Kafka + Schema Registry).
 * Defines methods for managing containers and topics.
 * This interface DOES NOT provide properties directly.
 */
public interface KafkaTest {

    String getRegistryType();

    String getRegistryEndpoint();

    /** Starts Kafka and the specific registry container. Idempotent. */
    void startContainers();

    boolean areContainersRunning();

    /** Resets registry state if applicable. */
    void resetContainers();

    /** Creates default Kafka topics. */
    void createTopics();

    /** Deletes default Kafka topics. */
    void deleteTopics();

    /** Creates the specified Kafka topics. */
    void createTopics(List<String> topicsToCreate);

    /** Deletes the specified Kafka topics. */
    void deleteTopics(List<String> topicsToDelete);
}