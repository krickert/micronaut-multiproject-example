package com.krickert.search.test.platform.proto;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PipeDocExample {

    // Helper method to create Timestamps easily from Instant
    private static Timestamp instantToTimestamp(Instant instant) {
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
        // 1. Create a PipeDoc
        PipeDoc pipeDoc = createFullPipeDoc("pipeDocStream456", "PipeDoc within Real Stream");

        // 2. Create Routes (at least two)
        Route routeKafka = Route.newBuilder()
                .setRouteType(RouteType.KAFKA)
                .setDestination("output-topic-kafka")
                .build();
        Route routeGrpc = Route.newBuilder()
                .setRouteType(RouteType.GRPC)
                .setDestination("downstream-service-grpc-tag")
                .build();
        Route routeNull = Route.newBuilder()
                .setRouteType(RouteType.NULL_TERMINATION)
                .setDestination("end-of-pipeline") // Destination might be optional/ignored for NULL
                .build();

        // 3. Create a PipeRequest
        Map<String, String> requestConfig = new HashMap<>();
        requestConfig.put("config_key_1", "config_value_1");
        requestConfig.put("retry_policy", "exponential_backoff"); // At least two config entries

        PipeRequest pipeRequest = PipeRequest.newBuilder()
                .setDoc(pipeDoc)
                .putAllConfig(requestConfig)
                .addDestinations(routeKafka)
                .addDestinations(routeGrpc) // At least two destinations
                .build();

        // 4. Create PipeResponse instances (at least two)

        // Successful Response
        PipeResponse successResponse = PipeResponse.newBuilder()
                .setSuccess(true)
                // No error data for success
                .build();

        // Failed Response with ErrorData
        ErrorData errorDetails = ErrorData.newBuilder()
                .setErrorMessage("Failed to connect to downstream gRPC service")
                .addFailedRoutes(routeGrpc) // Indicate which route failed
                // Optionally add another route if multiple failed simultaneously
                .addFailedRoutes(Route.newBuilder().setRouteType(RouteType.KAFKA).setDestination("secondary-topic").build()) // At least two failed routes
                .setErrorRequest(pipeRequest) // Optionally include the request that caused the error
                .build();

        PipeResponse failedResponse = PipeResponse.newBuilder()
                .setSuccess(false)
                .setErrorDate(errorDetails) // Use the generated setter (might be setErrorData depending on protoc version/plugins)
                .build();

        // 5. Create Stream Logs (at least two)
        List<String> streamLogs = Arrays.asList(
                "INFO: Received request corrId=xyz-123",
                "DEBUG: Applying transformation X",
                "WARN: Rate limit approaching for GRPC endpoint",
                "ERROR: Failed delivery to Kafka topic: output-topic-kafka, retrying..." // Example log entries
        );


        // 6. Create the PipeStream
        PipeStream pipeStream = PipeStream.newBuilder()
                .setRequest(pipeRequest)
                .addPipeReplies(successResponse) // Add first response
                .addPipeReplies(failedResponse)  // Add second response (at least two replies)
                .addAllStreamLogs(streamLogs)    // Add multiple log entries
                .build();

        return pipeStream;
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