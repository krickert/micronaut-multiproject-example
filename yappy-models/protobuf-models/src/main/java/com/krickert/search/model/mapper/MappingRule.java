// src/main/java/com/krickert/search/model/mapper/MappingRule.java
package com.krickert.search.model.mapper;

import java.util.Objects;

/**
 * Represents a parsed mapping rule.
 */
public class MappingRule {

    private final Operation operation;
    private final String targetPath; // Full path for ASSIGN/APPEND/DELETE, map field path for MAP_PUT
    private final String sourcePath; // Null for DELETE
    private final String mapKey;     // Null if not MAP_PUT
    private final String originalRuleString; // Store original for errors
    // Private constructor, use static factory methods
    MappingRule(Operation operation, String targetPath, String sourcePath, String mapKey, String originalRuleString) {
        this.operation = Objects.requireNonNull(operation, "Operation cannot be null");
        this.targetPath = Objects.requireNonNull(targetPath, "Target path cannot be null");
        this.originalRuleString = Objects.requireNonNull(originalRuleString, "Original rule string cannot be null");
        this.sourcePath = sourcePath; // Can be null for DELETE
        this.mapKey = mapKey;         // Can be null
        // Validate consistency
        if (operation == Operation.DELETE && sourcePath != null) {
            throw new IllegalArgumentException("Source path must be null for DELETE operation");
        }
        if (operation != Operation.DELETE && sourcePath == null) {
            throw new IllegalArgumentException("Source path cannot be null for non-DELETE operations");
        }
        if (operation == Operation.MAP_PUT && mapKey == null) {
            throw new IllegalArgumentException("Map key cannot be null for MAP_PUT operation");
        }
        if (operation != Operation.MAP_PUT && mapKey != null) {
            throw new IllegalArgumentException("Map key must be null for non-MAP_PUT operations");
        }
    }

    public static MappingRule createAssignRule(String targetPath, String sourcePath, String originalRuleString) {
        return new MappingRule(Operation.ASSIGN, targetPath, sourcePath, null, originalRuleString);
    }

    public static MappingRule createAppendRule(String targetPath, String sourcePath, String originalRuleString) {
        return new MappingRule(Operation.APPEND, targetPath, sourcePath, null, originalRuleString);
    }

    public static MappingRule createMapPutRule(String targetMapPath, String mapKey, String sourcePath, String originalRuleString) {
        return new MappingRule(Operation.MAP_PUT, targetMapPath, sourcePath, mapKey, originalRuleString);
    }

    public static MappingRule createDeleteRule(String targetPath, String originalRuleString) {
        return new MappingRule(Operation.DELETE, targetPath, null, null, originalRuleString);
    }

    public Operation getOperation() {
        return operation;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getMapKey() {
        return mapKey;
    }

    /**
     * Returns the full target specification including map key if applicable.
     * Useful for error reporting or path resolution.
     */
    public String getFullTargetPathSpecification() {
        if (operation == Operation.MAP_PUT) {
            // Consistent with original parsing logic (quotes removed from key)
            return targetPath + "[\"" + mapKey + "\"]";
        }
        return targetPath;
    }

    public String getOriginalRuleString() {
        return originalRuleString;
    }

    @Override
    public String toString() {
        return originalRuleString; // Represent as the original parsed string
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MappingRule that = (MappingRule) o;
        // Compare based on all defining fields
        return operation == that.operation &&
                Objects.equals(targetPath, that.targetPath) &&
                Objects.equals(sourcePath, that.sourcePath) &&
                Objects.equals(mapKey, that.mapKey) &&
                Objects.equals(originalRuleString, that.originalRuleString); // Keep original string check for completeness
    }

    @Override
    public int hashCode() {
        // Hash based on all defining fields used in equals
        return Objects.hash(operation, targetPath, sourcePath, mapKey, originalRuleString);
    }

    public enum Operation {
        ASSIGN, // target = source
        APPEND, // target += source
        MAP_PUT, // target["key"] = source
        DELETE   // -target
    }
}