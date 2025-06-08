package com.krickert.yappy.modules.tikaparser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Utility class for mapping metadata fields from one format to another.
 * Supports operations like keep, delete, copy, and regex transform.
 */
public class MetadataMapper {
    private static final Logger LOG = LoggerFactory.getLogger(MetadataMapper.class);

    /**
     * Represents a mapping operation to perform on a metadata field.
     */
    public enum Operation {
        /** Keep the field as is without any transformation. */
        KEEP,
        /** Delete the field from the output metadata. */
        DELETE,
        /** Copy the field to a new field name in the output metadata. */
        COPY,
        /** Apply a regex transformation to the field value. */
        REGEX
    }

    /**
     * Represents a mapping rule for a metadata field.
     */
    public static class MappingRule {
        private final String sourceField;
        private final String destinationField;
        private final Operation operation;
        private final String regexPattern;
        private final String replacement;
        private Pattern compiledPattern;

        /**
         * Creates a new mapping rule.
         *
         * @param sourceField The source field name
         * @param destinationField The destination field name (can be null for DELETE operation)
         * @param operation The operation to perform
         * @param regexPattern The regex pattern to use (only for REGEX operation)
         * @param replacement The replacement string (only for REGEX operation)
         */
        public MappingRule(String sourceField, String destinationField, Operation operation, 
                          String regexPattern, String replacement) {
            this.sourceField = sourceField;
            this.destinationField = destinationField;
            this.operation = operation;
            this.regexPattern = regexPattern;
            this.replacement = replacement;
            
            // Compile the pattern if it's a REGEX operation
            if (operation == Operation.REGEX && regexPattern != null) {
                try {
                    this.compiledPattern = Pattern.compile(regexPattern);
                } catch (PatternSyntaxException e) {
                    LOG.error("Invalid regex pattern: {}", regexPattern, e);
                    throw e;
                }
            }
        }

        /**
         * Gets the source field name.
         *
         * @return the source field name
         */
        public String getSourceField() {
            return sourceField;
        }

        /**
         * Gets the destination field name.
         *
         * @return the destination field name, or null for DELETE operations
         */
        public String getDestinationField() {
            return destinationField;
        }

        /**
         * Gets the operation to perform.
         *
         * @return the operation
         */
        public Operation getOperation() {
            return operation;
        }

        /**
         * Gets the regex pattern for REGEX operations.
         *
         * @return the regex pattern, or null for non-REGEX operations
         */
        public String getRegexPattern() {
            return regexPattern;
        }

        /**
         * Gets the replacement string for REGEX operations.
         *
         * @return the replacement string, or null for non-REGEX operations
         */
        public String getReplacement() {
            return replacement;
        }

        /**
         * Gets the compiled regex pattern for REGEX operations.
         *
         * @return the compiled pattern, or null for non-REGEX operations
         */
        public Pattern getCompiledPattern() {
            return compiledPattern;
        }
    }

    private final Map<String, MappingRule> rules;

    /**
     * Creates a new MetadataMapper with the specified rules.
     *
     * @param rules The mapping rules to apply
     */
    public MetadataMapper(Map<String, MappingRule> rules) {
        this.rules = rules;
    }

    /**
     * Creates a new MetadataMapper with no rules.
     */
    public MetadataMapper() {
        this.rules = new HashMap<>();
    }

    /**
     * Adds a mapping rule.
     *
     * @param sourceField The source field name
     * @param destinationField The destination field name (can be null for DELETE operation)
     * @param operation The operation to perform
     * @param regexPattern The regex pattern to use (only for REGEX operation)
     * @param replacement The replacement string (only for REGEX operation)
     */
    public void addRule(String sourceField, String destinationField, Operation operation, 
                       String regexPattern, String replacement) {
        rules.put(sourceField, new MappingRule(sourceField, destinationField, operation, 
                                              regexPattern, replacement));
    }

    /**
     * Applies the mapping rules to the input metadata.
     *
     * @param inputMetadata The input metadata
     * @return The transformed metadata
     */
    public Map<String, String> applyRules(Map<String, String> inputMetadata) {
        Map<String, String> outputMetadata = new HashMap<>(inputMetadata);
        
        // Apply rules for fields that have explicit rules
        for (MappingRule rule : rules.values()) {
            String sourceField = rule.getSourceField();
            
            // Skip if the source field doesn't exist in the input
            if (!inputMetadata.containsKey(sourceField)) {
                continue;
            }
            
            String sourceValue = inputMetadata.get(sourceField);
            
            switch (rule.getOperation()) {
                case KEEP:
                    // Keep the field as is (no action needed)
                    break;
                    
                case DELETE:
                    // Remove the field
                    outputMetadata.remove(sourceField);
                    break;
                    
                case COPY:
                    // Copy the field to a new name
                    if (rule.getDestinationField() != null) {
                        outputMetadata.put(rule.getDestinationField(), sourceValue);
                    }
                    break;
                    
                case REGEX:
                    // Apply regex transformation
                    if (rule.getCompiledPattern() != null && rule.getDestinationField() != null) {
                        Matcher matcher = rule.getCompiledPattern().matcher(sourceValue);
                        String transformedValue = matcher.replaceAll(rule.getReplacement());
                        outputMetadata.put(rule.getDestinationField(), transformedValue);
                    }
                    break;
            }
        }
        
        return outputMetadata;
    }

    /**
     * Gets the mapping rules.
     *
     * @return The mapping rules
     */
    public Map<String, MappingRule> getRules() {
        return rules;
    }
}