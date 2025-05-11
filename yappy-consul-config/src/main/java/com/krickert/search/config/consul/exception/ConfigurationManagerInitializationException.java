package com.krickert.search.config.consul.exception; // Or an appropriate package

public class ConfigurationManagerInitializationException extends RuntimeException {
    public ConfigurationManagerInitializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigurationManagerInitializationException(String message) {
        super(message);
    }
}