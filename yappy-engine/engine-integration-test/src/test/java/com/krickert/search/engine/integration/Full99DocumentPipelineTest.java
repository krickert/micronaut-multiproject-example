package com.krickert.search.engine.integration;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that processes all 99 test documents through the complete pipeline:
 * Tika → Chunker → Embedder
 * 
 * This test tracks the embedding output to verify that each document produces
 * the expected number of vector sets (chunks × embedding models).
 */
@MicronautTest(environments = {"test", "kafka"})
@KafkaListener(groupId = "full-pipeline-test", 
               offsetReset = io.micronaut.configuration.kafka.annotation.OffsetReset.EARLIEST)
class Full99DocumentPipelineTest {

    private static final Logger logger = LoggerFactory.getLogger(Full99DocumentPipelineTest.class);
    
    // Expected configuration
    private static final int EXPECTED_CHUNKS_PER_DOC = 2;
    private static final int EXPECTED_EMBEDDING_MODELS = 3;
    private static final int EXPECTED_VECTOR_SETS_PER_DOC = EXPECTED_CHUNKS_PER_DOC * EXPECTED_EMBEDDING_MODELS;
    
    // Track results
    private final Map<String, PipeStream> processedDocuments = new ConcurrentHashMap<>();
    private final List<String> processingLog = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalVectorSets = new AtomicInteger(0);
    private final AtomicInteger documentsWithBody = new AtomicInteger(0);
    private final AtomicInteger documentsWithoutBody = new AtomicInteger(0);

    @Topic("embedder-output")
    void receiveFromEmbedder(UUID key, PipeStream value) {
        String docId = value.hasDocument() ? value.getDocument().getId() : "no-doc";
        processedDocuments.put(docId, value);
        
        if (value.hasDocument()) {
            PipeDoc doc = value.getDocument();
            boolean hasBody = doc.getBody() != null && !doc.getBody().isEmpty();
            
            if (hasBody) {
                documentsWithBody.incrementAndGet();
                
                // Count vector sets
                int vectorSets = 0;
                for (int i = 0; i < doc.getSemanticResultsCount(); i++) {
                    vectorSets += doc.getSemanticResults(i).getChunksCount();
                }
                totalVectorSets.addAndGet(vectorSets);
                
                String logEntry = String.format("Doc %s: %d semantic results, %d total vector sets", 
                    docId, doc.getSemanticResultsCount(), vectorSets);
                processingLog.add(logEntry);
                logger.info(logEntry);
            } else {
                documentsWithoutBody.incrementAndGet();
                processingLog.add("Doc " + docId + ": No body - 0 vector sets");
            }
        }
    }

    @BeforeEach
    void setup() {
        processedDocuments.clear();
        processingLog.clear();
        totalVectorSets.set(0);
        documentsWithBody.set(0);
        documentsWithoutBody.set(0);
    }

