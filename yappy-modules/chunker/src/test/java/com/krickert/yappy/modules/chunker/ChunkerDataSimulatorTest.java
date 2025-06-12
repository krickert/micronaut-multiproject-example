package com.krickert.yappy.modules.chunker;

import com.krickert.search.model.*;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import com.krickert.search.model.util.TestDataGenerationConfig;
import com.krickert.search.model.util.DeterministicIdGenerator;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates chunker processing to generate test data.
 * This creates chunked documents from Tika output for testing purposes.
 */
public class ChunkerDataSimulatorTest {

    @TempDir
    Path tempDir;

    @Test
    void simulateChunkerProcessing() throws IOException {
        // Check if regeneration is enabled
        if (!TestDataGenerationConfig.isRegenerationEnabled()) {
            System.out.println("Test data regeneration is disabled. Set -D" + 
                    TestDataGenerationConfig.REGENERATE_PROPERTY + "=true to enable.");
            return;
        }
        
        // Load Tika output documents (we have 2 from the test data)
        List<PipeDoc> tikaDocs = new ArrayList<>();
        
        // Load the actual Tika test data files
        String[] tikaDocFiles = {
            "/test-data/tika-pipe-docs/tika_pipe_doc_doc-abc.bin",
            "/test-data/tika-pipe-docs/tika_pipe_doc_async_doc-async-abc.bin"
        };
        
        for (String resourcePath : tikaDocFiles) {
            try (var is = ChunkerDataSimulatorTest.class.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    PipeDoc doc = PipeDoc.parseFrom(is);
                    tikaDocs.add(doc);
                    System.out.println("Loaded Tika doc: " + doc.getId() + " with body length: " + 
                        (doc.hasBody() ? doc.getBody().length() : 0));
                }
            }
        }
        
        assertTrue(tikaDocs.size() > 0, "Should have loaded Tika documents");
        
        List<PipeDoc> chunkerOutputDocs = new ArrayList<>();
        
        // Simulate chunker processing for each document
        for (PipeDoc tikaDoc : tikaDocs) {
            System.out.println("\nProcessing document: " + tikaDoc.getId());
            
            // Create a copy of the document with chunking results
            PipeDoc.Builder chunkedDocBuilder = tikaDoc.toBuilder();
            
            // Simulate chunking the body field
            if (tikaDoc.hasBody()) {
                String body = tikaDoc.getBody();
                List<SemanticChunk> chunks = simulateChunking(body, "body-chunks");
                
                // Create semantic processing result
                SemanticProcessingResult.Builder resultBuilder = SemanticProcessingResult.newBuilder()
                    .setResultId(DeterministicIdGenerator.generateId("result", 0, body))
                    .setSourceFieldName("body")
                    .setChunkConfigId("sentence-splitter-v1")
                    .setResultSetName("body_sentences");
                
                // Add all chunks
                resultBuilder.addAllChunks(chunks);
                
                // Add some metadata
                resultBuilder.putMetadata("chunk_strategy", Value.newBuilder()
                    .setStringValue("sentence").build());
                resultBuilder.putMetadata("total_chunks", Value.newBuilder()
                    .setNumberValue(chunks.size()).build());
                
                // Add the semantic result to the document
                chunkedDocBuilder.addSemanticResults(resultBuilder.build());
                
                System.out.println("Created " + chunks.size() + " chunks from body");
            }
            
            // Also chunk the title if present
            if (tikaDoc.hasTitle() && !tikaDoc.getTitle().isEmpty()) {
                String title = tikaDoc.getTitle();
                List<SemanticChunk> titleChunks = simulateChunking(title, "title-chunks");
                
                SemanticProcessingResult titleResult = SemanticProcessingResult.newBuilder()
                    .setResultId(DeterministicIdGenerator.generateId("result", 1, title))
                    .setSourceFieldName("title")
                    .setChunkConfigId("title-processor-v1")
                    .setResultSetName("title_processed")
                    .addAllChunks(titleChunks)
                    .build();
                
                chunkedDocBuilder.addSemanticResults(titleResult);
                System.out.println("Created " + titleChunks.size() + " chunks from title");
            }
            
            PipeDoc chunkedDoc = chunkedDocBuilder.build();
            chunkerOutputDocs.add(chunkedDoc);
        }
        
