package com.krickert.search.config.consul.schema.exception;

public class SchemaDeleteException extends RuntimeException {
    public SchemaDeleteException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
