package com.krickert.search.config.consul.exception;

// Custom exception for schema not found scenarios
public class SchemaNotFoundException extends SchemaException {
    public SchemaNotFoundException(String serviceImplementationName) {
        super("Schema not found for service implementation: " + serviceImplementationName);
    }
     public SchemaNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}