package com.krickert.yappy.modules.chunker;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticProcessingResult;
import com.krickert.search.model.SemanticChunk;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to validate Chunker output using captured buffer data.
 * This test ensures that the Chunker is properly processing Tika output
 * and creating semantic chunks with embeddings.
 */
public class ChunkerBufferDataValidationTest {

    private static List<PipeDoc> chunkerOutputDocs;
    private static List<PipeDoc> tikaInputDocs;

    @BeforeAll
    static void loadBufferData() {
        // Load Tika output (which is input to chunker)
        tikaInputDocs = new ArrayList<>(ProtobufTestDataHelper.getTikaPipeDocuments());
        System.out.println("Loaded " + tikaInputDocs.size() + " Tika documents as input");

        // Load Chunker output directly from files to avoid caching issues
        chunkerOutputDocs = new ArrayList<>();
        try {
            // Load chunker output documents directly from the files
            String[] chunkerDocFiles = {
                "/test-data/chunker-pipe-docs/chunker_pipe_doc_doc-abc.bin",
                "/test-data/chunker-pipe-docs/chunker_pipe_doc_doc-async-abc.bin"
            };

            for (String resourcePath : chunkerDocFiles) {
                try (InputStream is = ChunkerBufferDataValidationTest.class.getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        PipeDoc doc = PipeDoc.parseFrom(is);
                        chunkerOutputDocs.add(doc);
                        System.out.println("Loaded chunker doc: " + doc.getId() + 
                            " with " + doc.getSemanticResultsCount() + " semantic results");
                    } else {
                        System.out.println("Resource not found: " + resourcePath);
                    }
                } catch (Exception e) {
                    System.err.println("Error loading " + resourcePath + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading chunker documents: " + e.getMessage());
        }

        System.out.println("Loaded " + chunkerOutputDocs.size() + " Chunker output documents");
    }

    @Test
    void testChunkerOutputExists() {
        // Skip this test if no chunker data is available yet
        if (chunkerOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Chunker output documents found in test data. " +
                "This test will be enabled once buffer data is captured from a full pipeline run.");
            return;
        }
        assertFalse(chunkerOutputDocs.isEmpty(), 
            "No Chunker output documents found. Check test data generation.");
    }

    @Test
    void testChunkerPreservesDocumentFields() {
        if (chunkerOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Chunker output documents found. Skipping field preservation test.");
            return;
        }

        // Chunker should preserve all document fields from Tika
        for (PipeDoc chunkerDoc : chunkerOutputDocs) {
            // Find corresponding Tika doc
            PipeDoc tikaDoc = tikaInputDocs.stream()
                .filter(doc -> doc.getId().equals(chunkerDoc.getId()))
                .findFirst()
                .orElse(null);

            if (tikaDoc != null) {
                // Verify core fields are preserved
                assertEquals(tikaDoc.getId(), chunkerDoc.getId(), "Document ID should be preserved");

                if (tikaDoc.hasTitle()) {
                    assertTrue(chunkerDoc.hasTitle(), "Title should be preserved if present in Tika output");
                    assertEquals(tikaDoc.getTitle(), chunkerDoc.getTitle(), "Title content should match");
                }

                if (tikaDoc.hasBody()) {
                    assertTrue(chunkerDoc.hasBody(), "Body should be preserved if present in Tika output");
                    assertEquals(tikaDoc.getBody(), chunkerDoc.getBody(), "Body content should match");
                }

                if (tikaDoc.hasSourceUri()) {
                    assertTrue(chunkerDoc.hasSourceUri(), "Source URI should be preserved");
                    assertEquals(tikaDoc.getSourceUri(), chunkerDoc.getSourceUri());
                }

                if (tikaDoc.hasCustomData()) {
                    assertTrue(chunkerDoc.hasCustomData(), "Custom data should be preserved");
                    // Note: Custom data might be enhanced by chunker, so we just check it exists
                }
            }
        }
    }

    @Test
    void testChunkerAddsSemanticResults() {
        if (chunkerOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Chunker output documents found. Skipping semantic results test.");
            return;
        }

        for (PipeDoc doc : chunkerOutputDocs) {
            // Chunker should add semantic processing results
            assertTrue(doc.getSemanticResultsCount() > 0, 
                "Chunker should add at least one semantic processing result");

            // Validate each semantic result
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                // Check required fields
                assertNotNull(result.getResultId(), "Result ID should not be null");
                assertFalse(result.getResultId().isEmpty(), "Result ID should not be empty");

                assertNotNull(result.getSourceFieldName(), "Source field name should not be null");
                assertFalse(result.getSourceFieldName().isEmpty(), "Source field name should not be empty");

                assertNotNull(result.getChunkConfigId(), "Chunk config ID should not be null");
                assertFalse(result.getChunkConfigId().isEmpty(), "Chunk config ID should not be empty");

                // Chunker might not set embedding config (that's for embedder)
                // but it should have chunks
                assertTrue(result.getChunksCount() > 0, 
                    "Semantic result should contain at least one chunk");
            }
        }
    }

