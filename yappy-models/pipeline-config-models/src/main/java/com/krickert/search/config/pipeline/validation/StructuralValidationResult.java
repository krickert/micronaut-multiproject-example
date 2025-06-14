package com.krickert.search.config.pipeline.validation;

import java.util.List;

/**
 * Result of structural validation.
 * This is a simplified version for client-side validation.
 * Server-side validation may have more detailed results.
 */
public record StructuralValidationResult(
    boolean valid,
    List<String> errors
) {
    /**
     * Creates a valid result with no errors.
     */
    public static StructuralValidationResult validResult() {
        return new StructuralValidationResult(true, List.of());
    }
    
    /**
     * Creates an invalid result with the given errors.
     */
    public static StructuralValidationResult invalid(List<String> errors) {
        return new StructuralValidationResult(false, errors != null ? errors : List.of());
    }
    
    /**
     * Creates an invalid result with a single error.
     */
    public static StructuralValidationResult invalid(String error) {
        return new StructuralValidationResult(false, List.of(error));
    }
}