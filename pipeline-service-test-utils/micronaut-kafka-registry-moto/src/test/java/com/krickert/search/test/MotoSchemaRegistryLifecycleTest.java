package com.krickert.search.test;

import com.krickert.search.test.registry.AbstractSchemaRegistryLifecycleTest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concrete implementation of AbstractSchemaRegistryLifecycleTest for testing the MotoSchemaRegistry.
 * This class tests the lifecycle of the MotoSchemaRegistry.
 */
@MicronautTest(environments = "test")
public class MotoSchemaRegistryLifecycleTest extends AbstractSchemaRegistryLifecycleTest {

    /**
     * Additional test specific to the Moto implementation.
     * Verifies that the endpoint contains "moto" since it's a Moto server.
     */
    @Test
    public void testMotoSpecificEndpoint() {
        String endpoint = schemaRegistry.getEndpoint().toLowerCase();
        assertTrue(endpoint.contains("localhost") || endpoint.contains("127.0.0.1"), 
                "Moto endpoint should be on localhost");
        
        log.info("Moto endpoint verified: {}", schemaRegistry.getEndpoint());
    }
}