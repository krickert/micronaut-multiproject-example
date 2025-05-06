package com.krickert.search.pipeline.protobuf;

import com.google.protobuf.ByteString; // Import ByteString
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit; // For time manipulation
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID; // For generating unique IDs

public class PipeDocExample {

    // Helper method to create Timestamps easily from Instant
    private static Timestamp instantToTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.newBuilder().build(); // Or handle as error
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    // createFullPipeDoc remains largely the same as PipeDoc structure didn't change
    public static PipeDoc createFullPipeDoc(String id, String title) {
        // Create a Timestamp for creation_date and last_modified
        Instant now = Instant.now();
        Timestamp creationDate = instantToTimestamp(now);
        Timestamp lastModified = instantToTimestamp(now.plusSeconds(3600)); // 1 hour later

        // Create a custom_data Struct with multiple fields.
        Struct customData = Struct.newBuilder()
                .putFields("field1", Value.newBuilder().setStringValue("value1_" + id).build())
                .putFields("field2", Value.newBuilder().setNumberValue(123.45).build())
                .putFields("field3", Value.newBuilder().setBoolValue(true).build())
                .putFields("nested_struct", Value.newBuilder().setStructValue(
                        Struct.newBuilder()
                                .putFields("nested_field", Value.newBuilder().setStringValue("nested_value").build())
                ).build()) // At least 2 fields (actually 4 here)
                .build();

        // Create ChunkEmbedding instances
        ChunkEmbedding chunkEmbedding1 = ChunkEmbedding.newBuilder()
                .setEmbeddingText("This is the first semantic chunk for " + id)
                .addAllEmbedding(List.of(0.1f, 0.2f, 0.3f))
                .build();

        ChunkEmbedding chunkEmbedding2 = ChunkEmbedding.newBuilder()
                .setEmbeddingText("This is the second semantic chunk for " + id)
                .addAllEmbedding(List.of(0.4f, 0.5f, 0.6f))
                .build();

        // Create SemanticChunk instances
        SemanticChunk semanticChunk1 = SemanticChunk.newBuilder()
                .setChunkId("chunk1_" + id)
                .setChunkNumber(1)
                .setEmbedding(chunkEmbedding1)
                .build();

        SemanticChunk semanticChunk2 = SemanticChunk.newBuilder()
                .setChunkId("chunk2_" + id)
                .setChunkNumber(2)
                .setEmbedding(chunkEmbedding2)
                .build();

        // Create SemanticDoc instances
        SemanticDoc semanticDoc1 = SemanticDoc.newBuilder()
                .setParentId("parent1_" + id)
                .setParentField("body")
                .setChunkConfigId("config1")
                .setSemanticConfigId("semantic1")
                .addChunks(semanticChunk1)
                .addChunks(semanticChunk2) // At least two chunks
                .build();

        // Create Embedding instances
        Embedding embedding1 = Embedding.newBuilder()
                .addAllEmbedding(List.of(0.7f, 0.8f, 0.9f))
                .build();

        Embedding embedding2 = Embedding.newBuilder()
                .addAllEmbedding(List.of(0.4f, 0.5f, 0.6f))
                .build();

        // Build the PipeDoc with all fields populated.
        PipeDoc.Builder builder = PipeDoc.newBuilder()
                .setId(id)
                .setTitle(title)
                .setBody("This is the body for " + id)
                .addAllKeywords(List.of("keyword1", "keyword2", "keyword3")) // More than two keywords
                .setDocumentType("example-type")
                .setRevisionId("rev-001-" + id)
                .setCreationDate(creationDate)
                .setLastModified(lastModified)
                .setCustomData(customData);

        // Set chunk_embeddings (assuming it's a single SemanticDoc message, using semanticDoc1 which has 2 chunks)
        builder.setChunkEmbeddings(semanticDoc1);

        // Set embeddings as a map (at least two entries)
        Map<String, Embedding> embeddings = new HashMap<>();
        embeddings.put("embedding_key1", embedding1);
        embeddings.put("embedding_key2", embedding2);
        builder.putAllEmbeddings(embeddings); // Use putAllEmbeddings for the map

        return builder.build();
    }