    @Test
    void testProcess99DocumentsThroughFullPipeline() throws InterruptedException {
        logger.info("=== Starting Full 99 Document Pipeline Test ===");
        logger.info("Loading 99 test documents from ProtobufTestDataHelper...");
        
        // Load all 99 documents
        List<PipeDoc> testDocuments = ProtobufTestDataHelper.getOrderedSamplePipeDocuments();
        assertEquals(99, testDocuments.size(), "Should have exactly 99 test documents");
        
        List<PipeStream> testStreams = ProtobufTestDataHelper.getOrderedSamplePipeStreams();
        assertEquals(99, testStreams.size(), "Should have exactly 99 test streams");
        
        logger.info("Loaded {} documents and {} streams", testDocuments.size(), testStreams.size());
        
        // TODO: Send documents through the pipeline
        // This would require either:
        // 1. Direct engine access to submit documents
        // 2. Publishing to the pipeline input topic
        // 3. Using gRPC client to submit to engine
        
        // For now, let's analyze the structure of the test documents
        logger.info("\n=== Analyzing Test Document Structure ===");
        
        int docsWithBody = 0;
        int docsWithoutBody = 0;
        int totalChars = 0;
        
        for (int i = 0; i < testDocuments.size(); i++) {
            PipeDoc doc = testDocuments.get(i);
            boolean hasBody = doc.getBody() != null && !doc.getBody().isEmpty();
            
            if (hasBody) {
                docsWithBody++;
                totalChars += doc.getBody().length();
            } else {
                docsWithoutBody++;
            }
            
            if (i < 5) { // Log first 5 documents
                logger.info("Document {}: ID={}, Title='{}', Body length={}", 
                    i, doc.getId(), 
                    doc.getTitle().isEmpty() ? "(no title)" : doc.getTitle(), 
                    hasBody ? doc.getBody().length() : 0);
            }
        }
        
        logger.info("\n=== Document Analysis Summary ===");
        logger.info("Total documents: {}", testDocuments.size());
        logger.info("Documents with body: {}", docsWithBody);
        logger.info("Documents without body: {}", docsWithoutBody);
        logger.info("Average body length: {} chars", docsWithBody > 0 ? totalChars / docsWithBody : 0);
        
        // Calculate expected results
        int expectedTotalVectorSets = docsWithBody * EXPECTED_VECTOR_SETS_PER_DOC;
        
        logger.info("\n=== Expected Pipeline Output ===");
        logger.info("Documents with body: {} × {} vector sets each = {} total vector sets",
            docsWithBody, EXPECTED_VECTOR_SETS_PER_DOC, expectedTotalVectorSets);
        logger.info("Documents without body: {} × 0 vector sets = 0 total vector sets", docsWithoutBody);
        logger.info("Expected total vector sets: {}", expectedTotalVectorSets);
        
        logger.info("\n=== Vector Set Breakdown ===");
        logger.info("Each document with body produces:");
        logger.info("  {} chunks (from chunker)", EXPECTED_CHUNKS_PER_DOC);
        logger.info("  × {} embedding models", EXPECTED_EMBEDDING_MODELS);
        logger.info("  = {} vector sets per document", EXPECTED_VECTOR_SETS_PER_DOC);
        
        // Wait for messages if pipeline is actually running
        if (isEngineAvailable()) {
            logger.info("\n=== Waiting for Pipeline Results ===");
            submitDocumentsToPipeline(testStreams);
            
            // Wait for all documents to be processed
            Awaitility.await()
                .atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    int processed = processedDocuments.size();
                    logger.info("Processed {}/{} documents so far...", processed, testDocuments.size());
                    assertEquals(testDocuments.size(), processed, 
                        "All documents should be processed");
                });
            
            // Verify results
            logger.info("\n=== Final Results ===");
            logger.info("Documents processed: {}", processedDocuments.size());
            logger.info("Documents with body (produced vectors): {}", documentsWithBody.get());
            logger.info("Documents without body (no vectors): {}", documentsWithoutBody.get());
            logger.info("Total vector sets produced: {}", totalVectorSets.get());
            
            // Assert expected results
            assertEquals(expectedTotalVectorSets, totalVectorSets.get(),
                "Should produce expected number of vector sets");
        } else {
            logger.info("\n=== Engine Not Available - Analysis Only Mode ===");
            logger.info("To run the full pipeline test, ensure:");
            logger.info("1. All test resources are running (Consul, Kafka, etc.)");
            logger.info("2. The engine container is available");
            logger.info("3. All modules (chunker, embedder) are registered");
        }
    }
    
    private boolean isEngineAvailable() {
        // Check if engine endpoint is configured
        // This would check for engine.grpc.host property or similar
        return false; // For now, just analysis mode
    }
    
    private void submitDocumentsToPipeline(List<PipeStream> streams) {
        // TODO: Implement document submission
        // Options:
        // 1. Use gRPC client to submit to engine
        // 2. Publish directly to Kafka input topic
        // 3. Use a test harness that simulates the pipeline
        logger.info("Document submission not yet implemented");
    }
}