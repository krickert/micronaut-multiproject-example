package com.krickert.search.orchestrator.kafka;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic test to verify the Kafka service module loads properly.
 */
@MicronautTest
class KafkaServiceModuleTest {

    @Test
    void moduleLoadsSuccessfully() {
        // This test verifies that the Micronaut context can be created
        // and all beans are properly wired without compilation or runtime errors
        assertTrue(true, "Module loaded successfully");
    }
}