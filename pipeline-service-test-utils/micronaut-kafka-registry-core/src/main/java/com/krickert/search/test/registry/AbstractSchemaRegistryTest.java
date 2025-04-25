package com.krickert.search.test.registry;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Abstract base class for schema registry tests.
 * This class provides common functionality for testing schema registry implementations.
 * Concrete test classes should extend this class and implement the necessary test methods.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(environments = "test", transactional = false)
public abstract class AbstractSchemaRegistryTest implements TestPropertyProvider {
    protected static final Logger log = LoggerFactory.getLogger(AbstractSchemaRegistryTest.class);

    @Inject
    protected SchemaRegistry schemaRegistry;

    @BeforeEach
    public void setUp() {
        // Ensure the schema registry is started
        schemaRegistry.start();
        log.info("Schema registry endpoint: {}", schemaRegistry.getEndpoint());
        log.info("Schema registry name: {}", schemaRegistry.getRegistryName());
    }

    @Override
    public Map<String, String> getProperties() {
        // Create a local SchemaRegistry if injection hasn't happened yet
        SchemaRegistry registry = schemaRegistry;
        if (registry == null) {
            // Create a temporary ApplicationContext to get the SchemaRegistry
            io.micronaut.context.ApplicationContext context = io.micronaut.context.ApplicationContext.builder().build();
            try (context) {
                context.start();
                com.krickert.search.test.kafka.registry.SchemaRegistryFactory factory = context.getBean(com.krickert.search.test.kafka.registry.SchemaRegistryFactory.class);
                registry = factory.schemaRegistry("moto");
            }
        }

        // Ensure schema registry is started
        registry.start();

        // Use the properties from the schema registry
        return registry.getProperties();
    }
}
