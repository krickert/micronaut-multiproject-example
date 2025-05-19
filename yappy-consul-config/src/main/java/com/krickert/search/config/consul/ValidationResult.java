package com.krickert.search.config.consul;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Represents the result of a configuration validation attempt.
 *
 * @param isValid True if validation passed, false otherwise.
 * @param errors  A list of error messages if validation failed. Empty if valid.
 */
public record ValidationResult(
        @JsonProperty("isValid") boolean isValid,
        @JsonProperty("errors") List<String> errors
) {
    // Canonical constructor, getters, equals, hashCode, toString are automatically provided.

    // Defensive copy for the errors list in the canonical constructor
    public ValidationResult { // Compact canonical constructor
        errors = Collections.unmodifiableList(errors == null ? Collections.emptyList() : List.copyOf(errors));
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, Collections.emptyList());
    }

    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, errors);
    }

    public static ValidationResult invalid(String error) {
        return new ValidationResult(false, Collections.singletonList(error));
    }
}