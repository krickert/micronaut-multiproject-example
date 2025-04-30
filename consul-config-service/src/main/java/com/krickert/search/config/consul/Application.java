package com.krickert.search.config.consul;

import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application class for the Consul Configuration Service.
 * This service provides a centralized configuration management system using Consul KV store.
 */
public class Application {
    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    /**
     * The main method serves as the entry point for the Consul Configuration Service application.
     * Initializes and starts the Micronaut application.
     *
     * @param args the command-line arguments passed to the application
     */
    public static void main(String[] args) {
        LOG.info("Starting Consul Configuration Service...");
        Micronaut.run(Application.class, args);
    }
}