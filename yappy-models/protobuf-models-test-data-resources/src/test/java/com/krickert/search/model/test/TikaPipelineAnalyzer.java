package com.krickert.search.model.test;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import com.krickert.search.model.ProtobufUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Analyzes Tika output documents to understand what the chunker will receive
 */
public class TikaPipelineAnalyzer {
    
    @Test
    public void analyzeTikaPipeline() throws IOException {
        System.out.println("=== Tika Pipeline Document Analysis ===\n");
        
        // First analyze the sample documents that Tika will process
        System.out.println("1. Analyzing Sample Documents (Tika Input):");
        analyzeSampleDocuments();
        
        // Then analyze the Tika output
        System.out.println("\n2. Analyzing Tika Output Documents:");
        analyzeTikaOutput();
    }
    
    private static void analyzeSampleDocuments() {
        Collection<PipeDoc> sampleDocs = ProtobufTestDataHelper.getSamplePipeDocuments();
        System.out.println("Total sample documents: " + sampleDocs.size());
        
        int withTitle = 0;
        int withBody = 0;
        int withBoth = 0;
        int withNeither = 0;
        
        for (PipeDoc doc : sampleDocs) {
            boolean hasTitle = doc.hasTitle() && !doc.getTitle().isEmpty();
            boolean hasBody = doc.hasBody() && !doc.getBody().isEmpty();
            
            if (hasTitle) withTitle++;
            if (hasBody) withBody++;
            if (hasTitle && hasBody) withBoth++;
            if (!hasTitle && !hasBody) withNeither++;
        }
        
        System.out.println("  - Documents with title: " + withTitle);
        System.out.println("  - Documents with body: " + withBody);
        System.out.println("  - Documents with both: " + withBoth);
        System.out.println("  - Documents with neither: " + withNeither);
    }
    
    private static void analyzeTikaOutput() throws IOException {
        Path tikaDir = Paths.get("/Users/krickert/IdeaProjects/yappy-work/yappy-models/protobuf-models-test-data-resources/src/main/resources/test-data/tika-pipe-docs-large");
        
        if (!Files.exists(tikaDir)) {
            System.err.println("Tika output directory not found: " + tikaDir);
            return;
        }
        
        List<PipeDoc> tikaDocs = new ArrayList<>();
        
        // Load all Tika documents
        try (Stream<Path> paths = Files.list(tikaDir)) {
            List<Path> binFiles = paths
                .filter(path -> path.toString().endsWith(".bin"))
                .sorted()
                .collect(Collectors.toList());
                
            System.out.println("Found " + binFiles.size() + " Tika output files");
            
            for (Path file : binFiles) {
                try {
                    PipeDoc doc = ProtobufUtils.loadPipeDocFromDisk(file.toString());
                    tikaDocs.add(doc);
                } catch (IOException e) {
                    System.err.println("Error loading " + file.getFileName() + ": " + e.getMessage());
                }
            }
        }
        
        System.out.println("Successfully loaded " + tikaDocs.size() + " Tika documents");
        
        // Analyze the documents
        int withTitle = 0;
        int withBody = 0;
        int withBoth = 0;
        int withNeither = 0;
        long totalBodyLength = 0;
        int docsWithContent = 0;
        
        Map<String, Integer> contentTypes = new HashMap<>();
        List<String> emptyDocs = new ArrayList<>();
        
        for (PipeDoc doc : tikaDocs) {
            boolean hasTitle = doc.hasTitle() && !doc.getTitle().isEmpty();
            boolean hasBody = doc.hasBody() && !doc.getBody().isEmpty();
            
            if (hasTitle) withTitle++;
            if (hasBody) {
                withBody++;
                totalBodyLength += doc.getBody().length();
                docsWithContent++;
            }
            if (hasTitle && hasBody) withBoth++;
            if (!hasTitle && !hasBody) {
                withNeither++;
                emptyDocs.add(doc.getId());
            }
            
            // Check content type
            if (doc.hasCustomData() && doc.getCustomData().getFieldsMap().containsKey("content_type")) {
                String contentType = doc.getCustomData().getFieldsMap().get("content_type").getStringValue();
                contentTypes.merge(contentType, 1, Integer::sum);
            }
        }
        
        System.out.println("\nDocument Statistics:");
        System.out.println("  - Documents with title: " + withTitle + " (" + (withTitle * 100.0 / tikaDocs.size()) + "%)");
        System.out.println("  - Documents with body: " + withBody + " (" + (withBody * 100.0 / tikaDocs.size()) + "%)");
        System.out.println("  - Documents with both: " + withBoth + " (" + (withBoth * 100.0 / tikaDocs.size()) + "%)");
        System.out.println("  - Documents with neither: " + withNeither + " (" + (withNeither * 100.0 / tikaDocs.size()) + "%)");
        
        if (docsWithContent > 0) {
            System.out.println("\nContent Statistics:");
            System.out.println("  - Average body length: " + (totalBodyLength / docsWithContent) + " characters");
            System.out.println("  - Total content size: " + totalBodyLength + " characters");
        }
        
        if (!contentTypes.isEmpty()) {
            System.out.println("\nContent Types:");
            contentTypes.forEach((type, count) -> 
                System.out.println("  - " + type + ": " + count));
        }
        
        if (!emptyDocs.isEmpty()) {
            System.out.println("\nEmpty documents (first 5):");
            emptyDocs.stream().limit(5).forEach(id -> 
                System.out.println("  - " + id));
        }
        
        // Sample some documents with content
        System.out.println("\nSample documents with content:");
        tikaDocs.stream()
            .filter(doc -> doc.hasBody() && !doc.getBody().isEmpty())
            .limit(3)
            .forEach(doc -> {
                System.out.println("\n  Document: " + doc.getId());
                if (doc.hasTitle()) {
                    System.out.println("    Title: " + doc.getTitle());
                }
                String body = doc.getBody();
                if (body.length() > 200) {
                    body = body.substring(0, 200) + "...";
                }
                System.out.println("    Body preview: " + body);
            });
    }
}