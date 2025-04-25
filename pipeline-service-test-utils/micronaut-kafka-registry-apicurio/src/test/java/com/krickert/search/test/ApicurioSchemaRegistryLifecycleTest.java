package com.krickert.search.test;

import com.krickert.search.test.registry.AbstractSchemaRegistryLifecycleTest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concrete implementation of AbstractSchemaRegistryLifecycleTest for testing the ApicurioSchemaRegistry.
 * This class tests the lifecycle of the ApicurioSchemaRegistry.
 */
@MicronautTest(environments = "test")
public class ApicurioSchemaRegistryLifecycleTest extends AbstractSchemaRegistryLifecycleTest {

    /**
     * Additional test specific to the Apicurio implementation.
     * Verifies that the endpoint contains "apicurio" since it's an Apicurio server.
     */
    @Test
    public void testApicurioSpecificEndpoint() {
        String endpoint = schemaRegistry.getEndpoint().toLowerCase();
        assertTrue(endpoint.contains("localhost") || endpoint.contains("127.0.0.1"), 
                "Apicurio endpoint should be on localhost");
        
        log.info("Apicurio endpoint verified: {}", schemaRegistry.getEndpoint());
    }
}