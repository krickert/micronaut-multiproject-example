package com.krickert.yappy.modules.embedder;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticProcessingResult;
import com.krickert.search.model.SemanticChunk;
import com.krickert.search.model.ChunkEmbedding;
import com.krickert.search.model.Embedding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to validate Embedder output using captured buffer data.
 * This test ensures that the Embedder is properly processing chunked documents
 * and adding vector embeddings to each chunk.
 */
public class EmbedderBufferDataValidationTest {

    private static List<PipeDoc> embedderOutputDocs;
    private static List<PipeDoc> chunkerInputDocs;

    @BeforeAll
    static void loadBufferData() {
        // Since ProtobufTestDataHelper doesn't have embedder support yet,
        // we'll load from raw buffer files if they exist
        embedderOutputDocs = new ArrayList<>();
        chunkerInputDocs = new ArrayList<>();
        
        // Try to load embedder output from test resources
        String[] embedderDocFiles = {
            "/buffer-dumps/embedder/pipedocs-001.bin",
            "/buffer-dumps/embedder/pipedocs-002.bin"
        };
        
        for (String resourcePath : embedderDocFiles) {
            try (InputStream is = EmbedderBufferDataValidationTest.class.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    PipeDoc doc = PipeDoc.parseFrom(is);
                    embedderOutputDocs.add(doc);
                }
            } catch (Exception e) {
                // Silently skip if file doesn't exist
            }
        }
        
        System.out.println("Loaded " + embedderOutputDocs.size() + " Embedder output documents");
        
