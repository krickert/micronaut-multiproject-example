package com.krickert.search.test;

import com.krickert.search.test.apicurio.ApicurioSchemaRegistry;
import com.krickert.search.test.kafka.registry.SchemaRegistryFactory;
import com.krickert.search.test.moto.MotoSchemaRegistry;
import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to verify that the correct schema registry is selected based on the system property.
 */
@MicronautTest(environments = "test", transactional = false)
public class SchemaRegistrySelectionTest {
    private static final Logger log = LoggerFactory.getLogger(SchemaRegistrySelectionTest.class);
    private static final String SCHEMA_REGISTRY_TYPE_PROP = "schema.registry.type";

    private Map<String, String> originalProps;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        log.info("Running test: {}", testInfo.getDisplayName());
        // Save original system properties related to schema registry
        originalProps = new HashMap<>();
        String originalValue = System.getProperty(SCHEMA_REGISTRY_TYPE_PROP);
        if (originalValue != null) {
            originalProps.put(SCHEMA_REGISTRY_TYPE_PROP, originalValue);
        }
    }

    @AfterEach
    void tearDown() {
        // Restore original system properties
        if (originalProps.containsKey(SCHEMA_REGISTRY_TYPE_PROP)) {
            System.setProperty(SCHEMA_REGISTRY_TYPE_PROP, originalProps.get(SCHEMA_REGISTRY_TYPE_PROP));
        } else {
            System.clearProperty(SCHEMA_REGISTRY_TYPE_PROP);
        }
    }

    @Test
    void testDefaultRegistryIsApicurio() {
        // Clear the system property to use default
        System.clearProperty(SCHEMA_REGISTRY_TYPE_PROP);

        // Create a context and get the factory
        ApplicationContext context = ApplicationContext.builder().build();
        context.start();
        SchemaRegistryFactory factory = context.getBean(SchemaRegistryFactory.class);

        // Get the registry
        SchemaRegistry registry = factory.schemaRegistry("default");

        // Verify it's an ApicurioSchemaRegistry
        assertInstanceOf(ApicurioSchemaRegistry.class, registry, "Default registry should be ApicurioSchemaRegistry but was " + registry.getClass().getSimpleName());

        context.close();
    }

    @Test
    void testMotoRegistrySelectedWithSystemProperty() {
        // Set the system property to moto
        System.setProperty(SCHEMA_REGISTRY_TYPE_PROP, "moto");

        // Create a context and get the factory
        ApplicationContext context = ApplicationContext.builder().build();
        context.start();
        SchemaRegistryFactory factory = context.getBean(SchemaRegistryFactory.class);

        // Get the registry
        SchemaRegistry registry = factory.schemaRegistry("default");

        // Verify it's a MotoSchemaRegistry
        assertInstanceOf(MotoSchemaRegistry.class, registry, "Registry should be MotoSchemaRegistry but was " + registry.getClass().getSimpleName());

        context.close();
    }

    @Test
    void testApicurioRegistrySelectedWithSystemProperty() {
        // Set the system property to apicurio
        System.setProperty(SCHEMA_REGISTRY_TYPE_PROP, "apicurio");

        // Create a context and get the factory
        ApplicationContext context = ApplicationContext.builder().build();
        context.start();
        SchemaRegistryFactory factory = context.getBean(SchemaRegistryFactory.class);

        // Get the registry
        SchemaRegistry registry = factory.schemaRegistry("default");

        // Verify it's an ApicurioSchemaRegistry
        assertInstanceOf(ApicurioSchemaRegistry.class, registry, "Registry should be ApicurioSchemaRegistry but was " + registry.getClass().getSimpleName());

        context.close();
    }
}
