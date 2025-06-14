package com.krickert.search.commons.jackson;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

/**
 * Factory for creating configured ObjectMapper instances that ensure consistent
 * serialization across the entire YAPPY system. This configuration is critical
 * for schema validation which requires a hash for versioning.
 * 
 * The ordering and configuration options here were established to prevent
 * serialization inconsistencies that caused bugs in the past.
 */
@Factory
public class YappyObjectMapperFactory {
    
    /**
     * Creates a singleton ObjectMapper with consistent configuration for the entire YAPPY system.
     * This ObjectMapper ensures:
     * - Alphabetical ordering of properties for consistent hashing
     * - Alphabetical ordering of map entries
     * - Pretty printing for readability
     * - Proper Java Time module support
     * - No writing of dates as timestamps
     * 
     * @return configured ObjectMapper instance
     */
    @Singleton
    @Replaces(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                // Enable alphabetical ordering of properties for consistent serialization
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                // Enable ordering of map entries by keys
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                // Enable pretty printing for better readability in Consul
                .enable(SerializationFeature.INDENT_OUTPUT)
                // Disable writing dates as timestamps
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Add Java Time module for proper date/time handling
                .addModule(new JavaTimeModule())
                .build();
    }
}