//package com.krickert.search.config.consul.service;
//
//import com.google.protobuf.Empty;
//import com.google.protobuf.Struct;
//import com.google.protobuf.Value;
//import com.krickert.search.engine.EngineProcessRequest;
//import com.krickert.search.engine.EngineProcessResponse;
//import com.krickert.search.engine.PipeStreamEngineGrpc;
//import com.krickert.search.model.*;
//import io.grpc.stub.StreamObserver;
//import io.micronaut.context.annotation.Requires;
//import io.micronaut.context.env.Environment;
//import io.micronaut.grpc.annotation.GrpcService;
//import jakarta.inject.Singleton;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.*;
//
///**
// * A dummy implementation of the PipeStreamEngine gRPC service for testing registration with Consul.
// * This implementation provides minimal functionality and is intended only for testing purposes.
// */
//@Singleton
//@GrpcService
//@Requires(env = Environment.TEST)
//public class DummyPipelineServiceImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
//
//    private static final Logger LOG = LoggerFactory.getLogger(DummyPipelineServiceImpl.class);
//
//    /**
//     * Processes an EngineProcessRequest and returns an EngineProcessResponse.
//     * This is a dummy implementation that generates realistic dummy data.
//     *
//     * @param request The EngineProcessRequest to process
//     * @param responseObserver The observer to send the response to
//     */
//    @Override
//    public void process(EngineProcessRequest request, StreamObserver<EngineProcessResponse> responseObserver) {
//        LOG.info("Received process request in dummy service for pipeline: {}", request.getPipelineName());
//        LOG.info("Document ID: {}",
//                request.hasInputStream() && request.getInputStream().hasCurrentDoc() ?
//                request.getInputStream().getCurrentDoc().getId() : "null");
//
//        // Create a processed PipeDoc with realistic data
//        PipeDoc processedDoc;
//        if (request.hasInputStream() && request.getInputStream().hasCurrentDoc()) {
//            // Use the input document as a base and enhance it
//            processedDoc = enhancePipeDoc(request.getInputStream().getCurrentDoc());
//        } else {
//            // Create a completely new document
//            processedDoc = createDummyPipeDoc();
//        }
//
//        // Create a PipeStream for the final state
//        PipeStream.Builder finalStreamBuilder = PipeStream.newBuilder();
//
//        // If input stream is provided, use it as a base
//        if (request.hasInputStream()) {
//            finalStreamBuilder.mergeFrom(request.getInputStream());
//        } else {
//            // Create a new stream with basic information
//            finalStreamBuilder.setStreamId(UUID.randomUUID().toString())
//                    .setPipelineName(request.getPipelineName())
//                    .setCurrentHopNumber(1);
//        }
//
//        // Set the current document to our processed document
//        finalStreamBuilder.setCurrentDoc(processedDoc);
//
//        // Add any context parameters from the request
//        for (Map.Entry<String, String> entry : request.getInitialContextParamsMap().entrySet()) {
//            finalStreamBuilder.putContextParams(entry.getKey(), entry.getValue());
//        }
//
//        // Build a success response with the processed document
//        EngineProcessResponse response = EngineProcessResponse.newBuilder()
//                .setOverallSuccess(true)
//                .addEngineLogs("Started pipeline execution")
//                .addEngineLogs("Processed document successfully")
//                .addEngineLogs("Completed pipeline execution")
//                .setFinalStream(finalStreamBuilder.build())
//                .build();
//
//        responseObserver.onNext(response);
//        responseObserver.onCompleted();
//    }
//
//    /**
//     * Processes an EngineProcessRequest asynchronously and returns an Empty response.
//     * This is a dummy implementation that just logs the request and returns an empty response.
//     *
//     * @param request The EngineProcessRequest to process
//     * @param responseObserver The observer to send the response to
//     */
//    @Override
//    public void processAsync(EngineProcessRequest request, StreamObserver<Empty> responseObserver) {
//        LOG.info("Received processAsync request in dummy service for pipeline: {}", request.getPipelineName());
//        LOG.info("Document ID: {}",
//                request.hasInputStream() && request.getInputStream().hasCurrentDoc() ?
//                request.getInputStream().getCurrentDoc().getId() : "null");
//
//        // In a real implementation, we would start the pipeline processing in the background
//        // For this dummy implementation, we just acknowledge receipt
//
//        // Return an empty response to acknowledge the request
//        responseObserver.onNext(Empty.getDefaultInstance());
//        responseObserver.onCompleted();
//    }
//
//    /**
//     * Enhances an existing PipeDoc with additional data.
//     *
//     * @param inputDoc The input PipeDoc to enhance
//     * @return The enhanced PipeDoc
//     */
//    private PipeDoc enhancePipeDoc(PipeDoc inputDoc) {
//        PipeDoc.Builder builder = inputDoc.toBuilder();
//
//        // Update the last modified timestamp
//        builder.setLastModified(ProtobufUtils.now());
//
//        // Add or update keywords
//        List<String> keywords = new ArrayList<>(inputDoc.getKeywordsList());
//        keywords.add("dummy-processed");
//        keywords.add("enhanced");
//        builder.clearKeywords().addAllKeywords(keywords);
//
//        // Add or update custom data
//        Struct.Builder customDataBuilder;
//        if (inputDoc.hasCustomData()) {
//            customDataBuilder = inputDoc.getCustomData().toBuilder();
//        } else {
//            customDataBuilder = Struct.newBuilder();
//        }
//
//        customDataBuilder.putFields("processed_by", Value.newBuilder().setStringValue("DummyPipelineService").build());
//        customDataBuilder.putFields("processed_at", Value.newBuilder().setStringValue(new Date().toString()).build());
//        builder.setCustomData(customDataBuilder.build());
//
//        // Add a semantic embedding if not present
//        if (!inputDoc.hasChunkEmbeddings()) {
//            builder.setChunkEmbeddings(createDummySemanticDoc(inputDoc.getId()));
//        }
//
//        // Add an embedding if not present
//        if (inputDoc.getEmbeddingsCount() == 0) {
//            builder.putEmbeddings("dummy_embedding", createDummyEmbedding());
//        }
//
//        return builder.build();
//    }
//
//    /**
//     * Creates a completely new dummy PipeDoc.
//     *
//     * @return A new dummy PipeDoc
//     */
//    private PipeDoc createDummyPipeDoc() {
//        String docId = UUID.randomUUID().toString();
//
//        // Create custom data
//        Struct customData = Struct.newBuilder()
//                .putFields("source", Value.newBuilder().setStringValue("DummyPipelineService").build())
//                .putFields("created_at", Value.newBuilder().setStringValue(new Date().toString()).build())
//                .putFields("dummy_field", Value.newBuilder().setNumberValue(42.0).build())
//                .build();
//
//        // Create the PipeDoc
//        return PipeDoc.newBuilder()
//                .setId(docId)
//                .setTitle("Dummy Document")
//                .setBody("This is a dummy document created by the DummyPipelineService for testing purposes.")
//                .addKeywords("dummy")
//                .addKeywords("test")
//                .addKeywords("generated")
//                .setDocumentType("dummy")
//                .setRevisionId("1")
//                .setCreationDate(ProtobufUtils.now())
//                .setLastModified(ProtobufUtils.now())
//                .setCustomData(customData)
//                .setChunkEmbeddings(createDummySemanticDoc(docId))
//                .putEmbeddings("document_embedding", createDummyEmbedding())
//                .build();
//    }
//
//    /**
//     * Creates a dummy SemanticDoc.
//     *
//     * @param parentId The parent document ID
//     * @return A new dummy SemanticDoc
//     */
//    private SemanticDoc createDummySemanticDoc(String parentId) {
//        return SemanticDoc.newBuilder()
//                .setParentId(parentId)
//                .setParentField("body")
//                .setChunkConfigId("default_chunk_config")
//                .setSemanticConfigId("default_semantic_config")
//                .addChunks(SemanticChunk.newBuilder()
//                        .setChunkId(UUID.randomUUID().toString())
//                        .setChunkNumber(1)
//                        .setEmbedding(ChunkEmbedding.newBuilder()
//                                .setEmbeddingText("This is a dummy chunk text.")
//                                .addEmbedding(0.1f)
//                                .addEmbedding(0.2f)
//                                .addEmbedding(0.3f)
//                                .build())
//                        .build())
//                .build();
//    }
//
//    /**
//     * Creates a dummy Embedding.
//     *
//     * @return A new dummy Embedding
//     */
//    private Embedding createDummyEmbedding() {
//        // Create a random embedding vector
//        Random random = new Random();
//        Embedding.Builder builder = Embedding.newBuilder();
//        for (int i = 0; i < 10; i++) {
//            builder.addEmbedding(random.nextFloat());
//        }
//        return builder.build();
//    }
//}
