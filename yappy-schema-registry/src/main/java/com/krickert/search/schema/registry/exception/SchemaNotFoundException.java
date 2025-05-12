package com.krickert.search.schema.registry.exception;

public class SchemaNotFoundException extends RuntimeException {

    public SchemaNotFoundException(String message) {
        super(message);
    }

    public SchemaNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}