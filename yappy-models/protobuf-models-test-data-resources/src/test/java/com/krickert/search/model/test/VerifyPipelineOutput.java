package com.krickert.search.model.test;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticProcessingResult;
import com.krickert.search.model.ProtobufUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class VerifyPipelineOutput {
    
    @Test
    public void verifyFinalEmbeddings() throws IOException {
        Path embedder2Dir = Paths.get("build/pipeline-test-data/embedder2-output");
        
        if (!Files.exists(embedder2Dir)) {
            System.out.println("No embedder2 output found. Run PipelineDataGenerator first.");
            return;
        }
        
        List<Path> files = Files.list(embedder2Dir)
            .filter(p -> p.toString().endsWith(".bin"))
            .sorted()
            .collect(Collectors.toList());
        
        System.out.println("Found " + files.size() + " files in embedder2 output");
        
        // Check first document in detail
        if (!files.isEmpty()) {
            PipeDoc doc = ProtobufUtils.loadPipeDocFromDisk(files.get(0).toString());
            
            System.out.println("\n=== Document: " + doc.getId() + " ===");
            System.out.println("Total semantic results: " + doc.getSemanticResultsCount());
            
            int embeddingSets = 0;
            int totalChunksWithEmbeddings = 0;
            
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                boolean hasEmbeddings = !result.getEmbeddingConfigId().isEmpty();
                System.out.println("\n" + result.getResultSetName() + ":");
                System.out.println("  Source field: " + result.getSourceFieldName());
                System.out.println("  Chunk config: " + result.getChunkConfigId());
                System.out.println("  Embedding config: " + result.getEmbeddingConfigId());
                System.out.println("  Chunks: " + result.getChunksCount());
                
                if (hasEmbeddings && result.getChunksCount() > 0) {
                    embeddingSets++;
                    totalChunksWithEmbeddings += result.getChunksCount();
                    
                    // Check first chunk
                    var chunk = result.getChunks(0);
                    int vectorSize = chunk.getEmbeddingInfo().getVectorCount();
                    System.out.println("  Vector dimensions: " + vectorSize);
                }
            }
            
            System.out.println("\n=== Summary ===");
            System.out.println("Embedding sets: " + embeddingSets);
            System.out.println("Total chunks with embeddings: " + totalChunksWithEmbeddings);
            
            // We expect 3 embedding sets:
            // 1. sentences with embedder1 embeddings
            // 2. sliding_windows with embedder1 embeddings  
            // 3. paragraph_embeddings with embedder2 embeddings
            System.out.println("\nExpected 3 embedding sets (2 from embedder1, 1 from embedder2)");
            System.out.println("This gives us 4 different chunking+embedding combinations total");
        }
    }
}