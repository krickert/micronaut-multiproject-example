package com.krickert.search.pipeline.integration;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticChunk;
import com.krickert.search.model.SemanticProcessingResult;
import com.krickert.search.sdk.*;
import com.krickert.search.pipeline.integration.util.TestDocumentGenerator;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


@MicronautTest(
        environments = {"test-chunker-grpc-working-standalone"},
        startApplication = true
)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class ChunkerServiceIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ChunkerServiceIntegrationTest.class);

    @Inject
    @Named("chunkerClientStub") // This name should match the bean name from your client factory
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerClient;

    private String pipelineName;
    private String chunkerStepName;
    private String streamIdBase; // Base for streamId to make it unique per document test

    // This list will be populated by the TestDocumentGenerator
    private static List<PipeDoc> sampleDocuments;

    @BeforeEach
    void setUp() {
        pipelineName = "test-pipeline-chunker-integration";
        chunkerStepName = "chunker-integration-step";
        streamIdBase = "stream-" + UUID.randomUUID().toString();

        // Load documents once for all tests in this class, or per test if preferred
        if (sampleDocuments == null) {
            LOG.info("[DEBUG_LOG] Loading sample documents for ChunkerServiceIntegrationTest...");
            sampleDocuments = TestDocumentGenerator.createSampleDocuments();
            LOG.info("[DEBUG_LOG] Loaded {} sample documents.", sampleDocuments.size());
        }
        assertTrue(sampleDocuments.size() > 0, "Should load at least one sample document.");
        LOG.info("[DEBUG_LOG] Test setup complete. Chunker client is injected.");
    }

    // Provides the stream of documents to the parameterized test
    static Stream<PipeDoc> documentProvider() {
        if (sampleDocuments == null) { // Ensure documents are loaded if tests run in parallel/different order
            sampleDocuments = TestDocumentGenerator.createSampleDocuments();
        }
        return sampleDocuments.stream();
    }

    private ProcessRequest createChunkerRequest(PipeDoc document, Struct customConfig, String uniqueStreamIdSuffix) {
        String currentStreamId = streamIdBase + "_" + uniqueStreamIdSuffix;
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(chunkerStepName)
                .setStreamId(currentStreamId)
                .setCurrentHopNumber(1)
                .build();

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customConfig != null) {
            configBuilder.setCustomJsonConfig(customConfig);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setMetadata(metadata)
                .setConfig(configBuilder.build())
                .build();
    }

    private Struct createChunkerConfig(int chunkSize, int chunkOverlap, String chunkConfigId, String sourceField) {
        return Struct.newBuilder()
                .putFields("source_field", Value.newBuilder().setStringValue(sourceField).build())
                .putFields("chunk_size", Value.newBuilder().setNumberValue(chunkSize).build())
                .putFields("chunk_overlap", Value.newBuilder().setNumberValue(chunkOverlap).build())
                .putFields("chunk_id_template", Value.newBuilder().setStringValue("%s_%s_chunk_%d").build()) // Default template
                .putFields("chunk_config_id", Value.newBuilder().setStringValue(chunkConfigId).build())
                .putFields("result_set_name_template", Value.newBuilder().setStringValue(chunkerStepName + "_chunks_" + chunkConfigId).build()) // Example template
                .build();
    }

    @ParameterizedTest
    @MethodSource("documentProvider")
    @DisplayName("Test chunker service with multiple configurations for each document")
    void testChunkerServiceWithMultipleConfigs(PipeDoc document) {
        LOG.info("[DEBUG_LOG] Testing chunker service for document ID: {}", document.getId());

        // Configuration 1: Small chunks
        String configId1 = "small_chunks_250_25";
        Struct chunkerConfig1 = createChunkerConfig(250, 25, configId1, "body");
        ProcessRequest request1 = createChunkerRequest(document, chunkerConfig1, document.getId() + "_config1");

        LOG.info("[DEBUG_LOG] Sending request to chunker service with config: {}", configId1);
        ProcessResponse response1 = chunkerClient.processData(request1);
        LOG.info("[DEBUG_LOG] Received response from chunker service (config: {}). Success: {}", configId1, response1.getSuccess());

        assertNotNull(response1, "Response 1 should not be null");
        assertTrue(response1.getSuccess(), "Processing with config 1 should be successful");
        assertTrue(response1.hasOutputDoc(), "Response 1 should have an output document");
        PipeDoc outputDoc1 = response1.getOutputDoc();

        if (document.getBody() != null && !document.getBody().isEmpty()) {
            assertTrue(outputDoc1.getSemanticResultsCount() > 0, "OutputDoc 1 should have semantic results if body is not empty");
            SemanticProcessingResult result1 = outputDoc1.getSemanticResults(0);
            assertEquals("body", result1.getSourceFieldName(), "Source field name for config 1 should be 'body'");
            assertEquals(configId1, result1.getChunkConfigId(), "ChunkConfigId for config 1 should match");
            assertTrue(result1.getChunksCount() > 0, "Result 1 should have chunks if body is not empty");
            LOG.info("[DEBUG_LOG] Config 1 ('{}') created {} chunks for doc ID: {}", configId1, result1.getChunksCount(), document.getId());
            for (int i = 0; i < Math.min(result1.getChunksCount(), 3); i++) { // Log first few chunks
                SemanticChunk chunk = result1.getChunks(i);
                LOG.debug("  Chunk {} ({}): '{}...'", i, chunk.getChunkId(), chunk.getEmbeddingInfo().getTextContent().substring(0, Math.min(50, chunk.getEmbeddingInfo().getTextContent().length())));
            }
        } else {
            LOG.info("[DEBUG_LOG] Document ID: {} has empty body, expecting no chunks for config 1.", document.getId());
            // Assert that no semantic results are added or that the logs indicate no content to chunk
            assertTrue(response1.getProcessorLogsList().stream().anyMatch(log -> log.contains("No content in 'body' to chunk")),
                    "Expected log message about no content to chunk for empty body with config 1");
        }
        // Verify blob preservation
        if (document.hasBlob()) {
            assertTrue(outputDoc1.hasBlob(), "OutputDoc 1 should preserve blob if input had one");
            assertEquals(document.getBlob(), outputDoc1.getBlob());
        } else {
            assertFalse(outputDoc1.hasBlob(), "OutputDoc 1 should not have blob if input didn't");
        }


        // Configuration 2: Larger chunks
        String configId2 = "large_chunks_1000_100";
        Struct chunkerConfig2 = createChunkerConfig(1000, 100, configId2, "body");
        ProcessRequest request2 = createChunkerRequest(document, chunkerConfig2, document.getId() + "_config2");

        LOG.info("[DEBUG_LOG] Sending request to chunker service with config: {}", configId2);
        ProcessResponse response2 = chunkerClient.processData(request2);
        LOG.info("[DEBUG_LOG] Received response from chunker service (config: {}). Success: {}", configId2, response2.getSuccess());

        assertNotNull(response2, "Response 2 should not be null");
        assertTrue(response2.getSuccess(), "Processing with config 2 should be successful");
        assertTrue(response2.hasOutputDoc(), "Response 2 should have an output document");
        PipeDoc outputDoc2 = response2.getOutputDoc();

        if (document.getBody() != null && !document.getBody().isEmpty()) {
            assertTrue(outputDoc2.getSemanticResultsCount() > 0, "OutputDoc 2 should have semantic results if body is not empty");
            SemanticProcessingResult result2 = outputDoc2.getSemanticResults(0);
            assertEquals("body", result2.getSourceFieldName(), "Source field name for config 2 should be 'body'");
            assertEquals(configId2, result2.getChunkConfigId(), "ChunkConfigId for config 2 should match");
            assertTrue(result2.getChunksCount() > 0, "Result 2 should have chunks if body is not empty");
            LOG.info("[DEBUG_LOG] Config 2 ('{}') created {} chunks for doc ID: {}", configId2, result2.getChunksCount(), document.getId());

            // If the document body is substantial, expect different chunk counts
            if (document.getBody().length() > 1000) { // Arbitrary length to expect differences
                SemanticProcessingResult result1ForCompare = outputDoc1.getSemanticResults(0); // Re-fetch if needed
                assertTrue(result1ForCompare.getChunksCount() >= result2.getChunksCount(),
                        "Smaller chunk size (config1) should generally produce more or equal chunks than larger chunk size (config2) for doc: " + document.getId());
            }
        } else {
            LOG.info("[DEBUG_LOG] Document ID: {} has empty body, expecting no chunks for config 2.", document.getId());
            assertTrue(response2.getProcessorLogsList().stream().anyMatch(log -> log.contains("No content in 'body' to chunk")),
                    "Expected log message about no content to chunk for empty body with config 2");
        }
        // Verify blob preservation
        if (document.hasBlob()) {
            assertTrue(outputDoc2.hasBlob(), "OutputDoc 2 should preserve blob if input had one");
            assertEquals(document.getBlob(), outputDoc2.getBlob());
        } else {
            assertFalse(outputDoc2.hasBlob(), "OutputDoc 2 should not have blob if input didn't");
        }
    }
}