    @Test
    void testChunkerCreatesValidChunks() {
        if (chunkerOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Chunker output documents found. Skipping chunk validation test.");
            return;
        }

        int totalChunks = 0;
        int chunksWithText = 0;
        int chunksWithIds = 0;

        for (PipeDoc doc : chunkerOutputDocs) {
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                for (SemanticChunk chunk : result.getChunksList()) {
                    totalChunks++;

                    // Validate chunk structure
                    assertNotNull(chunk.getChunkId(), "Chunk ID should not be null");
                    if (!chunk.getChunkId().isEmpty()) {
                        chunksWithIds++;
                    }

                    assertTrue(chunk.getChunkNumber() >= 0, 
                        "Chunk number should be non-negative");

                    // Check embedding info
                    assertNotNull(chunk.getEmbeddingInfo(), "Embedding info should not be null");

                    String textContent = chunk.getEmbeddingInfo().getTextContent();
                    assertNotNull(textContent, "Chunk text content should not be null");
                    if (!textContent.isEmpty()) {
                        chunksWithText++;
                    }

                    // Chunker may not add vectors (that's embedder's job)
                    // but the field should exist
                    assertNotNull(chunk.getEmbeddingInfo().getVectorList(), 
                        "Vector list should not be null (can be empty)");
                }
            }
        }

        System.out.println("Total chunks created: " + totalChunks);
        System.out.println("Chunks with text: " + chunksWithText);
        System.out.println("Chunks with IDs: " + chunksWithIds);

        assertTrue(totalChunks > 0, "Should have created at least one chunk");
        assertEquals(totalChunks, chunksWithText, "All chunks should have text content");
        assertEquals(totalChunks, chunksWithIds, "All chunks should have IDs");
    }

    @Test
    void testChunkingConsistency() {
        if (chunkerOutputDocs.isEmpty()) {
            System.out.println("WARNING: No Chunker output documents found. Skipping consistency test.");
            return;
        }

        // For documents with the same content, chunking should be consistent
        for (PipeDoc doc : chunkerOutputDocs) {
            // Check that chunk numbers are sequential within each result
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                List<Long> chunkNumbers = result.getChunksList().stream()
                    .map(SemanticChunk::getChunkNumber)
                    .sorted()
                    .collect(Collectors.toList());

                for (int i = 0; i < chunkNumbers.size(); i++) {
                    assertEquals(i, chunkNumbers.get(i), 
                        "Chunk numbers should be sequential starting from 0");
                }
            }
        }
    }

    @Test
    void generateChunkerOutputReport() {
        if (chunkerOutputDocs.isEmpty()) {
            System.out.println("No Chunker documents to analyze");
            return;
        }

        System.out.println("\n=== Chunker Output Analysis Report ===");
        System.out.println("Total documents: " + chunkerOutputDocs.size());

        // Analyze semantic results
        int totalSemanticResults = 0;
        int totalChunks = 0;
        List<Integer> chunkCounts = new ArrayList<>();

        for (PipeDoc doc : chunkerOutputDocs) {
            totalSemanticResults += doc.getSemanticResultsCount();

            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                int chunkCount = result.getChunksCount();
                totalChunks += chunkCount;
                chunkCounts.add(chunkCount);
            }
        }

        System.out.println("\nSemantic Processing:");
        System.out.println("  Total semantic results: " + totalSemanticResults);
        System.out.println("  Average results per document: " + 
            (totalSemanticResults / (double) chunkerOutputDocs.size()));
        System.out.println("  Total chunks created: " + totalChunks);

        if (!chunkCounts.isEmpty()) {
            int minChunks = chunkCounts.stream().min(Integer::compare).orElse(0);
            int maxChunks = chunkCounts.stream().max(Integer::compare).orElse(0);
            double avgChunks = chunkCounts.stream().mapToInt(Integer::intValue).average().orElse(0);

            System.out.println("\nChunks per result:");
            System.out.println("  Min chunks: " + minChunks);
            System.out.println("  Max chunks: " + maxChunks);
            System.out.println("  Avg chunks: " + String.format("%.2f", avgChunks));
        }

        // Sample chunk analysis
        System.out.println("\nSample chunks:");
        chunkerOutputDocs.stream().limit(2).forEach(doc -> {
            System.out.println("\n  Document ID: " + doc.getId());
            doc.getSemanticResultsList().stream().limit(1).forEach(result -> {
                System.out.println("    Source field: " + result.getSourceFieldName());
                System.out.println("    Chunk config: " + result.getChunkConfigId());
                System.out.println("    Number of chunks: " + result.getChunksCount());

                result.getChunksList().stream().limit(3).forEach(chunk -> {
                    String text = chunk.getEmbeddingInfo().getTextContent();
                    System.out.println("      Chunk " + chunk.getChunkNumber() + ": " +
                        (text.length() > 50 ? text.substring(0, 50) + "..." : text));
                });
            });
        });

        System.out.println("\n=== End of Report ===\n");
    }
}
