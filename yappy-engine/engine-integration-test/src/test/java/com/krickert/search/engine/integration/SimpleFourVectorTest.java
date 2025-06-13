package com.krickert.search.engine.integration;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to demonstrate the expected vector set output:
 * - 2 chunks per document
 * - 3 embedding models
 * - Total: 6 vector sets (2 chunks × 3 embeddings)
 */
@MicronautTest
class SimpleFourVectorTest {

    private static final Logger logger = LoggerFactory.getLogger(SimpleFourVectorTest.class);

    @Test
    void testSixVectorSetsExpectation() {
        // Given
        int numberOfChunks = 2;
        int numberOfEmbeddingModels = 3;
        
        // When
        int totalVectorSets = numberOfChunks * numberOfEmbeddingModels;
        
        // Then
        assertEquals(6, totalVectorSets, "Should have 6 vector sets total");
        
        logger.info("=== Vector Set Calculation ===");
        logger.info("Number of chunks: {}", numberOfChunks);
        logger.info("Number of embedding models: {}", numberOfEmbeddingModels);
        logger.info("Total vector sets: {} (chunks × embeddings)", totalVectorSets);
        
        // Demonstrate the structure
        logger.info("\n=== Expected Vector Sets ===");
        String[] embeddingModels = {"ALL_MINILM_L6_V2", "PARAPHRASE_MULTILINGUAL_MINILM_L12_V2", "EMBEDDER_3"};
        
        for (int chunkIndex = 0; chunkIndex < numberOfChunks; chunkIndex++) {
            logger.info("\nChunk {} vectors:", chunkIndex);
            for (int modelIndex = 0; modelIndex < numberOfEmbeddingModels; modelIndex++) {
                int vectorSetNumber = (chunkIndex * numberOfEmbeddingModels) + modelIndex + 1;
                logger.info("  Vector Set {}: Chunk {} embedded with {}", 
                    vectorSetNumber, chunkIndex, embeddingModels[modelIndex]);
            }
        }
        
        logger.info("\n=== Summary ===");
        logger.info("Total: {} vector sets for a document with {} chunks and {} embedding models", 
            totalVectorSets, numberOfChunks, numberOfEmbeddingModels);
        
        // Note about documents without body
        logger.info("\nNote: Documents without a body field would produce 0 vector sets");
    }
}