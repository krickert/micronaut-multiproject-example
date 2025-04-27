package com.krickert.search.test.registry;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.support.TestPropertyProvider;

/**
 * Interface for schema registry implementations to be used with Kafka tests.
 * This allows for different schema registry implementations to be injected into Kafka tests.
 */
public interface SchemaRegistry extends TestPropertyProvider {

    /**
     * Get the endpoint URL for the schema registry.
     * 
     * @return the endpoint URL as a string
     */
    @NonNull
    String getEndpoint();

    /**
     * Get the registry name to use.
     * 
     * @return the registry name as a string
     */
    @NonNull
    String getRegistryName();

    /**
     * Start the schema registry if it's not already running.
     * This method should be idempotent.
     */
    void start();

    /**
     * Check if the schema registry is running.
     * 
     * @return true if the registry is running, false otherwise
     */
    boolean isRunning();

    /**
     * Get the fully qualified class name of the serializer to use with this schema registry.
     * 
     * @return the serializer class name as a string
     */
    @NonNull
    String getSerializerClass();

    /**
     * Get the fully qualified class name of the deserializer to use with this schema registry.
     * 
     * @return the deserializer class name as a string
     */
    @NonNull
    String getDeserializerClass();

    /**
     * Reset the schema registry state between tests.
     * This method should clean up any resources that might cause interference between tests.
     */
    void reset();
}