        // Determine output directory based on configuration
        String baseOutputDir = TestDataGenerationConfig.getOutputDirectory();
        if (baseOutputDir.equals(TestDataGenerationConfig.DEFAULT_OUTPUT_DIR)) {
            baseOutputDir = baseOutputDir + "/yappy-test-data";
        }
        
        Path chunkerDir = Path.of(baseOutputDir + "/chunker-pipe-docs");
        chunkerDir.toFile().mkdirs();
        
        System.out.println("\nSaving chunker output to: " + chunkerDir);
        
        for (PipeDoc doc : chunkerOutputDocs) {
            String filename = "chunker_pipe_doc_" + doc.getId() + ".bin";
            Path docFile = chunkerDir.resolve(filename);
            
            try (FileOutputStream fos = new FileOutputStream(docFile.toFile())) {
                doc.writeTo(fos);
                System.out.println("Saved: " + filename);
            }
        }
        
        // Generate report
        System.out.println("\n=== Chunker Simulation Summary ===");
        System.out.println("Total documents processed: " + chunkerOutputDocs.size());
        
        for (PipeDoc doc : chunkerOutputDocs) {
            System.out.println("\nDocument: " + doc.getId());
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                System.out.println("  - " + result.getSourceFieldName() + ": " + 
                    result.getChunksCount() + " chunks");
            }
        }
        
        // If not writing to resources directory, provide instructions
        if (!baseOutputDir.contains("src/main/resources")) {
            System.out.println("\n=== IMPORTANT ===");
            System.out.println("Test data was written to temporary directory: " + baseOutputDir);
            System.out.println("To update the resources, copy files to: ../../yappy-models/protobuf-models-test-data-resources/src/main/resources/test-data/chunker-pipe-docs");
            System.out.println("Or run with -D" + TestDataGenerationConfig.OUTPUT_DIR_PROPERTY + 
                    "=../../yappy-models/protobuf-models-test-data-resources/src/main/resources/test-data to write directly to resources");
        }
    }
    
    /**
     * Simulates chunking by splitting text into sentences.
     */
    private List<SemanticChunk> simulateChunking(String text, String chunkIdPrefix) {
        List<SemanticChunk> chunks = new ArrayList<>();
        
        // Simple sentence splitting (real chunker would be more sophisticated)
        String[] sentences = text.split("(?<=[.!?])\\s+");
        
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i].trim();
            if (sentence.isEmpty()) continue;
            
            // Create chunk embedding info (without vectors for now)
            ChunkEmbedding embeddingInfo = ChunkEmbedding.newBuilder()
                .setTextContent(sentence)
                .setChunkId(chunkIdPrefix + "-" + i)
                .build();
            
            // Create semantic chunk
            SemanticChunk chunk = SemanticChunk.newBuilder()
                .setChunkId(DeterministicIdGenerator.generateId("chunk", i, sentence))
                .setChunkNumber(i)
                .setEmbeddingInfo(embeddingInfo)
                .putMetadata("sentence_index", Value.newBuilder()
                    .setNumberValue(i).build())
                .build();
            
            chunks.add(chunk);
        }
        
        // If no sentences found, create one chunk with the whole text
        if (chunks.isEmpty() && !text.trim().isEmpty()) {
            ChunkEmbedding embeddingInfo = ChunkEmbedding.newBuilder()
                .setTextContent(text)
                .setChunkId(chunkIdPrefix + "-0")
                .build();
            
            SemanticChunk chunk = SemanticChunk.newBuilder()
                .setChunkId(DeterministicIdGenerator.generateId("chunk", 0, text))
                .setChunkNumber(0)
                .setEmbeddingInfo(embeddingInfo)
                .build();
            
            chunks.add(chunk);
        }
        
        return chunks;
    }
}