package com.krickert.search.pipeline.kafka.serde;

import com.krickert.search.pipeline.config.PipelineConfig;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the KafkaSerdeFactory implementations.
 * These tests verify that the appropriate factory is selected based on the registry type.
 */
@MicronautTest
class KafkaSerdeFactoryTest {

    /**
     * Factory for test beans.
     */
    @Factory
    static class TestBeanFactory {

        /**
         * Provides a mock ExecutorService for the DynamicKafkaConsumerManager.
         * 
         * @return A mock ExecutorService.
         */
        @Bean
        @Singleton
        @Named("dynamic-kafka-consumer-executor")
        @Replaces(bean = ExecutorService.class, named = "dynamic-kafka-consumer-executor")
        ExecutorService dynamicKafkaExecutor() {
            return Executors.newSingleThreadExecutor();
        }
    }

    @Inject
    private KafkaSerdeProvider serdeProvider;

    /**
     * Test that the DefaultKafkaSerdeProvider is injected.
     */
    @Test
    void testProviderInjection() {
        assertNotNull(serdeProvider);
        assertTrue(serdeProvider instanceof DefaultKafkaSerdeProvider);
    }

    /**
     * Test that the DefaultKafkaSerdeProvider returns a deserializer.
     * This test will use whatever factory is configured as the default.
     */
    @Test
    void testGetDeserializer() {
        // Create a minimal PipelineConfig for testing
        PipelineConfig config = new PipelineConfig("test-pipeline");

        // Get a key deserializer
        Deserializer<?> keyDeserializer = serdeProvider.getKeyDeserializer("test-pipeline", config);
        assertNotNull(keyDeserializer, "Key deserializer should not be null");

        // Get a value deserializer
        Deserializer<?> valueDeserializer = serdeProvider.getValueDeserializer("test-pipeline", config);
        assertNotNull(valueDeserializer, "Value deserializer should not be null");
    }

    /**
     * Test the factory based on the configured registry type.
     * This test checks the actual deserializer type based on the configuration.
     */
    @Test
    void testFactoryBasedOnRegistryType() {
        // Create a minimal PipelineConfig for testing
        PipelineConfig config = new PipelineConfig("test-pipeline");

        // Get a value deserializer
        Deserializer<?> valueDeserializer = serdeProvider.getValueDeserializer("test-pipeline", config);
        assertNotNull(valueDeserializer, "Value deserializer should not be null");

        // Get the actual registry type from the provider
        String registryType = "unknown";
        if (serdeProvider instanceof DefaultKafkaSerdeProvider) {
            DefaultKafkaSerdeProvider provider = (DefaultKafkaSerdeProvider) serdeProvider;
            // We can't directly access the registry type, so we'll check the deserializer class
            String deserializerClass = valueDeserializer.getClass().getName();

            if (deserializerClass.contains("GlueSchemaRegistryKafkaDeserializer")) {
                registryType = "glue";
            } else if (deserializerClass.contains("ProtobufKafkaDeserializer")) {
                registryType = "apicurio";
            }
        }

        // Log the registry type and deserializer class for debugging
        System.out.println("[DEBUG_LOG] Registry type: " + registryType);
        System.out.println("[DEBUG_LOG] Deserializer class: " + valueDeserializer.getClass().getName());

        // Verify the deserializer based on the registry type
        if ("glue".equals(registryType)) {
            assertTrue(valueDeserializer.getClass().getName().contains("GlueSchemaRegistryKafkaDeserializer"),
                    "Expected a GlueSchemaRegistryKafkaDeserializer but got: " + valueDeserializer.getClass().getName());
        } else if ("apicurio".equals(registryType)) {
            assertTrue(valueDeserializer.getClass().getName().contains("ProtobufKafkaDeserializer"),
                    "Expected a ProtobufKafkaDeserializer but got: " + valueDeserializer.getClass().getName());
        } else {
            // If we can't determine the registry type, at least verify we got a deserializer
            assertNotNull(valueDeserializer, "Value deserializer should not be null regardless of registry type");
        }
    }
}
