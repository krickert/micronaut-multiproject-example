package com.krickert.yappy.modules.tikaparser;

import io.micronaut.runtime.Micronaut;

/**
 * Main application class for the Tika Parser module.
 * 
 * <p>This class bootstraps the Micronaut application context and starts the
 * Tika Parser gRPC service. The service listens for document parsing requests
 * and uses Apache Tika to extract text content and metadata from various
 * document formats.</p>
 */
public class TikaParserApplication {
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private TikaParserApplication() {
        // Main application class
    }

    /**
     * Main entry point for the Tika Parser application.
     * 
     * @param args command line arguments passed to the application
     */
    public static void main(String[] args) {
        Micronaut.run(TikaParserApplication.class, args);
    }
}
