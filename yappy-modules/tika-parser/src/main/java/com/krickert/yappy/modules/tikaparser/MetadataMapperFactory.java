package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory class for creating MetadataMapper instances from JSON configuration.
 * 
 * <p>This factory reads metadata mapping rules from a Protocol Buffers Struct
 * configuration and creates a MetadataMapper instance with the appropriate rules.
 * The configuration should contain a "mappers" field with mapping rules for each
 * metadata field to be transformed.</p>
 * 
 * <p>Example configuration structure:</p>
 * <pre>
 * {
 *   "mappers": {
 *     "dc:title": {
 *       "operation": "COPY",
 *       "destination": "title"
 *     },
 *     "author": {
 *       "operation": "REGEX",
 *       "destination": "formatted_author",
 *       "pattern": "^(.+), (.+)$",
 *       "replacement": "$2 $1"
 *     }
 *   }
 * }
 * </pre>
 */
public class MetadataMapperFactory {
    private static final Logger LOG = LoggerFactory.getLogger(MetadataMapperFactory.class);
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MetadataMapperFactory() {
        // Utility class
    }

    /**
     * Creates a MetadataMapper from a Struct configuration.
     *
     * @param config The configuration Struct
     * @return A MetadataMapper instance
     */
    public static MetadataMapper createFromConfig(Struct config) {
        if (config == null) {
            LOG.info("No metadata mapper configuration provided, using empty mapper");
            return new MetadataMapper();
        }

        MetadataMapper mapper = new MetadataMapper();
        
        // Check if the config has a "mappers" field
        if (!config.containsFields("mappers")) {
            LOG.info("No 'mappers' field in configuration, using empty mapper");
            return mapper;
        }
        
        Value mappersValue = config.getFieldsOrDefault("mappers", null);
        if (mappersValue == null || !mappersValue.hasStructValue()) {
            LOG.info("'mappers' field is not a Struct, using empty mapper");
            return mapper;
        }
        
        Struct mappersStruct = mappersValue.getStructValue();
        
        // Process each mapping rule
        for (Map.Entry<String, Value> entry : mappersStruct.getFieldsMap().entrySet()) {
            String sourceField = entry.getKey();
            Value ruleValue = entry.getValue();
            
            if (!ruleValue.hasStructValue()) {
                LOG.warn("Mapping rule for '{}' is not a Struct, skipping", sourceField);
                continue;
            }
            
            Struct ruleStruct = ruleValue.getStructValue();
            
            // Get the operation
            String operationStr = getStringValue(ruleStruct, "operation", "keep").toUpperCase();
            MetadataMapper.Operation operation;
            try {
                operation = MetadataMapper.Operation.valueOf(operationStr);
            } catch (IllegalArgumentException e) {
                LOG.warn("Invalid operation '{}' for field '{}', defaulting to KEEP", operationStr, sourceField);
                operation = MetadataMapper.Operation.KEEP;
            }
            
            // Get the destination field
            String destinationField = getStringValue(ruleStruct, "destination", sourceField);
            
            // Get regex pattern and replacement (only used for REGEX operation)
            String regexPattern = getStringValue(ruleStruct, "pattern", null);
            String replacement = getStringValue(ruleStruct, "replacement", "");
            
            try {
                mapper.addRule(sourceField, destinationField, operation, regexPattern, replacement);
                LOG.debug("Added mapping rule for field '{}': operation={}, destination={}", 
                        sourceField, operation, destinationField);
            } catch (Exception e) {
                LOG.error("Error adding mapping rule for field '{}': {}", sourceField, e.getMessage(), e);
            }
        }
        
        return mapper;
    }
    
    /**
     * Helper method to get a string value from a Struct.
     *
     * @param struct The Struct to get the value from
     * @param key The key to look up
     * @param defaultValue The default value to return if the key is not found or not a string
     * @return The string value
     */
    private static String getStringValue(Struct struct, String key, String defaultValue) {
        if (!struct.containsFields(key)) {
            return defaultValue;
        }
        
        Value value = struct.getFieldsOrDefault(key, null);
        if (value == null || !value.hasStringValue()) {
            return defaultValue;
        }
        
        return value.getStringValue();
    }
}