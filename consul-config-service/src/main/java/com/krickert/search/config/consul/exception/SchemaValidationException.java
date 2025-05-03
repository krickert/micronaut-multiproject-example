package com.krickert.search.config.consul.exception;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exception thrown when configParams fail validation against the registered schema.
 */
public class SchemaValidationException extends SchemaException {
    private final Set<String> validationMessages;

    public SchemaValidationException(String serviceImplementationName, Set<String> validationMessages) {
        super(String.format("Configuration validation failed for service '%s': %s",
                serviceImplementationName, formatMessages(validationMessages)));
        this.validationMessages = validationMessages;
    }

    public Set<String> getValidationMessages() {
        return validationMessages;
    }

    private static String formatMessages(Set<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return "No details available.";
        }
        return messages.stream().collect(Collectors.joining("; "));
    }
}