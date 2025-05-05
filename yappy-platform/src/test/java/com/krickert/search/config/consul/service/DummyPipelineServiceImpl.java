package com.krickert.search.config.consul.service;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.engine.EngineProcessRequest;
import com.krickert.search.engine.EngineProcessResponse;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * A dummy implementation of the PipelineService gRPC service for testing registration with Consul.
 * This implementation provides minimal functionality and is intended only for testing purposes.
 */
@Singleton
@GrpcService
@Requires(env = Environment.TEST)
public class DummyPipelineServiceImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(DummyPipelineServiceImpl.class);

    /**
     * Processes a PipeStream and returns an Empty response.
     * This is a dummy implementation that just logs the request and returns an empty response.
     * 
     * @param request The PipeStream to process
     * @param responseObserver The observer to send the response to
     */
    @Override
    public void forward(PipeStream request, StreamObserver<Empty> responseObserver) {
        LOG.info("Received forward request in dummy service: {}", request.getPipelineName());

        // Return an empty response
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Processes a ServiceProcessRequest and returns a ServiceProcessRepsonse.
     * This is a dummy implementation that generates realistic dummy data.
     * 
     * @param request The ServiceProcessRequest to process
     * @param responseObserver The observer to send the response to
     */
    @Override
    public void process(EngineProcessRequest request, StreamObserver<EngineProcessResponse> responseObserver) {
        LOG.info("Received process request in dummy service for doc: {}", 
                request.hasInputStream() && request.getInputStream().hasCurrentDoc() ?
                request.getInputStream().getCurrentDoc().getId() : "null");

        // Create a processed PipeDoc with realistic data
        PipeDoc processedDoc;
        if (request.hasInputStream() && request.getInputStream().hasCurrentDoc()) {
            // Use the input document as a base and enhance it
            processedDoc = enhancePipeDoc(request.getInputStream().getCurrentDoc());
        } else {
            // Create a completely new document
            processedDoc = createDummyPipeDoc();
        }

        // Build a success response with the processed document
        EngineProcessResponse response = EngineProcessResponse.newBuilder()
                .setOverallSuccess(true)
                .addEngineLogs("Completed pipeline step!")//TODO add more detail
                .setFinalStream(PipeStream.newBuilder().setCurrentDoc(processedDoc).build())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * Enhances an existing PipeDoc with additional data.
     * 
     * @param inputDoc The input PipeDoc to enhance
     * @return The enhanced PipeDoc
     */
    private PipeDoc enhancePipeDoc(PipeDoc inputDoc) {
        PipeDoc.Builder builder = inputDoc.toBuilder();

        // Update the last modified timestamp
        builder.setLastModified(ProtobufUtils.now());

        // Add or update keywords
        List<String> keywords = new ArrayList<>(inputDoc.getKeywordsList());
        keywords.add("dummy-processed");
        keywords.add("enhanced");
        builder.clearKeywords().addAllKeywords(keywords);

        // Add or update custom data
        Struct.Builder customDataBuilder;
        if (inputDoc.hasCustomData()) {
            customDataBuilder = inputDoc.getCustomData().toBuilder();
        } else {
            customDataBuilder = Struct.newBuilder();
        }

        customDataBuilder.putFields("processed_by", Value.newBuilder().setStringValue("DummyPipelineService").build());
        customDataBuilder.putFields("processed_at", Value.newBuilder().setStringValue(new Date().toString()).build());
        builder.setCustomData(customDataBuilder.build());

        // Add a semantic embedding if not present
        if (!inputDoc.hasChunkEmbeddings()) {
            builder.setChunkEmbeddings(createDummySemanticDoc(inputDoc.getId()));
        }

        // Add an embedding if not present
        if (inputDoc.getEmbeddingsCount() == 0) {
            builder.putEmbeddings("dummy_embedding", createDummyEmbedding());
        }

        return builder.build();
    }

    /**
     * Creates a completely new dummy PipeDoc.
     * 
     * @return A new dummy PipeDoc
     */
    private PipeDoc createDummyPipeDoc() {
        String docId = UUID.randomUUID().toString();

        // Create custom data
        Struct customData = Struct.newBuilder()
                .putFields("source", Value.newBuilder().setStringValue("DummyPipelineService").build())
                .putFields("created_at", Value.newBuilder().setStringValue(new Date().toString()).build())
                .putFields("dummy_field", Value.newBuilder().setNumberValue(42.0).build())
                .build();

        // Create the PipeDoc
        return PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Dummy Document")
                .setBody("This is a dummy document created by the DummyPipelineService for testing purposes.")
                .addKeywords("dummy")
                .addKeywords("test")
                .addKeywords("generated")
                .setDocumentType("dummy")
                .setRevisionId("1")
                .setCreationDate(ProtobufUtils.now())
                .setLastModified(ProtobufUtils.now())
                .setCustomData(customData)
                .setChunkEmbeddings(createDummySemanticDoc(docId))
                .putEmbeddings("document_embedding", createDummyEmbedding())
                .build();
    }

    /**
     * Creates a dummy SemanticDoc.
     * 
     * @param parentId The parent document ID
     * @return A new dummy SemanticDoc
     */
    private SemanticDoc createDummySemanticDoc(String parentId) {
        return SemanticDoc.newBuilder()
                .setParentId(parentId)
                .setParentField("body")
                .setChunkConfigId("default_chunk_config")
                .setSemanticConfigId("default_semantic_config")
                .addChunks(SemanticChunk.newBuilder()
                        .setChunkId(UUID.randomUUID().toString())
                        .setChunkNumber(1)
                        .setEmbedding(ChunkEmbedding.newBuilder()
                                .setEmbeddingText("This is a dummy chunk text.")
                                .addEmbedding(0.1f)
                                .addEmbedding(0.2f)
                                .addEmbedding(0.3f)
                                .build())
                        .build())
                .build();
    }

    /**
     * Creates a dummy Embedding.
     * 
     * @return A new dummy Embedding
     */
    private Embedding createDummyEmbedding() {
        // Create a random embedding vector
        Random random = new Random();
        Embedding.Builder builder = Embedding.newBuilder();
        for (int i = 0; i < 10; i++) {
            builder.addEmbedding(random.nextFloat());
        }
        return builder.build();
    }
}
