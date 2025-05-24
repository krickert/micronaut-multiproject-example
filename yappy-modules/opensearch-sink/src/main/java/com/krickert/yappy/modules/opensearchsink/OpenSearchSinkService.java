package com.krickert.yappy.modules.opensearchsink;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.engine.SinkServiceGrpc;
import com.krickert.search.engine.SinkTestResponse;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenSearch sink service that implements the SinkService interface.
 * This service converts PipeDoc to JSON and indexes it in OpenSearch.
 */
@Singleton
@GrpcService
@Requires(property = "grpc.services.opensearch-sink.enabled", value = "true", defaultValue = "true")
public class OpenSearchSinkService extends SinkServiceGrpc.SinkServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchSinkService.class);

    @Inject
    private OpenSearchClient openSearchClient;

    /**
     * Processes a PipeStream as a terminal step in the pipeline.
     * Converts the PipeDoc to JSON and indexes it in OpenSearch.
     *
     * @param request          the PipeStream to process
     * @param responseObserver the response observer
     */
    @Override
    public void processSink(PipeStream request, StreamObserver<Empty> responseObserver) {
        String streamId = request.getStreamId();
        PipeDoc document = request.getDocument();
        String docId = document.getId();

        LOG.info("OpenSearchSinkService received request for pipeline: {}, stream ID: {}, document ID: {}",
                request.getCurrentPipelineName(), streamId, docId);

        try {
            // Convert PipeDoc to JSON using Google Protobuf util
            String jsonDocument = convertPipeDocToJson(document);

            // Index the document in OpenSearch
            indexDocument(docId, jsonDocument);

            LOG.info("Successfully indexed document {} in OpenSearch", docId);

            // Return empty response as no further processing is needed
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Error in OpenSearchSinkService: {}", e.getMessage(), e);
            responseObserver.onError(e);
        }
    }

    /**
     * For testing purposes only - allows verification of sink processing without side effects.
     *
     * @param request          the PipeStream to process
     * @param responseObserver the response observer
     */
    @Override
    public void testSink(PipeStream request, StreamObserver<SinkTestResponse> responseObserver) {
        String streamId = request.getStreamId();
        PipeDoc document = request.getDocument();
        String docId = document.getId();

        LOG.info("OpenSearchSinkService received test request for pipeline: {}, stream ID: {}, document ID: {}",
                request.getCurrentPipelineName(), streamId, docId);

        try {
            // Convert PipeDoc to JSON using Google Protobuf util
            String jsonDocument = convertPipeDocToJson(document);

            // Log the JSON document but don't index it
            LOG.info("Test mode: Would index document {} with content: {}", docId, jsonDocument);

            // Return success response
            SinkTestResponse response = SinkTestResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Document would be indexed in OpenSearch")
                    .setStreamId(streamId)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Error in OpenSearchSinkService test: {}", e.getMessage(), e);

            // Return error response
            SinkTestResponse response = SinkTestResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error: " + e.getMessage())
                    .setStreamId(streamId)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    /**
     * Converts a PipeDoc to JSON using Google Protobuf util.
     *
     * @param document the PipeDoc to convert
     * @return the JSON representation of the PipeDoc
     * @throws IOException if the conversion fails
     */
    private String convertPipeDocToJson(PipeDoc document) throws IOException {
        // Create a printer that omits default values and preserves proto field names
        JsonFormat.Printer printer = JsonFormat.printer()
                .omittingInsignificantWhitespace()
                .preservingProtoFieldNames();

        return printer.print(document);
    }

    /**
     * Indexes a document in OpenSearch.
     *
     * @param docId    the document ID
     * @param jsonDocument the JSON document to index
     * @throws IOException if the indexing fails
     */
    private void indexDocument(String docId, String jsonDocument) throws IOException {
        // Create index if it doesn't exist
        ensureIndexExists();

        // Parse JSON document to Map
        Map<String, Object> documentMap = JsonUtils.parseJsonToMap(jsonDocument);

        // Create index request using the builder pattern
        org.opensearch.client.opensearch.core.IndexRequest<Map<String, Object>> indexRequest = 
            new org.opensearch.client.opensearch.core.IndexRequest.Builder<Map<String, Object>>()
                .index("yappy")
                .id(docId)
                .document(documentMap)
                .build();

        // Index the document
        IndexResponse indexResponse = openSearchClient.index(indexRequest);

        LOG.debug("Document indexed with response: {}", indexResponse);
    }

    /**
     * Ensures that the index exists, creating it if necessary.
     *
     * @throws IOException if the operation fails
     */
    private void ensureIndexExists() throws IOException {
        // Check if index exists
        ExistsRequest existsRequest = new ExistsRequest.Builder().index("yappy").build();
        boolean indexExists = openSearchClient.indices().exists(existsRequest).value();

        if (!indexExists) {
            LOG.info("Creating index 'yappy'");
            // Create index using the builder pattern
            CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder().index("yappy").build();
            CreateIndexResponse createResponse = openSearchClient.indices().create(createIndexRequest);
            LOG.debug("Index created with response: {}", createResponse);
        }
    }

    /**
     * Utility class for JSON operations.
     */
    private static class JsonUtils {
        /**
         * Parses a JSON string to a Map.
         *
         * @param json the JSON string to parse
         * @return the parsed Map
         * @throws IOException if parsing fails
         */
        @SuppressWarnings("unchecked")
        public static Map<String, Object> parseJsonToMap(String json) throws IOException {
            // Use Jackson to parse JSON to Map
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, HashMap.class);
        }
    }
}