    public static PipeStream createFullPipeStream() {
        Instant startTime = Instant.now();

        // 1. Create a PipeDoc
        PipeDoc pipeDoc = createFullPipeDoc("pipeDocStream456", "PipeDoc within Real Stream");

        // 2. Create Routes (used in ErrorData and potentially History)
        Route routeKafka = Route.newBuilder()
                .setRouteType(RouteType.KAFKA)
                .setDestination("output-topic-kafka")
                .build();
        Route routeGrpc = Route.newBuilder()
                .setRouteType(RouteType.GRPC)
                .setDestination("downstream-service-grpc-tag")
                .build();
        Route routeNull = Route.newBuilder() // Example route for another failure
                .setRouteType(RouteType.NULL_TERMINATION)
                .setDestination("end-of-pipeline")
                .build();

        // 3. Create a PipeRequest (used in ErrorData)
        Map<String, String> requestConfig = new HashMap<>();
        requestConfig.put("config_key_1", "config_value_1");
        requestConfig.put("retry_policy", "exponential_backoff"); // At least two config entries

        PipeRequest pipeRequestForError = PipeRequest.newBuilder()
                .setDoc(pipeDoc) // Document state *before* the failed step
                .putAllConfig(requestConfig)
                .build();

        // 4. Create ErrorData for a failed history entry
        ErrorData errorDetails = ErrorData.newBuilder()
                .setErrorMessage("Failed to connect to downstream gRPC service")
                .addFailedRoutes(routeGrpc) // Indicate which route failed
                .addFailedRoutes(routeNull) // Add another failed route example
                .setErrorRequestState(pipeRequestForError) // Include the request that caused the error
                .setErrorCode("GRPC_CONNECTION_ERROR") // Machine-readable code
                .setTechnicalDetails("Connection refused: localhost:50051") // More tech details
                .build();

        // 5. Create Stream Logs (to be used within HistoryEntry)
        List<String> step1Logs = Arrays.asList(
                "INFO: Received request corrId=xyz-123",
                "DEBUG: Applying transformation X"
        );
        List<String> step2Logs = Arrays.asList(
                "WARN: Rate limit approaching for GRPC endpoint",
                "ERROR: Failed delivery attempt 1 to gRPC: downstream-service-grpc-tag"
        );


        // 6. Create History Entries (at least two)
        HistoryEntry historyEntry1 = HistoryEntry.newBuilder()
                .setHopNumber(0)
                .setStepName("Initial Data Load")
                .setServiceInstanceId("data-loader-instance-1")
                .setStartTime(instantToTimestamp(startTime.minus(10, ChronoUnit.SECONDS))) // 10 seconds before stream start
                .setEndTime(instantToTimestamp(startTime.minus(9, ChronoUnit.SECONDS)))   // Took 1 second
                .setStatus("SUCCESS")
                .addAllProcessorLogs(step1Logs)
                // No error_data for success
                .build();

        HistoryEntry historyEntry2 = HistoryEntry.newBuilder()
                .setHopNumber(1)
                .setStepName("gRPC Enrichment Step")
                .setServiceInstanceId("enrichment-service-instance-3")
                .setStartTime(instantToTimestamp(startTime.minus(5, ChronoUnit.SECONDS))) // 5 seconds before stream start
                .setEndTime(instantToTimestamp(startTime.minus(3, ChronoUnit.SECONDS)))   // Took 2 seconds
                .setStatus("FAILURE")
                .addAllProcessorLogs(step2Logs)
                .setErrorData(errorDetails) // Add the detailed error info
                // Optionally add richer error details via Struct
                .setErrorStruct(Struct.newBuilder()
                        .putFields("retry_attempt", Value.newBuilder().setNumberValue(3).build())
                        .putFields("target_service", Value.newBuilder().setStringValue(routeGrpc.getDestination()).build())
                        .build())
                .build();

        // 7. Create an optional Input Blob
        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("source_system", "legacy-ftp");
        blobMetadata.put("correlation_id", "blob-corr-789");

        Blob inputBlob = Blob.newBuilder()
                .setBlob(ByteString.copyFromUtf8("This is the raw input data as a blob."))
                .setMimeType("text/plain")
                .setFilename("original_input.txt")
                .setEncoding("UTF-8")
                .putAllMetadata(blobMetadata)
                .build();

        // 8. Create Context Params
        Map<String, String> contextParams = new HashMap<>();
        contextParams.put("trace_id", UUID.randomUUID().toString());
        contextParams.put("user_id", "user-abc");
        contextParams.put("experiment_group", "group_b");


        // 9. Create the PipeStream with more fields populated
        return PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString()) // REQUIRED: Unique stream ID
                .setPipelineName("complex-enrichment-pipeline") // REQUIRED: Pipeline name
                .setCurrentHopNumber(2) // Next step to execute would be hop 2
                .setCurrentDoc(pipeDoc) // Document state *before* hop 2
                .addHistory(historyEntry1) // Add first history entry
                .addHistory(historyEntry2) // Add second history entry (at least two)
                .setInputBlob(inputBlob)   // Set the optional input blob
                .putAllContextParams(contextParams) // Add context parameters
                .build();
    }

    public static void main(String[] args) {
        System.out.println("--- Full PipeDoc ---");
        PipeDoc pipeDoc = createFullPipeDoc("standalone-doc-789", "Standalone Example PipeDoc");
        System.out.println(pipeDoc);

        System.out.println("\n--- Full PipeStream (Based on Proto) ---");
        PipeStream pipeStream = createFullPipeStream();
        System.out.println(pipeStream);
    }
}