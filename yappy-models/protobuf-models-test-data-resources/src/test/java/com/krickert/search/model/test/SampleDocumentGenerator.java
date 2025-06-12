package com.krickert.search.model.test;

import com.krickert.search.model.*;
import com.krickert.search.model.util.TestDataGenerationConfig;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Generates initial sample documents that can be used by other tests.
 * These are basic documents without chunking or embeddings.
 */
public class SampleDocumentGenerator {
    
    @Test
    void generateSampleDocuments() throws IOException {
        System.out.println("=== Sample Document Generation ===");
        System.out.println("Regeneration enabled: " + TestDataGenerationConfig.isRegenerationEnabled());
        System.out.println("Output directory: " + TestDataGenerationConfig.getOutputDirectory());
        System.out.println("Deterministic mode: " + TestDataGenerationConfig.isDeterministicMode());
        
        if (!TestDataGenerationConfig.isRegenerationEnabled()) {
            System.out.println("\nSkipping generation - regeneration is disabled");
            System.out.println("To enable, run with: -Dyappy.test.data.regenerate=true");
            return;
        }
        
        // Determine output directory
        Path outputDir;
        String outputDirProperty = TestDataGenerationConfig.getOutputDirectory();
        if (outputDirProperty.equals(System.getProperty("java.io.tmpdir"))) {
            // Use resources directory for permanent storage
            outputDir = Paths.get(TestDataGenerationConfig.getResourcesDirectory());
        } else {
            outputDir = Paths.get(outputDirProperty);
        }
        
        Path pipeDocsDir = outputDir.resolve("pipe-docs");
        Path pipeStreamsDir = outputDir.resolve("pipe-streams");
        
        Files.createDirectories(pipeDocsDir);
        Files.createDirectories(pipeStreamsDir);
        
        System.out.println("\nGenerating documents to: " + outputDir);
        
        // Generate 99 sample documents
        List<PipeDoc> docs = new ArrayList<>();
        List<PipeStream> streams = new ArrayList<>();
        
        for (int i = 0; i < 99; i++) {
            PipeDoc doc = createSampleDocument(i);
            docs.add(doc);
            
            // Create corresponding stream
            PipeStream stream = createSampleStream(i, doc);
            streams.add(stream);
        }
        
        // Save documents
        saveDocs(docs, pipeDocsDir);
        saveStreams(streams, pipeStreamsDir);
        
        System.out.println("\nGeneration complete!");
        System.out.println("Created " + docs.size() + " documents");
        System.out.println("Created " + streams.size() + " streams");
    }
    
    private PipeDoc createSampleDocument(int index) {
        String sourceUri = "https://example.com/source/" + index;
        String docId = TestDataGenerationConfig.isDeterministicMode() 
            ? "doc-" + UUID.nameUUIDFromBytes(sourceUri.getBytes())
            : "doc-" + UUID.randomUUID();
            
        PipeDoc.Builder builder = PipeDoc.newBuilder()
            .setId(docId)
            .setSourceUri(sourceUri)
            .setSourceMimeType("text/plain")
            .setProcessedDate(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());
        
        // Add varied content based on index
        if (index % 3 == 0) {
            // Technical documents
            builder.setTitle("Technical Document " + index)
                .setBody("This is a technical document about software engineering. " +
                         "It covers topics like design patterns, algorithms, and best practices. " +
                         "The document includes code examples and implementation details. " +
                         "Software architecture is discussed in depth with diagrams and explanations. " +
                         "Performance optimization techniques are covered extensively.")
                .setDocumentType("technical");
            builder.addKeywords("software");
            builder.addKeywords("engineering");
            builder.addKeywords("architecture");
        } else if (index % 3 == 1) {
            // Business documents
            builder.setTitle("Business Report " + index)
                .setBody("This quarterly business report analyzes market trends and financial performance. " +
                         "Revenue has increased by 15% compared to the previous quarter. " +
                         "Customer acquisition costs have been optimized through new marketing strategies. " +
                         "The competitive landscape shows opportunities for expansion in emerging markets. " +
                         "Strategic partnerships have been formed to enhance our market position.")
                .setDocumentType("business");
            builder.addKeywords("business");
            builder.addKeywords("revenue");
            builder.addKeywords("strategy");
        } else {
            // Research papers
            builder.setTitle("Research Paper " + index)
                .setBody("Abstract: This research investigates the effects of machine learning on data processing. " +
                         "The methodology involves experimental analysis with control groups. " +
                         "Results show significant improvements in processing efficiency. " +
                         "The discussion section explores implications for future research. " +
                         "Conclusions suggest new avenues for investigation in this field.")
                .setDocumentType("research");
            builder.addKeywords("research");
            builder.addKeywords("machine learning");
            builder.addKeywords("data processing");
        }
        
        // Add custom data as a Struct
        com.google.protobuf.Struct.Builder customData = com.google.protobuf.Struct.newBuilder();
        customData.putFields("document_type", com.google.protobuf.Value.newBuilder()
            .setStringValue("sample").build());
        customData.putFields("index", com.google.protobuf.Value.newBuilder()
            .setNumberValue(index).build());
        customData.putFields("generated", com.google.protobuf.Value.newBuilder()
            .setBoolValue(true).build());
        builder.setCustomData(customData);
        
        // Add creation and modification dates
        builder.setCreationDate(com.google.protobuf.Timestamp.newBuilder()
            .setSeconds(Instant.now().getEpochSecond())
            .build());
        builder.setLastModifiedDate(com.google.protobuf.Timestamp.newBuilder()
            .setSeconds(Instant.now().getEpochSecond())
            .build());
        
        // Add some documents without body (to test edge cases)
        if (index % 20 == 0 && index > 0) {
            builder.clearBody();
            builder.setTitle("Empty Body Document " + index);
        }
        
        return builder.build();
    }
    
    private PipeStream createSampleStream(int index, PipeDoc doc) {
        String streamId = TestDataGenerationConfig.isDeterministicMode()
            ? "stream-" + UUID.nameUUIDFromBytes(("stream-" + doc.getId()).getBytes())
            : "stream-" + UUID.randomUUID();
            
        return PipeStream.newBuilder()
            .setStreamId(streamId)
            .setDocument(doc)
            .setCurrentPipelineName("sample-pipeline")
            .setTargetStepName("initial")
            .setCurrentHopNumber(0)
            .putContextParams("stream_type", "sample")
            .putContextParams("index", String.valueOf(index))
            .putContextParams("generated_at", Instant.now().toString())
            .build();
    }
    
    private void saveDocs(List<PipeDoc> docs, Path outputDir) throws IOException {
        for (int i = 0; i < docs.size(); i++) {
            PipeDoc doc = docs.get(i);
            String filename = String.format("pipe_doc_%03d_%s.bin", i, doc.getId());
            Path outputPath = outputDir.resolve(filename);
            
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                doc.writeTo(fos);
            }
        }
        System.out.println("Saved " + docs.size() + " documents to " + outputDir);
    }
    
    private void saveStreams(List<PipeStream> streams, Path outputDir) throws IOException {
        for (int i = 0; i < streams.size(); i++) {
            PipeStream stream = streams.get(i);
            String filename = String.format("pipe_stream_%03d_%s.bin", i, stream.getStreamId());
            Path outputPath = outputDir.resolve(filename);
            
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                stream.writeTo(fos);
            }
        }
        System.out.println("Saved " + streams.size() + " streams to " + outputDir);
    }
}