package com.krickert.search.test;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.*;

import java.util.Arrays;

public class PipeDocExample {

    public static PipeDoc createFullPipeDoc() {
        // Create a Timestamp for creation_date and last_modified
        Timestamp creationDate = Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .setNanos(0)
                .build();

        Timestamp lastModified = Timestamp.newBuilder()
                .setSeconds((System.currentTimeMillis() / 1000) + 3600)
                .setNanos(500)
                .build();

        // Create a custom_data Struct with multiple fields.
        Struct customData = Struct.newBuilder()
                .putFields("field1", Value.newBuilder().setStringValue("value1").build())
                .putFields("field2", Value.newBuilder().setNumberValue(123.45).build())
                .putFields("field3", Value.newBuilder().setBoolValue(true).build())
                .build();

        // Create ChunkEmbedding instances
        ChunkEmbedding chunkEmbedding1 = ChunkEmbedding.newBuilder()
                .setEmbeddingText("This is the first semantic chunk.")
                .addAllEmbedding(Arrays.asList(0.1f, 0.2f, 0.3f))
                .build();

        ChunkEmbedding chunkEmbedding2 = ChunkEmbedding.newBuilder()
                .setEmbeddingText("This is the second semantic chunk.")
                .addAllEmbedding(Arrays.asList(0.4f, 0.5f, 0.6f))
                .build();

        // Create SemanticChunk instances
        SemanticChunk semanticChunk1 = SemanticChunk.newBuilder()
                .setChunkId("chunk1")
                .setChunkNumber(1)
                .setEmbedding(chunkEmbedding1)
                .build();

        SemanticChunk semanticChunk2 = SemanticChunk.newBuilder()
                .setChunkId("chunk2")
                .setChunkNumber(2)
                .setEmbedding(chunkEmbedding2)
                .build();

        // Create SemanticDoc instances
        SemanticDoc semanticDoc1 = SemanticDoc.newBuilder()
                .setParentId("parent1")
                .setParentField("body")
                .setChunkConfigId("config1")
                .setSemanticConfigId("semantic1")
                .addChunks(semanticChunk1)
                .addChunks(semanticChunk2)
                .build();

        SemanticDoc semanticDoc2 = SemanticDoc.newBuilder()
                .setParentId("parent2")
                .setParentField("title")
                .setChunkConfigId("config2")
                .setSemanticConfigId("semantic2")
                .addChunks(semanticChunk2)
                .build();

        // Create Embedding instances
        Embedding embedding1 = Embedding.newBuilder()
                .addAllEmbedding(Arrays.asList(0.7f, 0.8f, 0.9f))
                .build();

        Embedding embedding2 = Embedding.newBuilder()
                .addAllEmbedding(Arrays.asList(0.4f, 0.5f, 0.6f))
                .build();

        // Build the PipeDoc with all fields populated.
        PipeDoc.Builder builder = PipeDoc.newBuilder()
                .setId("pipeDoc123")
                .setTitle("Example PipeDoc")
                .setBody("This is an example document body.")
                .addAllKeywords(Arrays.asList("keyword1", "keyword2", "keyword3"))
                .setDocumentType("example-type")
                .setRevisionId("rev-001")
                .setCreationDate(creationDate)
                .setLastModified(lastModified)
                .setCustomData(customData);

        // Set chunk_embeddings as a single message
        builder.setChunkEmbeddings(semanticDoc1);

        // Add embeddings
        builder.putEmbeddings("embedding1", embedding1);
        builder.putEmbeddings("embedding2", embedding2);

        return builder.build();
    }

    public static void main(String[] args) {
        PipeDoc pipeDoc = createFullPipeDoc();
        System.out.println("PipeDoc:\n" + pipeDoc);
    }
}
