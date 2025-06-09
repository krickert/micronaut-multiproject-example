package com.krickert.yappy.modules.chunker;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import com.krickert.search.model.util.ProcessingBuffer;
import com.krickert.search.model.util.ProcessingBufferFactory;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ServiceMetadata;
import com.google.protobuf.Struct;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to generate chunker buffer data from Tika output.
 * This will create test data for the chunker validation tests.
 */
@MicronautTest
public class ChunkerBufferDataGeneratorTest {

    @Inject
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerService;

    @TempDir
    Path tempDir;

    @Test
    void generateChunkerDataFromTikaOutput() throws IOException {
        // Load Tika output documents
        Collection<PipeDoc> tikaDocs = ProtobufTestDataHelper.getTikaPipeDocuments();
        System.out.println("Loaded " + tikaDocs.size() + " Tika documents");
        
        // Verify Tika docs have body content
        for (PipeDoc doc : tikaDocs) {
            assertTrue(doc.hasBody(), "Tika doc " + doc.getId() + " should have body");
            assertFalse(doc.getBody().isEmpty(), "Tika doc " + doc.getId() + " body should not be empty");
            System.out.println("Tika doc " + doc.getId() + " has body of length: " + doc.getBody().length());
        }
        
        if (tikaDocs.isEmpty()) {
            fail("No Tika documents found to process. Cannot generate chunker data.");
        }
        
        // Create buffers to capture chunker input/output
        ProcessingBuffer<ProcessRequest> requestBuffer = ProcessingBufferFactory.createBuffer(true, ProcessRequest.class);
        ProcessingBuffer<ProcessResponse> responseBuffer = ProcessingBufferFactory.createBuffer(true, ProcessResponse.class);
        ProcessingBuffer<PipeDoc> pipeDocBuffer = ProcessingBufferFactory.createBuffer(true, PipeDoc.class);
        
        List<PipeDoc> chunkerOutputDocs = new ArrayList<>();
        
        // Process each Tika document through the chunker
        for (PipeDoc tikaDoc : tikaDocs) {
            System.out.println("\nProcessing document: " + tikaDoc.getId());
            
            // Create metadata for this processing step
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName("chunker-test")
                .setStreamId("test-stream-" + tikaDoc.getId())
                .setCurrentHopNumber(1)
                .build();
            
            // Create configuration (empty for now)
            ProcessConfiguration config = ProcessConfiguration.newBuilder()
                .setCustomJsonConfig(Struct.newBuilder().build())
                .build();
            
            // Create request for chunker
            ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(tikaDoc)
                .setConfig(config)
                .setMetadata(metadata)
                .build();
            
            // Add to request buffer
            requestBuffer.add(request);
            
            try {
                // Call chunker service
                ProcessResponse response = chunkerService
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .processData(request);
                
                // Add to response buffer
                responseBuffer.add(response);
                
                // Check if processing was successful
                assertTrue(response.getSuccess(), 
                    "Chunker processing should be successful for doc: " + tikaDoc.getId());
                
                // Get the processed document
                assertTrue(response.hasOutputDoc(), "Response should have output document");
                PipeDoc chunkedDoc = response.getOutputDoc();
                assertNotNull(chunkedDoc, "Chunked document should not be null");
                
                // Add to output buffers
                pipeDocBuffer.add(chunkedDoc);
                chunkerOutputDocs.add(chunkedDoc);
                
                // Verify chunker added semantic results
                assertTrue(chunkedDoc.getSemanticResultsCount() > 0,
                    "Chunker should add semantic results");
                
                System.out.println("Successfully chunked document: " + chunkedDoc.getId());
                System.out.println("Created " + chunkedDoc.getSemanticResultsList().stream()
                    .mapToInt(r -> r.getChunksCount())
                    .sum() + " chunks");
                
            } catch (Exception e) {
                System.err.println("Failed to process document " + tikaDoc.getId() + ": " + e.getMessage());
                fail("Chunker processing failed: " + e.getMessage());
            }
        }
        
        // Save buffers to disk
        System.out.println("\nSaving chunker buffer data...");
        
        // Save to temp directory first
        Path chunkerDir = tempDir.resolve("chunker-buffer-data");
        chunkerDir.toFile().mkdirs();
        
        requestBuffer.saveToDisk(chunkerDir, "chunker-requests", 3);
        responseBuffer.saveToDisk(chunkerDir, "chunker-responses", 3);
        pipeDocBuffer.saveToDisk(chunkerDir, "chunker-pipedocs", 3);
        
        // Also save individual files for easy access
        for (int i = 0; i < chunkerOutputDocs.size(); i++) {
            PipeDoc doc = chunkerOutputDocs.get(i);
            Path docFile = chunkerDir.resolve("chunker_pipe_doc_" + doc.getId() + ".bin");
            try (FileOutputStream fos = new FileOutputStream(docFile.toFile())) {
                doc.writeTo(fos);
            }
        }
        
        System.out.println("Saved " + chunkerOutputDocs.size() + " chunker output documents to: " + chunkerDir);
        System.out.println("\nChunker data generation complete!");
        
        // Print summary
        System.out.println("\n=== Chunker Generation Summary ===");
        System.out.println("Total documents processed: " + chunkerOutputDocs.size());
        int totalChunks = chunkerOutputDocs.stream()
            .flatMap(doc -> doc.getSemanticResultsList().stream())
            .mapToInt(result -> result.getChunksCount())
            .sum();
        System.out.println("Total chunks created: " + totalChunks);
    }
}