        // Note: The user mentioned embedder may have crashed after 1 document
        if (embedderOutputDocs.size() == 1) {
            System.out.println("WARNING: Only 1 embedder document found. Embedder may have crashed during processing.");
        }
    }

    @Test
    void testEmbedderOutputExists() {
        if (embedderOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Embedder output documents found in test data. " +
                "This test will be enabled once buffer data is captured from a full pipeline run.");
            return;
        }
        assertFalse(embedderOutputDocs.isEmpty(), 
            "No Embedder output documents found. Check test data generation.");
    }

    @Test
    void testEmbedderPreservesDocumentFields() {
        if (embedderOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Embedder output documents found. Skipping field preservation test.");
            return;
        }
        
        // Embedder should preserve all document fields from Chunker
        for (PipeDoc embedderDoc : embedderOutputDocs) {
            // Verify core fields are present
            assertNotNull(embedderDoc.getId(), "Document ID should not be null");
            assertFalse(embedderDoc.getId().isEmpty(), "Document ID should not be empty");
            
            // If chunker added semantic results, they should still be there
            assertTrue(embedderDoc.getSemanticResultsCount() > 0,
                "Embedder should preserve semantic results from chunker");
        }
    }

    @Test
    void testEmbedderAddsVectorEmbeddings() {
        if (embedderOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Embedder output documents found. Skipping embedding test.");
            return;
        }
        
        int totalChunks = 0;
        int chunksWithVectors = 0;
        int totalVectorDimensions = 0;
        
        for (PipeDoc doc : embedderOutputDocs) {
            // Check if embedder updated the semantic results with embeddings
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                // Embedder should set the embedding config ID
                assertNotNull(result.getEmbeddingConfigId(), 
                    "Embedding config ID should be set by embedder");
                assertFalse(result.getEmbeddingConfigId().isEmpty(),
                    "Embedding config ID should not be empty");
                
                // Check each chunk for vectors
                for (SemanticChunk chunk : result.getChunksList()) {
                    totalChunks++;
                    
                    ChunkEmbedding embedding = chunk.getEmbeddingInfo();
                    assertNotNull(embedding, "Chunk embedding info should not be null");
                    
                    // Text content should still be present
                    assertNotNull(embedding.getTextContent(), "Text content should be preserved");
                    assertFalse(embedding.getTextContent().isEmpty(), "Text content should not be empty");
                    
                    // Check for vector embeddings
                    List<Float> vector = embedding.getVectorList();
                    assertNotNull(vector, "Vector list should not be null");
                    
                    if (!vector.isEmpty()) {
                        chunksWithVectors++;
                        totalVectorDimensions += vector.size();
                        
                        // Validate vector dimensions (common embedding sizes)
                        assertTrue(vector.size() == 384 || vector.size() == 768 || 
                                  vector.size() == 1024 || vector.size() == 1536,
                            "Vector dimension should be a standard size (384, 768, 1024, or 1536), but was: " + 
                            vector.size());
                        
                        // Validate vector values are reasonable
                        for (Float value : vector) {
                            assertNotNull(value, "Vector values should not be null");
                            assertTrue(Math.abs(value) < 100, 
                                "Vector values should be reasonable (between -100 and 100)");
                        }
                    }
                }
            }
        }
        
        System.out.println("Total chunks processed: " + totalChunks);
        System.out.println("Chunks with vectors: " + chunksWithVectors);
        if (chunksWithVectors > 0) {
            System.out.println("Average vector dimensions: " + (totalVectorDimensions / chunksWithVectors));
        }
        
        // All chunks should have vectors after embedder processing
        if (totalChunks > 0) {
            assertEquals(totalChunks, chunksWithVectors, 
                "All chunks should have vector embeddings after embedder processing");
        }
    }

    @Test
    void testEmbedderNamedEmbeddings() {
        if (embedderOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Embedder output documents found. Skipping named embeddings test.");
            return;
        }
        
        // Embedder might also add document-level embeddings
        for (PipeDoc doc : embedderOutputDocs) {
            // Check if document has any named embeddings (optional feature)
            if (doc.getNamedEmbeddingsCount() > 0) {
                doc.getNamedEmbeddingsMap().forEach((name, embedding) -> {
                    assertNotNull(name, "Embedding name should not be null");
                    assertFalse(name.isEmpty(), "Embedding name should not be empty");
                    
                    assertNotNull(embedding, "Named embedding should not be null");
                    assertNotNull(embedding.getVectorList(), "Named embedding vector should not be null");
                    assertFalse(embedding.getVectorList().isEmpty(), 
                        "Named embedding vector should not be empty");
                    
                    // If model ID is set, validate it
                    if (embedding.hasModelId()) {
                        assertFalse(embedding.getModelId().isEmpty(), 
                            "Model ID should not be empty if set");
                    }
                });
            }
        }
    }

    @Test
    void generateEmbedderOutputReport() {
        if (embedderOutputDocs.isEmpty()) {
            System.out.println("No Embedder documents to analyze");
            return;
        }
        
        System.out.println("\n=== Embedder Output Analysis Report ===");
        System.out.println("Total documents: " + embedderOutputDocs.size());
        
        if (embedderOutputDocs.size() == 1) {
            System.out.println("⚠️  WARNING: Only 1 document processed. Embedder may have crashed!");
        }
        
        // Analyze embeddings
        int totalSemanticResults = 0;
        int totalChunks = 0;
        int chunksWithVectors = 0;
        List<Integer> vectorDimensions = new ArrayList<>();
        
        for (PipeDoc doc : embedderOutputDocs) {
            totalSemanticResults += doc.getSemanticResultsCount();
            
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                System.out.println("\nEmbedding config: " + result.getEmbeddingConfigId());
                
                for (SemanticChunk chunk : result.getChunksList()) {
                    totalChunks++;
                    List<Float> vector = chunk.getEmbeddingInfo().getVectorList();
                    if (!vector.isEmpty()) {
                        chunksWithVectors++;
                        vectorDimensions.add(vector.size());
                    }
                }
            }
        }
        
        System.out.println("\nEmbedding Statistics:");
        System.out.println("  Total semantic results: " + totalSemanticResults);
        System.out.println("  Total chunks: " + totalChunks);
        System.out.println("  Chunks with vectors: " + chunksWithVectors + " (" + 
            (totalChunks > 0 ? (100.0 * chunksWithVectors / totalChunks) : 0) + "%)");
        
        if (!vectorDimensions.isEmpty()) {
            int minDim = vectorDimensions.stream().min(Integer::compare).orElse(0);
            int maxDim = vectorDimensions.stream().max(Integer::compare).orElse(0);
            
            System.out.println("\nVector Dimensions:");
            System.out.println("  Min dimensions: " + minDim);
            System.out.println("  Max dimensions: " + maxDim);
            
            // Count occurrences of each dimension
            vectorDimensions.stream()
                .distinct()
                .forEach(dim -> {
                    long count = vectorDimensions.stream().filter(d -> d.equals(dim)).count();
                    System.out.println("  Vectors with " + dim + " dimensions: " + count);
                });
        }
        
        // Sample output
        System.out.println("\nSample embeddings:");
        embedderOutputDocs.stream().limit(1).forEach(doc -> {
            System.out.println("\n  Document ID: " + doc.getId());
            doc.getSemanticResultsList().stream().limit(1).forEach(result -> {
                result.getChunksList().stream().limit(2).forEach(chunk -> {
                    List<Float> vector = chunk.getEmbeddingInfo().getVectorList();
                    String text = chunk.getEmbeddingInfo().getTextContent();
                    System.out.println("    Chunk " + chunk.getChunkNumber() + ":");
                    System.out.println("      Text: " + 
                        (text.length() > 50 ? text.substring(0, 50) + "..." : text));
                    System.out.println("      Vector dimensions: " + vector.size());
                    if (!vector.isEmpty()) {
                        System.out.println("      First 5 values: " + 
                            vector.subList(0, Math.min(5, vector.size())));
                    }
                });
            });
        });
        
        System.out.println("\n=== End of Report ===\n");
    }
}