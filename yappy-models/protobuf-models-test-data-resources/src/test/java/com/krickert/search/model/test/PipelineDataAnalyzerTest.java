package com.krickert.search.model.test;

import com.krickert.search.model.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PipelineDataAnalyzerTest {
    
    private static final String TEST_DATA_BASE = "src/main/resources/test-data";
    
    @Test
    public void analyzePipelineDataFlow() throws IOException {
        System.out.println("=== Pipeline Data Flow Analysis ===\n");
        
        // Analyze Tika output
        System.out.println("1. TIKA OUTPUT ANALYSIS");
        System.out.println("------------------------");
        analyzeTikaOutput();
        
        // Analyze Chunker output
        System.out.println("\n2. CHUNKER OUTPUT ANALYSIS");
        System.out.println("---------------------------");
        analyzeChunkerOutput();
        
        // Analyze Embedder output
        System.out.println("\n3. EMBEDDER OUTPUT ANALYSIS");
        System.out.println("----------------------------");
        analyzeEmbedderOutput();
        
        // Check data flow
        System.out.println("\n4. DATA FLOW VALIDATION");
        System.out.println("------------------------");
        validateDataFlow();
    }
    
    private void analyzeTikaOutput() throws IOException {
        String tikaDir = TEST_DATA_BASE + "/tika-pipe-docs";
        List<PipeDoc> tikaDocs = ProtobufUtils.loadPipeDocsFromDirectory(tikaDir, ".bin");
        
        System.out.println("Total Tika documents found: " + tikaDocs.size());
        
        for (int i = 0; i < tikaDocs.size(); i++) {
            PipeDoc doc = tikaDocs.get(i);
            System.out.println("\nTika Document " + (i + 1) + ":");
            System.out.println("  ID: " + doc.getId());
            System.out.println("  Title: " + (doc.hasTitle() ? doc.getTitle() : "[NULL/EMPTY]"));
            System.out.println("  Body length: " + (doc.hasBody() ? doc.getBody().length() : 0));
            System.out.println("  Body preview: " + (doc.hasBody() && doc.getBody().length() > 0 ? 
                doc.getBody().substring(0, Math.min(100, doc.getBody().length())) + "..." : "[NULL/EMPTY]"));
            System.out.println("  Source URI: " + (doc.hasSourceUri() ? doc.getSourceUri() : "[NULL/EMPTY]"));
            System.out.println("  Source MIME type: " + (doc.hasSourceMimeType() ? doc.getSourceMimeType() : "[NULL/EMPTY]"));
            System.out.println("  Keywords: " + doc.getKeywordsList());
            System.out.println("  Custom data fields: " + (doc.hasCustomData() ? doc.getCustomData().getFieldsMap().keySet() : "[NULL/EMPTY]"));
            System.out.println("  Has blob: " + doc.hasBlob());
            if (doc.hasBlob()) {
                System.out.println("    Blob size: " + doc.getBlob().getData().size() + " bytes");
                System.out.println("    Blob MIME type: " + (doc.getBlob().hasMimeType() ? doc.getBlob().getMimeType() : "[NULL/EMPTY]"));
            }
            
            // Check for null/empty critical fields
            List<String> issues = new ArrayList<>();
            if (doc.getId().isEmpty()) issues.add("Missing ID");
            if (!doc.hasBody() || doc.getBody().isEmpty()) issues.add("Missing/empty body");
            if (!doc.hasTitle() || doc.getTitle().isEmpty()) issues.add("Missing/empty title");
            
            if (!issues.isEmpty()) {
                System.out.println("  ⚠️  ISSUES: " + String.join(", ", issues));
            }
        }
    }
    
    private void analyzeChunkerOutput() throws IOException {
        String chunkerDir = TEST_DATA_BASE + "/chunker-pipe-docs";
        List<PipeDoc> chunkerDocs = ProtobufUtils.loadPipeDocsFromDirectory(chunkerDir, ".bin");
        
        System.out.println("Total Chunker documents found: " + chunkerDocs.size());
        
        for (int i = 0; i < chunkerDocs.size(); i++) {
            PipeDoc doc = chunkerDocs.get(i);
            System.out.println("\nChunker Document " + (i + 1) + ":");
            System.out.println("  ID: " + doc.getId());
            System.out.println("  Title: " + (doc.hasTitle() ? doc.getTitle() : "[NULL/EMPTY]"));
            System.out.println("  Body length: " + (doc.hasBody() ? doc.getBody().length() : 0));
            System.out.println("  Semantic results count: " + doc.getSemanticResultsCount());
            
            // Analyze semantic processing results
            for (int j = 0; j < doc.getSemanticResultsCount(); j++) {
                SemanticProcessingResult result = doc.getSemanticResults(j);
                System.out.println("  Semantic Result " + (j + 1) + ":");
                System.out.println("    Result ID: " + result.getResultId());
                System.out.println("    Source field: " + result.getSourceFieldName());
                System.out.println("    Chunk config ID: " + result.getChunkConfigId());
                System.out.println("    Embedding config ID: " + result.getEmbeddingConfigId());
                System.out.println("    Result set name: " + (result.hasResultSetName() ? result.getResultSetName() : "[NULL/EMPTY]"));
                System.out.println("    Number of chunks: " + result.getChunksCount());
                
                // Check first few chunks
                for (int k = 0; k < Math.min(3, result.getChunksCount()); k++) {
                    SemanticChunk chunk = result.getChunks(k);
                    System.out.println("      Chunk " + (k + 1) + ":");
                    System.out.println("        Chunk ID: " + chunk.getChunkId());
                    System.out.println("        Chunk number: " + chunk.getChunkNumber());
                    System.out.println("        Text length: " + chunk.getEmbeddingInfo().getTextContent().length());
                    System.out.println("        Text preview: " + chunk.getEmbeddingInfo().getTextContent().substring(0, 
                        Math.min(50, chunk.getEmbeddingInfo().getTextContent().length())) + "...");
                    System.out.println("        Has vector: " + (chunk.getEmbeddingInfo().getVectorCount() > 0));
                    System.out.println("        Vector size: " + chunk.getEmbeddingInfo().getVectorCount());
                }
            }
            
            // Check for issues
            List<String> issues = new ArrayList<>();
            if (doc.getSemanticResultsCount() == 0) issues.add("No semantic results");
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                if (result.getChunksCount() == 0) issues.add("Semantic result with no chunks");
                for (SemanticChunk chunk : result.getChunksList()) {
                    if (chunk.getEmbeddingInfo().getTextContent().isEmpty()) {
                        issues.add("Empty chunk text");
                    }
                }
            }
            
            if (!issues.isEmpty()) {
                System.out.println("  ⚠️  ISSUES: " + String.join(", ", issues));
            }
        }
    }
    
    private void analyzeEmbedderOutput() throws IOException {
        String embedderDir = TEST_DATA_BASE + "/embedder-pipe-docs";
        List<PipeDoc> embedderDocs = ProtobufUtils.loadPipeDocsFromDirectory(embedderDir, ".bin");
        
        System.out.println("Total Embedder documents found: " + embedderDocs.size());
        
        for (int i = 0; i < embedderDocs.size(); i++) {
            PipeDoc doc = embedderDocs.get(i);
            System.out.println("\nEmbedder Document " + (i + 1) + ":");
            System.out.println("  ID: " + doc.getId());
            System.out.println("  Title: " + (doc.hasTitle() ? doc.getTitle() : "[NULL/EMPTY]"));
            System.out.println("  Semantic results count: " + doc.getSemanticResultsCount());
            System.out.println("  Named embeddings count: " + doc.getNamedEmbeddingsCount());
            
            // Check semantic results for embeddings
            int totalChunksWithEmbeddings = 0;
            int totalChunksWithoutEmbeddings = 0;
            
            for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
                System.out.println("  Semantic Result:");
                System.out.println("    Embedding config ID: " + result.getEmbeddingConfigId());
                System.out.println("    Chunks count: " + result.getChunksCount());
                
                for (SemanticChunk chunk : result.getChunksList()) {
                    if (chunk.getEmbeddingInfo().getVectorCount() > 0) {
                        totalChunksWithEmbeddings++;
                    } else {
                        totalChunksWithoutEmbeddings++;
                    }
                }
            }
            
            System.out.println("  Total chunks with embeddings: " + totalChunksWithEmbeddings);
            System.out.println("  Total chunks without embeddings: " + totalChunksWithoutEmbeddings);
            
            // Check named embeddings
            for (Map.Entry<String, Embedding> entry : doc.getNamedEmbeddingsMap().entrySet()) {
                System.out.println("  Named embedding: " + entry.getKey());
                System.out.println("    Vector size: " + entry.getValue().getVectorCount());
                System.out.println("    Model ID: " + (entry.getValue().hasModelId() ? entry.getValue().getModelId() : "[NULL/EMPTY]"));
            }
            
            // Check for issues
            List<String> issues = new ArrayList<>();
            if (totalChunksWithEmbeddings == 0 && doc.getNamedEmbeddingsCount() == 0) {
                issues.add("No embeddings found");
            }
            if (totalChunksWithoutEmbeddings > 0) {
                issues.add(totalChunksWithoutEmbeddings + " chunks without embeddings");
            }
            
            if (!issues.isEmpty()) {
                System.out.println("  ⚠️  ISSUES: " + String.join(", ", issues));
            }
        }
    }
    
    private void validateDataFlow() throws IOException {
        System.out.println("Checking data flow consistency...\n");
        
        // Load all documents
        List<PipeDoc> tikaDocs = ProtobufUtils.loadPipeDocsFromDirectory(TEST_DATA_BASE + "/tika-pipe-docs", ".bin");
        List<PipeDoc> chunkerDocs = ProtobufUtils.loadPipeDocsFromDirectory(TEST_DATA_BASE + "/chunker-pipe-docs", ".bin");
        List<PipeDoc> embedderDocs = ProtobufUtils.loadPipeDocsFromDirectory(TEST_DATA_BASE + "/embedder-pipe-docs", ".bin");
        
        // Create ID maps
        Map<String, PipeDoc> tikaMap = new HashMap<>();
        Map<String, PipeDoc> chunkerMap = new HashMap<>();
        Map<String, PipeDoc> embedderMap = new HashMap<>();
        
        tikaDocs.forEach(doc -> tikaMap.put(doc.getId(), doc));
        chunkerDocs.forEach(doc -> chunkerMap.put(doc.getId(), doc));
        embedderDocs.forEach(doc -> embedderMap.put(doc.getId(), doc));
        
        System.out.println("Document count by stage:");
        System.out.println("  Tika: " + tikaMap.size());
        System.out.println("  Chunker: " + chunkerMap.size());
        System.out.println("  Embedder: " + embedderMap.size());
        
        // Check document flow
        System.out.println("\nDocument flow analysis:");
        
        // Check Tika -> Chunker flow
        System.out.println("\nTika -> Chunker:");
        for (String tikaId : tikaMap.keySet()) {
            if (!chunkerMap.containsKey(tikaId)) {
                System.out.println("  ⚠️  Document " + tikaId + " from Tika not found in Chunker output");
            } else {
                PipeDoc tikaDoc = tikaMap.get(tikaId);
                PipeDoc chunkerDoc = chunkerMap.get(tikaId);
                
                // Check if body content is preserved
                if (tikaDoc.hasBody() && (!chunkerDoc.hasBody() || !tikaDoc.getBody().equals(chunkerDoc.getBody()))) {
                    System.out.println("  ⚠️  Document " + tikaId + " body content changed between Tika and Chunker");
                }
                
                // Check if chunking was performed
                if (chunkerDoc.getSemanticResultsCount() == 0) {
                    System.out.println("  ⚠️  Document " + tikaId + " has no chunks after chunker");
                }
            }
        }
        
        // Check Chunker -> Embedder flow
        System.out.println("\nChunker -> Embedder:");
        for (String chunkerId : chunkerMap.keySet()) {
            if (!embedderMap.containsKey(chunkerId)) {
                System.out.println("  ⚠️  Document " + chunkerId + " from Chunker not found in Embedder output");
            } else {
                PipeDoc chunkerDoc = chunkerMap.get(chunkerId);
                PipeDoc embedderDoc = embedderMap.get(chunkerId);
                
                // Check if chunks are preserved and embeddings added
                if (chunkerDoc.getSemanticResultsCount() != embedderDoc.getSemanticResultsCount()) {
                    System.out.println("  ⚠️  Document " + chunkerId + " semantic results count mismatch: " +
                        "Chunker=" + chunkerDoc.getSemanticResultsCount() + ", Embedder=" + embedderDoc.getSemanticResultsCount());
                }
                
                // Check if embeddings were added
                boolean hasEmbeddings = false;
                for (SemanticProcessingResult result : embedderDoc.getSemanticResultsList()) {
                    for (SemanticChunk chunk : result.getChunksList()) {
                        if (chunk.getEmbeddingInfo().getVectorCount() > 0) {
                            hasEmbeddings = true;
                            break;
                        }
                    }
                }
                
                if (!hasEmbeddings && embedderDoc.getNamedEmbeddingsCount() == 0) {
                    System.out.println("  ⚠️  Document " + chunkerId + " has no embeddings after embedder");
                }
            }
        }
        
        // Summary
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Documents lost between Tika and Chunker: " + (tikaMap.size() - chunkerMap.size()));
        System.out.println("Documents lost between Chunker and Embedder: " + (chunkerMap.size() - embedderMap.size()));
        
        if (embedderMap.size() < tikaMap.size()) {
            System.out.println("\n⚠️  CRITICAL: Only " + embedderMap.size() + " out of " + tikaMap.size() + 
                " documents made it through the entire pipeline!");
        }
    }
}