package com.krickert.yappy.modules.embedder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.model.*;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class EmbedderServiceGrpcTest {

    @Inject
    private ObjectMapper objectMapper;

    @Inject
    private EmbedderServiceGrpc embedderService;


    // Helper class to capture StreamObserver events
    private static class TestStreamObserver<T> implements StreamObserver<T> {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private Throwable error;

        @Override
        public void onNext(T value) {
            future.complete(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            future.completeExceptionally(t);
            completed.completeExceptionally(t);
        }

        @Override
        public void onCompleted() {
            completed.complete(null);
        }

        public T getResponse() throws Exception {
            return future.get(5, TimeUnit.SECONDS); // Wait for a reasonable time
        }

        public Throwable getError() {
            return error;
        }

        public boolean isCompleted() {
            return completed.isDone() && !completed.isCompletedExceptionally();
        }

        public void awaitCompletion() throws Exception {
            completed.get(5, TimeUnit.SECONDS); // Wait for completion
        }
    }


    @Test
    public void testProcessDataWithChunks() throws Exception {
        // Create a test document with chunks
        PipeDoc inputDoc = createTestDocumentWithChunks();

        // Create a test request
        ProcessRequest request = createTestRequest(inputDoc);

        // Create a test response observer
        TestStreamObserver<ProcessResponse> responseObserver = new TestStreamObserver<>();

        // Process the request
        embedderService.processData(request, responseObserver);

        // Wait for completion
        responseObserver.awaitCompletion();

        // Get the response
        ProcessResponse response = responseObserver.getResponse();
        assertNull(responseObserver.getError(), "onError should not have been called");
        assertTrue(responseObserver.isCompleted(), "onCompleted should have been called");


        // Verify the response is successful
        assertTrue(response.getSuccess());

        // Verify the output document has embeddings
        PipeDoc outputDoc = response.getOutputDoc();
        assertNotNull(outputDoc);

        // Verify that we have semantic results with embeddings
        assertTrue(outputDoc.getSemanticResultsCount() > 0);

        // Verify that the chunks have embeddings
        SemanticProcessingResult result = outputDoc.getSemanticResults(0);

        // Verify that each chunk has embeddings
        for (SemanticChunk chunk : result.getChunksList()) {
            ChunkEmbedding embedding = chunk.getEmbeddingInfo();
            assertTrue(embedding.getVectorCount() > 0);
        }
        assertEquals(EmbeddingModel.ALL_MINILM_L6_V2.name(), result.getEmbeddingConfigId());

    }

    @Test
    public void testProcessDataWithDocumentFields() throws Exception {
        // Create a test document without chunks but with body and title
        PipeDoc inputDoc = createTestDocumentWithoutChunks();

        // Create a test request
        ProcessRequest request = createTestRequest(inputDoc);

        // Create a test response observer
        TestStreamObserver<ProcessResponse> responseObserver = new TestStreamObserver<>();

        // Process the request
        embedderService.processData(request, responseObserver);

        // Wait for completion
        responseObserver.awaitCompletion();

        // Get the response
        ProcessResponse response = responseObserver.getResponse();
        assertNull(responseObserver.getError(), "onError should not have been called");
        assertTrue(responseObserver.isCompleted(), "onCompleted should have been called");


        // Verify the response is successful
        assertTrue(response.getSuccess());

        // Verify the output document has embeddings
        PipeDoc outputDoc = response.getOutputDoc();
        assertNotNull(outputDoc);

        // Verify that we have named embeddings
        assertTrue(outputDoc.getNamedEmbeddingsCount() > 0);

        // Verify that the body and title have embeddings
        assertTrue(outputDoc.containsNamedEmbeddings("body_all_minilm_l6_v2"));
        assertTrue(outputDoc.containsNamedEmbeddings("title_all_minilm_l6_v2"));

        // Verify that the embeddings have the correct model ID
        assertEquals(EmbeddingModel.ALL_MINILM_L6_V2.name(),
                outputDoc.getNamedEmbeddingsOrThrow("body_all_minilm_l6_v2").getModelId());
        assertEquals(EmbeddingModel.ALL_MINILM_L6_V2.name(),
                outputDoc.getNamedEmbeddingsOrThrow("title_all_minilm_l6_v2").getModelId());
    }

    @Test
    public void testProcessDataWithKeywords() throws Exception {
        // Create a test document with keywords
        PipeDoc inputDoc = createTestDocumentWithKeywords();

        // Create a test request
        ProcessRequest request = createTestRequest(inputDoc);

        // Create a test response observer
        TestStreamObserver<ProcessResponse> responseObserver = new TestStreamObserver<>();

        // Process the request
        embedderService.processData(request, responseObserver);

        // Wait for completion
        responseObserver.awaitCompletion();

        // Get the response
        ProcessResponse response = responseObserver.getResponse();
        assertNull(responseObserver.getError(), "onError should not have been called");
        assertTrue(responseObserver.isCompleted(), "onCompleted should have been called");

        // Verify the response is successful
        assertTrue(response.getSuccess());

        // Verify the output document has embeddings
        PipeDoc outputDoc = response.getOutputDoc();
        assertNotNull(outputDoc);

        // Verify that we have named embeddings for keywords
        assertTrue(outputDoc.containsNamedEmbeddings("keywords_1_all_minilm_l6_v2"));
    }

    private PipeDoc createTestDocumentWithChunks() {
        // Create a document with chunks
        String documentId = UUID.randomUUID().toString();

        // Create chunk embeddings
        ChunkEmbedding chunk1 = ChunkEmbedding.newBuilder()
                .setTextContent("This is the first chunk of text.")
                .setChunkId("chunk1")
                .build();

        ChunkEmbedding chunk2 = ChunkEmbedding.newBuilder()
                .setTextContent("This is the second chunk of text.")
                .setChunkId("chunk2")
                .build();

        // Create semantic chunks
        SemanticChunk semanticChunk1 = SemanticChunk.newBuilder()
                .setChunkId("chunk1")
                .setChunkNumber(0)
                .setEmbeddingInfo(chunk1)
                .build();

        SemanticChunk semanticChunk2 = SemanticChunk.newBuilder()
                .setChunkId("chunk2")
                .setChunkNumber(1)
                .setEmbeddingInfo(chunk2)
                .build();

        // Create semantic processing result
        SemanticProcessingResult result = SemanticProcessingResult.newBuilder()
                .setResultId(UUID.randomUUID().toString())
                .setSourceFieldName("body")
                .setChunkConfigId("test_chunker")
                .addChunks(semanticChunk1)
                .addChunks(semanticChunk2)
                .build();

        // Create the document
        return PipeDoc.newBuilder()
                .setId(documentId)
                .setTitle("Test Document")
                .setBody("This is a test document with chunks.")
                .addSemanticResults(result)
                .build();
    }

    private PipeDoc createTestDocumentWithoutChunks() {
        // Create a document without chunks
        String documentId = UUID.randomUUID().toString();

        // Create the document
        return PipeDoc.newBuilder()
                .setId(documentId)
                .setTitle("Test Document")
                .setBody("This is a test document without chunks.")
                .build();
    }

    private PipeDoc createTestDocumentWithKeywords() {
        // Create a document with keywords
        String documentId = UUID.randomUUID().toString();

        // Create the document
        return PipeDoc.newBuilder()
                .setId(documentId)
                .setTitle("Test Document")
                .setBody("This is a test document with keywords.")
                .addKeywords("test")
                .addKeywords("document")
                .addKeywords("keywords")
                .build();
    }

    private ProcessRequest createTestRequest(PipeDoc inputDoc) throws Exception {
        // Create embedder options
        EmbedderOptions options = new EmbedderOptions();

        // Convert options to JSON
        String optionsJson = objectMapper.writeValueAsString(options);

        // Create custom JSON config
        Struct.Builder customJsonConfigBuilder = Struct.newBuilder();
        JsonFormat.parser().merge(optionsJson, customJsonConfigBuilder);

        // Create process configuration
        ProcessConfiguration config = ProcessConfiguration.newBuilder()
                .setCustomJsonConfig(customJsonConfigBuilder.build())
                .build();

        // Create service metadata
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName("test-embedder")
                .setStreamId(UUID.randomUUID().toString())
                .setCurrentHopNumber(1)
                .build();

        // Create the request
        return ProcessRequest.newBuilder()
                .setDocument(inputDoc)
                .setConfig(config)
                .setMetadata(metadata)
                .build();
    }
}