package com.krickert.yappy.modules.webcrawlerconnector;

import io.micronaut.runtime.Micronaut;

/**
 * Main application class for the Web Crawler Connector.
 * This class serves as the entry point for the application.
 */
public class Application {

    /**
     * Main method.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}