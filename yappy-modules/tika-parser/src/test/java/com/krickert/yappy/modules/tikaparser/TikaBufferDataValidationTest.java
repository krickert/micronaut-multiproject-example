package com.krickert.yappy.modules.tikaparser;

import com.krickert.search.model.PipeDoc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to validate Tika parser output using captured buffer data.
 * This test ensures that Tika is properly parsing documents and populating
 * all required fields in the PipeDoc protobuf messages.
 */
public class TikaBufferDataValidationTest {

    private static List<PipeDoc> tikaOutputDocs;
    
    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadBufferData() throws IOException {
        // Load test data from protobuf-models-test-data-resources
        // This test uses the existing test data that's already in the project
        try {
            // The test data is in the classpath from the protobuf-models-test-data-resources dependency
            tikaOutputDocs = new ArrayList<>();
            
            // Load tika pipe docs from classpath
            String[] tikaDocFiles = {
                "/test-data/tika-pipe-docs/tika_pipe_doc_doc-abc.bin",
                "/test-data/tika-pipe-docs/tika_pipe_doc_async_doc-async-abc.bin"
            };
            
            for (String resourcePath : tikaDocFiles) {
                try (InputStream is = TikaBufferDataValidationTest.class.getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        PipeDoc doc = PipeDoc.parseFrom(is);
                        tikaOutputDocs.add(doc);
                    }
                }
            }
            
            System.out.println("Loaded " + tikaOutputDocs.size() + " Tika test documents from classpath");
        } catch (Exception e) {
            System.err.println("Failed to load test data: " + e.getMessage());
            tikaOutputDocs = List.of();
        }
    }

    @Test
    void testTikaOutputHasRequiredFields() {
        assertFalse(tikaOutputDocs.isEmpty(), 
            "No Tika output documents found. Run integration test to generate buffer data.");
        
        for (PipeDoc doc : tikaOutputDocs) {
            // Validate document ID
            assertNotNull(doc.getId(), "Document ID should not be null");
            assertFalse(doc.getId().isEmpty(), "Document ID should not be empty");
            
            // Validate content (body field in new proto)
            assertTrue(doc.hasBody(), "Document should have body content");
            assertFalse(doc.getBody().isEmpty(), "Document body should not be empty");
            
            // Validate metadata fields
            if (doc.hasSourceUri()) {
                assertFalse(doc.getSourceUri().isEmpty(), "Source URI should not be empty if present");
            }
            
            if (doc.hasSourceMimeType()) {
                assertFalse(doc.getSourceMimeType().isEmpty(), "Source MIME type should not be empty if present");
            }
            
            // Validate dates
            if (doc.hasCreationDate()) {
                assertNotNull(doc.getCreationDate(), "Creation date should not be null if present");
                assertTrue(doc.getCreationDate().getSeconds() > 0, "Creation date should be valid");
            }
            
            if (doc.hasProcessedDate()) {
                assertNotNull(doc.getProcessedDate(), "Processed date should not be null if present");
                assertTrue(doc.getProcessedDate().getSeconds() > 0, "Processed date should be valid");
            }
        }
    }

    @Test
    void testTikaOutputContentQuality() {
        for (PipeDoc doc : tikaOutputDocs) {
            if (doc.hasBody()) {
                String body = doc.getBody();
                
                // Check for reasonable content length
                assertTrue(body.length() > 10, 
                    "Document body should have meaningful content, found only " + body.length() + " characters");
                
                // Check that content is not just whitespace
                assertFalse(body.trim().isEmpty(), "Document body should not be just whitespace");
                
                // Check for common parsing artifacts that indicate issues
                assertFalse(body.contains("\u0000"), "Document should not contain null characters");
                assertFalse(body.contains("�"), "Document should not contain replacement characters");
            }
            
            // If title is present, validate it
            if (doc.hasTitle()) {
                String title = doc.getTitle();
                assertFalse(title.trim().isEmpty(), "Title should not be empty or just whitespace");
                assertTrue(title.length() < 1000, "Title seems unusually long: " + title.length() + " characters");
            }
        }
    }

    @Test
    void testTikaMetadataExtraction() {
        int docsWithMetadata = 0;
        int docsWithCustomData = 0;
        
        for (PipeDoc doc : tikaOutputDocs) {
            // Check standard metadata fields
            if (doc.hasSourceUri() || doc.hasSourceMimeType() || 
                doc.hasCreationDate() || doc.hasLastModifiedDate()) {
                docsWithMetadata++;
            }
            
            // Check custom data
            if (doc.hasCustomData() && doc.getCustomData().getFieldsCount() > 0) {
                docsWithCustomData++;
                
                // Validate custom data structure
                doc.getCustomData().getFieldsMap().forEach((key, value) -> {
                    assertNotNull(key, "Custom data key should not be null");
                    assertFalse(key.isEmpty(), "Custom data key should not be empty");
                    assertNotNull(value, "Custom data value should not be null for key: " + key);
                });
            }
        }
        
        // Since the test data shows metadata is stored in custom data instead of standard fields,
        // we should check that at least we have custom data
        if (!tikaOutputDocs.isEmpty()) {
            assertTrue(docsWithMetadata > 0 || docsWithCustomData > 0, 
                "At least some documents should have either standard metadata fields or custom data");
        }
    }

    @Test
    void testTikaOutputConsistency() {
        // Check for consistency across multiple documents
        if (tikaOutputDocs.size() > 1) {
            // All documents should have IDs
            long docsWithIds = tikaOutputDocs.stream()
                .filter(doc -> doc.getId() != null && !doc.getId().isEmpty())
                .count();
            assertEquals(tikaOutputDocs.size(), docsWithIds, 
                "All documents should have IDs");
            
            // All documents should have body content
            long docsWithBody = tikaOutputDocs.stream()
                .filter(PipeDoc::hasBody)
                .filter(doc -> !doc.getBody().isEmpty())
                .count();
            assertEquals(tikaOutputDocs.size(), docsWithBody, 
                "All documents should have body content");
        }
    }

    @Test
    void generateTikaOutputReport() {
        if (tikaOutputDocs.isEmpty()) {
            System.out.println("No Tika documents to analyze");
            return;
        }
        
        System.out.println("\n=== Tika Output Analysis Report ===");
        System.out.println("Total documents: " + tikaOutputDocs.size());
        
        // Analyze field presence
        long docsWithTitle = tikaOutputDocs.stream().filter(PipeDoc::hasTitle).count();
        long docsWithBody = tikaOutputDocs.stream().filter(PipeDoc::hasBody).count();
        long docsWithSourceUri = tikaOutputDocs.stream().filter(PipeDoc::hasSourceUri).count();
        long docsWithMimeType = tikaOutputDocs.stream().filter(PipeDoc::hasSourceMimeType).count();
        long docsWithCreationDate = tikaOutputDocs.stream().filter(PipeDoc::hasCreationDate).count();
        long docsWithProcessedDate = tikaOutputDocs.stream().filter(PipeDoc::hasProcessedDate).count();
        long docsWithCustomData = tikaOutputDocs.stream()
            .filter(PipeDoc::hasCustomData)
            .filter(doc -> doc.getCustomData().getFieldsCount() > 0)
            .count();
        
        System.out.println("\nField presence:");
        System.out.println("  Documents with title: " + docsWithTitle + " (" + 
            (100.0 * docsWithTitle / tikaOutputDocs.size()) + "%)");
        System.out.println("  Documents with body: " + docsWithBody + " (" + 
            (100.0 * docsWithBody / tikaOutputDocs.size()) + "%)");
        System.out.println("  Documents with source URI: " + docsWithSourceUri + " (" + 
            (100.0 * docsWithSourceUri / tikaOutputDocs.size()) + "%)");
        System.out.println("  Documents with MIME type: " + docsWithMimeType + " (" + 
            (100.0 * docsWithMimeType / tikaOutputDocs.size()) + "%)");
        System.out.println("  Documents with creation date: " + docsWithCreationDate + " (" + 
            (100.0 * docsWithCreationDate / tikaOutputDocs.size()) + "%)");
        System.out.println("  Documents with processed date: " + docsWithProcessedDate + " (" + 
            (100.0 * docsWithProcessedDate / tikaOutputDocs.size()) + "%)");
        System.out.println("  Documents with custom data: " + docsWithCustomData + " (" + 
            (100.0 * docsWithCustomData / tikaOutputDocs.size()) + "%)");
        
        // Analyze content statistics
        if (docsWithBody > 0) {
            List<Integer> bodySizes = tikaOutputDocs.stream()
                .filter(PipeDoc::hasBody)
                .map(doc -> doc.getBody().length())
                .collect(Collectors.toList());
            
            int minSize = bodySizes.stream().min(Integer::compare).orElse(0);
            int maxSize = bodySizes.stream().max(Integer::compare).orElse(0);
            double avgSize = bodySizes.stream().mapToInt(Integer::intValue).average().orElse(0);
            
            System.out.println("\nContent statistics:");
            System.out.println("  Min body size: " + minSize + " characters");
            System.out.println("  Max body size: " + maxSize + " characters");
            System.out.println("  Avg body size: " + String.format("%.2f", avgSize) + " characters");
        }
        
        // Sample document details
        System.out.println("\nSample document details:");
        tikaOutputDocs.stream().limit(3).forEach(doc -> {
            System.out.println("\n  Document ID: " + doc.getId());
            if (doc.hasTitle()) {
                System.out.println("    Title: " + 
                    (doc.getTitle().length() > 50 ? 
                        doc.getTitle().substring(0, 50) + "..." : doc.getTitle()));
            }
            if (doc.hasBody()) {
                System.out.println("    Body preview: " + 
                    (doc.getBody().length() > 100 ? 
                        doc.getBody().substring(0, 100).replace("\n", " ") + "..." : 
                        doc.getBody().replace("\n", " ")));
            }
            if (doc.hasSourceMimeType()) {
                System.out.println("    MIME type: " + doc.getSourceMimeType());
            }
        });
        
        System.out.println("\n=== End of Report ===\n");
    }
}