package com.krickert.yappy.modules.opensearchsink;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.engine.SinkServiceGrpc;
import com.krickert.search.engine.SinkTestResponse;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.yappy.modules.opensearchsink.config.OpenSearchSinkConfig;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    private RestHighLevelClient openSearchClient;

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
        
        // Create index request
        IndexRequest indexRequest = new IndexRequest("yappy")
                .id(docId)
                .source(jsonDocument, XContentType.JSON);
        
        // Index the document
        IndexResponse indexResponse = openSearchClient.index(indexRequest, RequestOptions.DEFAULT);
        
        LOG.debug("Document indexed with response: {}", indexResponse);
    }

    /**
     * Ensures that the index exists, creating it if necessary.
     *
     * @throws IOException if the operation fails
     */
    private void ensureIndexExists() throws IOException {
        GetIndexRequest getIndexRequest = new GetIndexRequest("yappy");
        boolean indexExists = openSearchClient.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
        
        if (!indexExists) {
            LOG.info("Creating index 'yappy'");
            CreateIndexRequest createIndexRequest = new CreateIndexRequest("yappy");
            openSearchClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
        }
    }
}