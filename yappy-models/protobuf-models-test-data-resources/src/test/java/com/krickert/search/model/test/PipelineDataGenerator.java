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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generates test data for the entire pipeline by processing documents through each step.
 * This will create realistic test data for:
 * 1. Tika output analysis
 * 2. Chunker1 processing
 * 3. Chunker2 processing  
 * 4. Embedder1 processing
 * 5. Embedder2 processing
 */
public class PipelineDataGenerator {
    
    private static final Path OUTPUT_BASE = Paths.get("build/pipeline-test-data");
    private static final Path RESOURCE_BASE = Paths.get("src/main/resources/test-data");
    
    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(OUTPUT_BASE);
    }
    
    @Test
    void generateFullPipelineData() throws IOException {
        System.out.println("=== Full Pipeline Data Generation ===\n");
        
        // Step 1: Analyze Tika output
        System.out.println("1. Analyzing Tika Output:");
        List<PipeDoc> tikaDocs = analyzeTikaOutput();
        
        // Step 2: Process through Chunker1
        System.out.println("\n2. Processing through Chunker1:");
        List<PipeDoc> chunker1Docs = processChunker1(tikaDocs);
        saveDocuments(chunker1Docs, "chunker1-output");
        
        // Step 3: Process through Chunker2
        System.out.println("\n3. Processing through Chunker2:");
        List<PipeDoc> chunker2Docs = processChunker2(chunker1Docs);
        saveDocuments(chunker2Docs, "chunker2-output");
        
        // Step 4: Process through Embedder1
        System.out.println("\n4. Processing through Embedder1:");
        List<PipeDoc> embedder1Docs = processEmbedder1(chunker2Docs);
        saveDocuments(embedder1Docs, "embedder1-output");
        
        // Step 5: Process through Embedder2
        System.out.println("\n5. Processing through Embedder2:");
        List<PipeDoc> embedder2Docs = processEmbedder2(embedder1Docs);
        saveDocuments(embedder2Docs, "embedder2-output");
        
        // Final summary
        System.out.println("\n=== Pipeline Summary ===");
        System.out.println("Tika documents: " + tikaDocs.size());
        System.out.println("After Chunker1: " + chunker1Docs.size());
        System.out.println("After Chunker2: " + chunker2Docs.size());
        System.out.println("After Embedder1: " + embedder1Docs.size());
        System.out.println("After Embedder2: " + embedder2Docs.size());
        
        // Validate final output
        validateFinalOutput(embedder2Docs);
    }
    
    private List<PipeDoc> analyzeTikaOutput() throws IOException {
        // Use the sample documents as our "Tika output"
        List<PipeDoc> sampleDocs = ProtobufTestDataHelper.getOrderedSamplePipeDocuments();
        
        System.out.println("  Total documents: " + sampleDocs.size());
        
        // Analyze content
        int withTitle = 0;
        int withBody = 0;
        int totalBodyLength = 0;
        
        for (PipeDoc doc : sampleDocs) {
            if (doc.hasTitle() && !doc.getTitle().isEmpty()) withTitle++;
            if (doc.hasBody() && !doc.getBody().isEmpty()) {
                withBody++;
                totalBodyLength += doc.getBody().length();
            }
        }
        
        System.out.println("  Documents with title: " + withTitle);
        System.out.println("  Documents with body: " + withBody);
        System.out.println("  Average body length: " + (withBody > 0 ? totalBodyLength / withBody : 0));
        
        // Return first 10 for processing
        return sampleDocs.stream().limit(10).collect(Collectors.toList());
    }
    
    private List<PipeDoc> processChunker1(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        
        for (PipeDoc doc : inputDocs) {
            if (!doc.hasBody() || doc.getBody().isEmpty()) {
                outputDocs.add(doc); // Pass through unchanged
                continue;
            }
            
            PipeDoc.Builder builder = doc.toBuilder();
            
            // Chunker1: Simple sentence-based chunking
            SemanticProcessingResult.Builder result = SemanticProcessingResult.newBuilder()
                .setResultId("chunker1-" + UUID.randomUUID())
                .setSourceFieldName("body")
                .setChunkConfigId("sentence-chunker-v1")
                .setResultSetName("sentences");
            
            String body = doc.getBody();
            String[] sentences = body.split("(?<=[.!?])\\s+");
            
            for (int i = 0; i < sentences.length; i++) {
                String sentence = sentences[i].trim();
                if (sentence.isEmpty()) continue;
                
                ChunkEmbedding embedding = ChunkEmbedding.newBuilder()
                    .setTextContent(sentence)
                    .setChunkId("chunk1-" + i)
                    .build();
                
                SemanticChunk chunk = SemanticChunk.newBuilder()
                    .setChunkId("c1-" + doc.getId() + "-" + i)
                    .setChunkNumber(i)
                    .setEmbeddingInfo(embedding)
                    .putMetadata("chunk_size", com.google.protobuf.Value.newBuilder()
                        .setNumberValue(sentence.length()).build())
                    .putMetadata("original_field", com.google.protobuf.Value.newBuilder()
                        .setStringValue("body").build())
                    .putMetadata("sentence_index", com.google.protobuf.Value.newBuilder()
                        .setNumberValue(i).build())
                    .build();
                
                result.addChunks(chunk);
            }
            
            builder.addSemanticResults(result);
            outputDocs.add(builder.build());
        }
        
        System.out.println("  Processed " + outputDocs.size() + " documents");
        System.out.println("  Total chunks created: " + 
            outputDocs.stream().mapToInt(d -> d.getSemanticResultsCount() > 0 ? 
                d.getSemanticResults(0).getChunksCount() : 0).sum());
        
        return outputDocs;
    }
    
    private List<PipeDoc> processChunker2(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        
        for (PipeDoc doc : inputDocs) {
            PipeDoc.Builder builder = doc.toBuilder();
            
            // Chunker2: Sliding window chunking on the original body
            if (doc.hasBody() && !doc.getBody().isEmpty()) {
                SemanticProcessingResult.Builder result = SemanticProcessingResult.newBuilder()
                    .setResultId("chunker2-" + UUID.randomUUID())
                    .setSourceFieldName("body")
                    .setChunkConfigId("sliding-window-v1")
                    .setResultSetName("sliding_windows");
                
                String body = doc.getBody();
                int windowSize = 200;
                int overlap = 50;
                int chunkNum = 0;
                
                for (int i = 0; i < body.length(); i += (windowSize - overlap)) {
                    int end = Math.min(i + windowSize, body.length());
                    String chunkText = body.substring(i, end);
                    
                    ChunkEmbedding embedding = ChunkEmbedding.newBuilder()
                        .setTextContent(chunkText)
                        .setChunkId("chunk2-" + chunkNum)
                        .build();
                    
                    SemanticChunk chunk = SemanticChunk.newBuilder()
                        .setChunkId("c2-" + doc.getId() + "-" + chunkNum)
                        .setChunkNumber(chunkNum)
                        .setEmbeddingInfo(embedding)
                        .putMetadata("chunk_size", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(chunkText.length()).build())
                        .putMetadata("original_field", com.google.protobuf.Value.newBuilder()
                            .setStringValue("body").build())
                        .putMetadata("window_start", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(i).build())
                        .putMetadata("window_end", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(end).build())
                        .build();
                    
                    result.addChunks(chunk);
                    chunkNum++;
                    
                    if (end >= body.length()) break;
                }
                
                builder.addSemanticResults(result);
            }
            
            outputDocs.add(builder.build());
        }
        
        System.out.println("  Processed " + outputDocs.size() + " documents");
        System.out.println("  Now have " + outputDocs.stream()
            .mapToInt(PipeDoc::getSemanticResultsCount).sum() + " semantic results");
        
        return outputDocs;
    }
    
    private List<PipeDoc> processEmbedder1(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        Random random = new Random(42); // Deterministic for testing
        
        for (PipeDoc doc : inputDocs) {
            PipeDoc.Builder builder = doc.toBuilder();
            
            // Process each semantic result
            for (int i = 0; i < doc.getSemanticResultsCount(); i++) {
                SemanticProcessingResult oldResult = doc.getSemanticResults(i);
                SemanticProcessingResult.Builder newResult = oldResult.toBuilder();
                
                // Update with embedding config
                newResult.setEmbeddingConfigId("embedder1-text-embedding-ada-002");
                
                // Add embeddings to each chunk
                for (int j = 0; j < oldResult.getChunksCount(); j++) {
                    SemanticChunk oldChunk = oldResult.getChunks(j);
                    ChunkEmbedding.Builder embedding = oldChunk.getEmbeddingInfo().toBuilder();
                    
                    // Generate fake 384-dimensional embedding
                    for (int k = 0; k < 384; k++) {
                        embedding.addVector(random.nextFloat() * 2 - 1); // [-1, 1]
                    }
                    
                    // Add embedding metadata to the semantic chunk
                    SemanticChunk newChunk = oldChunk.toBuilder()
                        .setEmbeddingInfo(embedding)
                        .putMetadata("model_name", com.google.protobuf.Value.newBuilder()
                            .setStringValue("text-embedding-ada-002").build())
                        .putMetadata("model_version", com.google.protobuf.Value.newBuilder()
                            .setStringValue("1.0").build())
                        .putMetadata("embedding_dim", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(384).build())
                        .build();
                    
                    newResult.setChunks(j, newChunk);
                }
                
                builder.setSemanticResults(i, newResult);
            }
            
            outputDocs.add(builder.build());
        }
        
        System.out.println("  Added embeddings to " + outputDocs.size() + " documents");
        int totalEmbeddings = outputDocs.stream()
            .mapToInt(d -> d.getSemanticResultsList().stream()
                .mapToInt(SemanticProcessingResult::getChunksCount).sum())
            .sum();
        System.out.println("  Total embeddings created: " + totalEmbeddings);
        
        return outputDocs;
    }
    
    private List<PipeDoc> processEmbedder2(List<PipeDoc> inputDocs) {
        List<PipeDoc> outputDocs = new ArrayList<>();
        Random random = new Random(123); // Different seed
        
        for (PipeDoc doc : inputDocs) {
            PipeDoc.Builder builder = doc.toBuilder();
            
            // First, update existing semantic results with new embeddings (embedder2 re-embeds)
            for (int i = 0; i < doc.getSemanticResultsCount(); i++) {
                SemanticProcessingResult oldResult = doc.getSemanticResults(i);
                SemanticProcessingResult.Builder updatedResult = oldResult.toBuilder();
                
                // Create a new embedding config for embedder2's version
                String newEmbeddingConfig = oldResult.getEmbeddingConfigId().replace("embedder1", "embedder2");
                updatedResult.setEmbeddingConfigId(newEmbeddingConfig + "-v2");
                
                // Re-embed each chunk with embedder2
                for (int j = 0; j < oldResult.getChunksCount(); j++) {
                    SemanticChunk oldChunk = oldResult.getChunks(j);
                    ChunkEmbedding.Builder embedding = oldChunk.getEmbeddingInfo().toBuilder();
                    
                    // Clear old vectors and add new ones
                    embedding.clearVector();
                    
                    // Generate fake 768-dimensional embedding (different from embedder1)
                    for (int k = 0; k < 768; k++) {
                        embedding.addVector(random.nextFloat() * 2 - 1);
                    }
                    
                    SemanticChunk newChunk = oldChunk.toBuilder()
                        .setEmbeddingInfo(embedding)
                        .putMetadata("model_name", com.google.protobuf.Value.newBuilder()
                            .setStringValue("embedder2-minilm").build())
                        .putMetadata("model_version", com.google.protobuf.Value.newBuilder()
                            .setStringValue("2.0").build())
                        .putMetadata("embedding_dim", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(768).build())
                        .build();
                    
                    updatedResult.setChunks(j, newChunk);
                }
                
                builder.setSemanticResults(i, updatedResult);
            }
            
            // Then add a new semantic result with different chunking and embeddings
            SemanticProcessingResult.Builder newResult = SemanticProcessingResult.newBuilder()
                .setResultId("embedder2-" + UUID.randomUUID())
                .setSourceFieldName("body")
                .setChunkConfigId("paragraph-chunker-v1")
                .setEmbeddingConfigId("embedder2-bge-large")
                .setResultSetName("paragraph_embeddings");
            
            // Create paragraph-based chunks with embeddings
            if (doc.hasBody() && !doc.getBody().isEmpty()) {
                String[] paragraphs = doc.getBody().split("\n\n+");
                
                for (int i = 0; i < paragraphs.length; i++) {
                    String para = paragraphs[i].trim();
                    if (para.isEmpty()) continue;
                    
                    ChunkEmbedding.Builder embedding = ChunkEmbedding.newBuilder()
                        .setTextContent(para)
                        .setChunkId("embed2-" + i);
                    
                    // Generate fake 1024-dimensional embedding
                    for (int k = 0; k < 1024; k++) {
                        embedding.addVector(random.nextFloat() * 2 - 1);
                    }
                    
                    SemanticChunk chunk = SemanticChunk.newBuilder()
                        .setChunkId("e2-" + doc.getId() + "-" + i)
                        .setChunkNumber(i)
                        .setEmbeddingInfo(embedding)
                        .putMetadata("chunk_size", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(para.length()).build())
                        .putMetadata("original_field", com.google.protobuf.Value.newBuilder()
                            .setStringValue("body").build())
                        .putMetadata("paragraph_index", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(i).build())
                        .putMetadata("model_name", com.google.protobuf.Value.newBuilder()
                            .setStringValue("bge-large-en-v1.5").build())
                        .putMetadata("model_version", com.google.protobuf.Value.newBuilder()
                            .setStringValue("1.5").build())
                        .putMetadata("embedding_dim", com.google.protobuf.Value.newBuilder()
                            .setNumberValue(1024).build())
                        .build();
                    
                    newResult.addChunks(chunk);
                }
            }
            
            if (newResult.getChunksCount() > 0) {
                builder.addSemanticResults(newResult);
            }
            
            outputDocs.add(builder.build());
        }
        
        System.out.println("  Processed " + outputDocs.size() + " documents with second embedder");
        System.out.println("  Documents now have average of " + 
            (outputDocs.stream().mapToInt(PipeDoc::getSemanticResultsCount).sum() / 
             (double) outputDocs.size()) + " semantic results each");
        
        // Count embedding sets
        int totalEmbeddingSets = 0;
        for (PipeDoc doc : outputDocs) {
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                if (!result.getEmbeddingConfigId().isEmpty()) {
                    totalEmbeddingSets++;
                }
            }
        }
        System.out.println("  Total embedding sets: " + totalEmbeddingSets);
        
        return outputDocs;
    }
    
    private void saveDocuments(List<PipeDoc> docs, String stage) throws IOException {
        Path stageDir = OUTPUT_BASE.resolve(stage);
        Files.createDirectories(stageDir);
        
        for (int i = 0; i < docs.size(); i++) {
            PipeDoc doc = docs.get(i);
            String filename = String.format("%s-%03d-%s.bin", stage, i, doc.getId());
            Path outputPath = stageDir.resolve(filename);
            
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                doc.writeTo(fos);
            }
        }
        
        System.out.println("  Saved " + docs.size() + " documents to " + stageDir);
    }
    
    private void validateFinalOutput(List<PipeDoc> finalDocs) {
        System.out.println("\n=== Final Validation ===");
        
        for (PipeDoc doc : finalDocs) {
            System.out.println("\nDocument: " + doc.getId());
            System.out.println("  Semantic results: " + doc.getSemanticResultsCount());
            
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                System.out.println("  - " + result.getResultSetName() + ":");
                System.out.println("    Chunks: " + result.getChunksCount());
                System.out.println("    Config: " + result.getChunkConfigId());
                System.out.println("    Embedding: " + result.getEmbeddingConfigId());
                
                if (result.getChunksCount() > 0) {
                    SemanticChunk firstChunk = result.getChunks(0);
                    int vectorSize = firstChunk.getEmbeddingInfo().getVectorCount();
                    System.out.println("    Vector dimensions: " + vectorSize);
                }
            }
        }
        
        // Verify we have multiple sets of embeddings
        long totalEmbeddingSets = finalDocs.stream()
            .flatMap(d -> d.getSemanticResultsList().stream())
            .filter(r -> !r.getEmbeddingConfigId().isEmpty())
            .count();
        
        System.out.println("\nTotal embedding sets across all documents: " + totalEmbeddingSets);
        System.out.println("Expected: " + (finalDocs.size() * 3) + " (3 per document)");
        
        // Count unique embedding configs
        Set<String> uniqueConfigs = finalDocs.stream()
            .flatMap(d -> d.getSemanticResultsList().stream())
            .map(SemanticProcessingResult::getEmbeddingConfigId)
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toSet());
        
        System.out.println("\nUnique embedding configurations: " + uniqueConfigs.size());
        uniqueConfigs.forEach(config -> System.out.println("  - " + config));
        
        assertTrue(totalEmbeddingSets > 0, "Should have at least one embedding set");
        assertEquals(3, uniqueConfigs.size(), "Should have 3 unique embedding configurations");
    }
}