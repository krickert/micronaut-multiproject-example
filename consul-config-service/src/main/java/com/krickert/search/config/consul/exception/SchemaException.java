// src/main/java/com/krickert/search/config/consul/exception/SchemaException.java
package com.krickert.search.config.consul.exception;

// Simple base class (if it doesn't exist already)
public class SchemaException extends RuntimeException {
    public SchemaException(String message) {
        super(message);
    }
     public SchemaException(String message, Throwable cause) {
         super(message, cause);
     }
}