package com.krickert.search.model.test;

import com.krickert.search.model.*;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generates test data for the entire pipeline with proper accumulation:
 * Chunker1 -> Chunker2 -> Embedder1 -> Embedder2
 * 
 * This creates 4 distinct chunk+embedding combinations:
 * 1. Chunker1 chunks with Embedder1 embeddings
 * 2. Chunker2 chunks with Embedder1 embeddings
 * 3. Chunker1 chunks with Embedder2 embeddings (replaces Embedder1)
 * 4. Chunker2 chunks with Embedder2 embeddings (replaces Embedder1)
 */
public class FixedPipelineDataGenerator {
    
    private static final Path OUTPUT_BASE = Paths.get("build/pipeline-test-data-v2");
    
    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(OUTPUT_BASE);
    }
    
    @Test
    void generateCorrectPipelineData() throws IOException {
        System.out.println("=== Fixed Pipeline Data Generation ===\n");
        
        // Step 1: Start with sample documents
        System.out.println("1. Loading Sample Documents:");
        List<PipeDoc> sampleDocs = ProtobufTestDataHelper.getOrderedSamplePipeDocuments()
            .stream().limit(5).collect(Collectors.toList()); // Process 5 for testing
        System.out.println("  Loaded " + sampleDocs.size() + " documents");
        
        // Step 2: Process through Chunker1
        System.out.println("\n2. Processing through Chunker1 (sentences):");
        List<PipeDoc> afterChunker1 = processChunker1(sampleDocs);
        saveDocuments(afterChunker1, "after-chunker1");
        
        // Step 3: Process through Chunker2 (adds more chunks)
        System.out.println("\n3. Processing through Chunker2 (sliding windows):");
        List<PipeDoc> afterChunker2 = processChunker2(afterChunker1);
        saveDocuments(afterChunker2, "after-chunker2");
        
        // Step 4: Process through Embedder1 (embeds all existing chunks)
        System.out.println("\n4. Processing through Embedder1 (embeds all chunks):");
        List<PipeDoc> afterEmbedder1 = processEmbedder1(afterChunker2);
        saveDocuments(afterEmbedder1, "after-embedder1");
        
        // Step 5: Process through Embedder2 (re-embeds all chunks)
        System.out.println("\n5. Processing through Embedder2 (re-embeds all chunks):");
        List<PipeDoc> afterEmbedder2 = processEmbedder2(afterEmbedder1);
        saveDocuments(afterEmbedder2, "after-embedder2");
        
        // Validate final output
        System.out.println("\n=== Final Validation ===");
        validateFinalOutput(afterEmbedder2);
    }
    
    private List<PipeDoc> processChunker1(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        
        for (PipeDoc doc : inputDocs) {
            if (!doc.hasBody() || doc.getBody().isEmpty()) {
                outputDocs.add(doc);
                continue;
            }
            
            PipeDoc.Builder builder = doc.toBuilder();
            
            // Create sentence-based chunks
            SemanticProcessingResult.Builder result = SemanticProcessingResult.newBuilder()
                .setResultId("chunker1-" + UUID.randomUUID())
                .setSourceFieldName("body")
                .setChunkConfigId("sentence-chunker")
                .setResultSetName("sentence_chunks");
            
            String body = doc.getBody();
            String[] sentences = body.split("(?<=[.!?])\\s+");
            
            for (int i = 0; i < sentences.length; i++) {
                String sentence = sentences[i].trim();
                if (sentence.isEmpty()) continue;
                
                ChunkEmbedding embedding = ChunkEmbedding.newBuilder()
                    .setTextContent(sentence)
                    .setChunkId("sent-" + i)
                    .build();
                
                SemanticChunk chunk = SemanticChunk.newBuilder()
                    .setChunkId("c1-" + doc.getId() + "-" + i)
                    .setChunkNumber(i)
                    .setEmbeddingInfo(embedding)
                    .putMetadata("chunk_type", com.google.protobuf.Value.newBuilder()
                        .setStringValue("sentence").build())
                    .build();
                
                result.addChunks(chunk);
            }
            
            builder.addSemanticResults(result);
            outputDocs.add(builder.build());
        }
        
        int totalChunks = outputDocs.stream()
            .mapToInt(d -> d.getSemanticResultsCount() > 0 ? d.getSemanticResults(0).getChunksCount() : 0)
            .sum();
        
        System.out.println("  Created " + totalChunks + " sentence chunks");
        System.out.println("  Documents now have " + outputDocs.stream()
            .mapToInt(PipeDoc::getSemanticResultsCount).sum() + " semantic results");
        
        return outputDocs;
    }
    
    private List<PipeDoc> processChunker2(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        
        for (PipeDoc doc : inputDocs) {
            PipeDoc.Builder builder = doc.toBuilder();
            
            // Add sliding window chunks
            if (doc.hasBody() && !doc.getBody().isEmpty()) {
                SemanticProcessingResult.Builder result = SemanticProcessingResult.newBuilder()
                    .setResultId("chunker2-" + UUID.randomUUID())
                    .setSourceFieldName("body")
                    .setChunkConfigId("sliding-window")
                    .setResultSetName("window_chunks");
                
                String body = doc.getBody();
                int windowSize = 200;
                int overlap = 50;
                int chunkNum = 0;
                
                for (int i = 0; i < body.length(); i += (windowSize - overlap)) {
                    int end = Math.min(i + windowSize, body.length());
                    String chunkText = body.substring(i, end);
                    
                    ChunkEmbedding embedding = ChunkEmbedding.newBuilder()
                        .setTextContent(chunkText)
                        .setChunkId("win-" + chunkNum)
                        .build();
                    
                    SemanticChunk chunk = SemanticChunk.newBuilder()
                        .setChunkId("c2-" + doc.getId() + "-" + chunkNum)
                        .setChunkNumber(chunkNum)
                        .setEmbeddingInfo(embedding)
                        .putMetadata("chunk_type", com.google.protobuf.Value.newBuilder()
                            .setStringValue("window").build())
                        .putMetadata("window_start", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(i).build())
                        .build();
                    
                    result.addChunks(chunk);
                    chunkNum++;
                    
                    if (end >= body.length()) break;
                }
                
                builder.addSemanticResults(result);
            }
            
            outputDocs.add(builder.build());
        }
        
        int totalResults = outputDocs.stream().mapToInt(PipeDoc::getSemanticResultsCount).sum();
        System.out.println("  Documents now have " + totalResults + " semantic results total");
        System.out.println("  (Should be 2 per document with body)");
        
        return outputDocs;
    }
    
    private List<PipeDoc> processEmbedder1(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        Random random = new Random(42);
        
        for (PipeDoc doc : inputDocs) {
            PipeDoc.Builder builder = doc.toBuilder();
            
            // Process ALL semantic results
            for (int i = 0; i < doc.getSemanticResultsCount(); i++) {
                SemanticProcessingResult oldResult = doc.getSemanticResults(i);
                SemanticProcessingResult.Builder newResult = oldResult.toBuilder();
                
                // Set embedder1 config
                newResult.setEmbeddingConfigId("embedder1-ada-002");
                
                // Add embeddings to all chunks
                for (int j = 0; j < oldResult.getChunksCount(); j++) {
                    SemanticChunk oldChunk = oldResult.getChunks(j);
                    ChunkEmbedding.Builder embedding = oldChunk.getEmbeddingInfo().toBuilder();
                    
                    // Add 384-dimensional embeddings
                    for (int k = 0; k < 384; k++) {
                        embedding.addVector(random.nextFloat() * 2 - 1);
                    }
                    
                    SemanticChunk newChunk = oldChunk.toBuilder()
                        .setEmbeddingInfo(embedding)
                        .putMetadata("embedder", com.google.protobuf.Value.newBuilder()
                            .setStringValue("embedder1").build())
                        .putMetadata("embedding_dim", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(384).build())
                        .build();
                    
                    newResult.setChunks(j, newChunk);
                }
                
                builder.setSemanticResults(i, newResult);
            }
            
            outputDocs.add(builder.build());
        }
        
        int embeddedResults = outputDocs.stream()
            .mapToInt(d -> (int) d.getSemanticResultsList().stream()
                .filter(r -> !r.getEmbeddingConfigId().isEmpty())
                .count())
            .sum();
        
        System.out.println("  Embedded " + embeddedResults + " semantic results with embedder1");
        System.out.println("  All chunks now have 384-dimensional embeddings");
        
        return outputDocs;
    }
    
    private List<PipeDoc> processEmbedder2(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        Random random = new Random(123);
        
        for (PipeDoc doc : inputDocs) {
            PipeDoc.Builder builder = doc.toBuilder();
            
            // Process ALL semantic results - create NEW ones with embedder2 embeddings
            // This simulates the actual embedder behavior which ADDS new results
            int originalResultCount = doc.getSemanticResultsCount();
            
            for (int i = 0; i < originalResultCount; i++) {
                SemanticProcessingResult oldResult = doc.getSemanticResults(i);
                
                // Skip results that don't have embeddings (shouldn't happen after embedder1)
                if (oldResult.getEmbeddingConfigId().isEmpty()) {
                    continue;
                }
                
                // Create a new result with embedder2 embeddings
                SemanticProcessingResult.Builder newResult = oldResult.toBuilder();
                
                // Update to embedder2 config
                newResult.setEmbeddingConfigId("embedder2-minilm");
                newResult.setResultId("embedder2-" + UUID.randomUUID());
                
                // Update result set name
                String newResultSetName = oldResult.getResultSetName().replace("embedder1", "embedder2");
                newResult.setResultSetName(newResultSetName);
                
                // Create new embeddings for all chunks
                for (int j = 0; j < oldResult.getChunksCount(); j++) {
                    SemanticChunk oldChunk = oldResult.getChunks(j);
                    ChunkEmbedding.Builder embedding = oldChunk.getEmbeddingInfo().toBuilder();
                    
                    // Clear old embeddings and add new ones
                    embedding.clearVector();
                    
                    // Add 768-dimensional embeddings
                    for (int k = 0; k < 768; k++) {
                        embedding.addVector(random.nextFloat() * 2 - 1);
                    }
                    
                    SemanticChunk newChunk = oldChunk.toBuilder()
                        .setEmbeddingInfo(embedding)
                        .putMetadata("embedder", com.google.protobuf.Value.newBuilder()
                            .setStringValue("embedder2").build())
                        .putMetadata("embedding_dim", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(768).build())
                        .build();
                    
                    newResult.setChunks(j, newChunk);
                }
                
                // ADD the new result (don't replace)
                builder.addSemanticResults(newResult);
            }
            
            outputDocs.add(builder.build());
        }
        
        int embeddedResults = outputDocs.stream()
            .mapToInt(d -> (int) d.getSemanticResultsList().stream()
                .filter(r -> !r.getEmbeddingConfigId().isEmpty())
                .count())
            .sum();
        
        System.out.println("  Re-embedded " + embeddedResults + " semantic results with embedder2");
        System.out.println("  All chunks now have 768-dimensional embeddings");
        
        return outputDocs;
    }
    
    private void saveDocuments(List<PipeDoc> docs, String stage) throws IOException {
        Path stageDir = OUTPUT_BASE.resolve(stage);
        Files.createDirectories(stageDir);
        
        for (int i = 0; i < docs.size(); i++) {
            PipeDoc doc = docs.get(i);
            String filename = String.format("%s-%03d.bin", stage, i);
            Path outputPath = stageDir.resolve(filename);
            
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                doc.writeTo(fos);
            }
        }
        
        System.out.println("  Saved " + docs.size() + " documents to " + stageDir);
    }
    
    private void validateFinalOutput(List<PipeDoc> finalDocs) {
        for (PipeDoc doc : finalDocs) {
            System.out.println("\nDocument: " + doc.getId());
            System.out.println("  Semantic results: " + doc.getSemanticResultsCount());
            
            int totalChunks = 0;
            
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                int chunks = result.getChunksCount();
                totalChunks += chunks;
                
                System.out.println("  - " + result.getResultSetName() + ":");
                System.out.println("    Chunks: " + chunks);
                System.out.println("    Chunk config: " + result.getChunkConfigId());
                System.out.println("    Embedding config: " + result.getEmbeddingConfigId());
                
                if (chunks > 0) {
                    SemanticChunk firstChunk = result.getChunks(0);
                    int vectorSize = firstChunk.getEmbeddingInfo().getVectorCount();
                    System.out.println("    Vector dimensions: " + vectorSize);
                    
                    // Check metadata
                    String embedder = firstChunk.getMetadataOrDefault("embedder", 
                        com.google.protobuf.Value.getDefaultInstance()).getStringValue();
                    System.out.println("    Embedder: " + embedder);
                }
            }
            
            System.out.println("  Total chunks with embeddings: " + totalChunks);
        }
        
        // Summary
        int totalDocs = finalDocs.size();
        int docsWithBody = (int) finalDocs.stream().filter(d -> d.hasBody() && !d.getBody().isEmpty()).count();
        int totalSemanticResults = finalDocs.stream().mapToInt(PipeDoc::getSemanticResultsCount).sum();
        
        System.out.println("\n=== Summary ===");
        System.out.println("Total documents: " + totalDocs);
        System.out.println("Documents with body: " + docsWithBody);
        System.out.println("Total semantic results: " + totalSemanticResults);
        System.out.println("Expected: " + (docsWithBody * 4) + " (4 semantic results per document with body)");
        
        // We should have 4 semantic results per document with body
        // 2 from chunkers + 2 more from embedder2 (embedder1's results + embedder2's new results)
        assertEquals(docsWithBody * 4, totalSemanticResults, 
            "Should have 4 semantic results per document with body (2 chunk types × 2 embedders)");
        
        // All semantic results should have embeddings
        long resultsWithEmbeddings = finalDocs.stream()
            .flatMap(d -> d.getSemanticResultsList().stream())
            .filter(r -> !r.getEmbeddingConfigId().isEmpty())
            .count();
        
        assertEquals(totalSemanticResults, resultsWithEmbeddings, 
            "All semantic results should have embeddings");
        
        System.out.println("\nThis gives us 4 distinct combinations:");
        System.out.println("1. Sentence chunks + Embedder1 embeddings");
        System.out.println("2. Window chunks + Embedder1 embeddings");
        System.out.println("3. Sentence chunks + Embedder2 embeddings");
        System.out.println("4. Window chunks + Embedder2 embeddings");
    }
}