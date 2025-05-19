package com.krickert.search.config.consul.schema.exception;

public class SchemaNotFoundException extends RuntimeException {

    public SchemaNotFoundException(String message) {
        super(message);
    }

    public SchemaNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}