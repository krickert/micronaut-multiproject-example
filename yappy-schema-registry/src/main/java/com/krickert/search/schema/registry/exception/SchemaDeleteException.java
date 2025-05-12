package com.krickert.search.schema.registry.exception;

public class SchemaDeleteException extends RuntimeException {
    public SchemaDeleteException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
