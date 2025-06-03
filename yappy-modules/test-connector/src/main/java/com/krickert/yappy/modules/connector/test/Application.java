package com.krickert.yappy.modules.connector.test;

import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main application class for the test connector.
 * This connector provides sample PipeDocs for testing purposes.
 */
public class Application {
    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("Starting Test Connector Application");
        Micronaut.run(Application.class, args);
    }
}