package com.krickert.yappy.modules.embedder;

import io.micronaut.runtime.Micronaut;

/**
 * Main application class for the Embedder service.
 * This service provides embedding functionality for documents and chunks.
 */
public class EmbedderApplication {
    public static void main(String[] args) {
        Micronaut.run(EmbedderApplication.class, args);
    }
}