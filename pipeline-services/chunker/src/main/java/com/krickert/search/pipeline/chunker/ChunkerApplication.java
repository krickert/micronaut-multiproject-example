package com.krickert.search.pipeline.chunker;

import io.micronaut.runtime.Micronaut;

/**
 * The main application class for the Chunker service.
 * This service processes documents by chunking them into smaller pieces with overlapping sections.
 * 
 * The service listens for documents on the "input-documents" Kafka topic and
 * publishes the chunked documents to the "chunked-documents" Kafka topic.
 * 
 * By default, this application runs in the dev environment, which is configured
 * to connect to local instances of Kafka, Apicurio, and Consul.
 */
public class ChunkerApplication {

    /**
     * Main method to start the Chunker application.
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        Micronaut.build(args)
                .defaultEnvironments("dev") // Default to dev environment
                .mainClass(ChunkerApplication.class)
                .start();
    }
}