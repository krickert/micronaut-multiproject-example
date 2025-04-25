package com.krickert.search.test;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.pipe.PipeDoc;
import com.krickert.search.model.pipe.SemanticChunk;
import com.krickert.search.model.pipe.SemanticData;
import com.krickert.search.model.pipe.SemanticDocument;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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

        // Create two SemanticChunks.
        SemanticChunk chunk1 = SemanticChunk.newBuilder()
                .setChunkId("chunk1")
                .setChunkNumber(1)
                .setChunk("This is the first semantic chunk.")
                .addAllEmbeddings(Arrays.asList(0.1f, 0.2f, 0.3f))
                .build();

        SemanticChunk chunk2 = SemanticChunk.newBuilder()
                .setChunkId("chunk2")
                .setChunkNumber(2)
                .setChunk("This is the second semantic chunk.")
                .addAllEmbeddings(Arrays.asList(0.4f, 0.5f, 0.6f))
                .build();

        // Create a SemanticDocument with repeated chunks and metadata.
        SemanticDocument semanticDocument1 = SemanticDocument.newBuilder()
                .addChunks(chunk1)
                .addChunks(chunk2)
                .setParentId("parent1")
                .putMetadata("meta1", "value1")
                .putMetadata("meta2", "value2")
                .build();

        // Create another SemanticDocument.
        SemanticDocument semanticDocument2 = SemanticDocument.newBuilder()
                .addChunks(chunk2)
                .setParentId("parent2")
                .putMetadata("metaA", "valueA")
                .putMetadata("metaB", "valueB")
                .build();

        // Create SemanticData with a map containing two entries.
        Map<String, SemanticDocument> chunkData = new HashMap<>();
        chunkData.put("doc1", semanticDocument1);
        chunkData.put("doc2", semanticDocument2);
        SemanticData semanticData = SemanticData.newBuilder()
                .putAllChunkData(chunkData)
                .build();

        // Build the PipeDoc with all fields populated.

        return PipeDoc.newBuilder()
                .setId("pipeDoc123")
                .setTitle("Example PipeDoc")
                .setBody("This is an example document body.")
                .addAllKeywords(Arrays.asList("keyword1", "keyword2", "keyword3"))
                .setDocumentType("example-type")
                .setRevisionId("rev-001")
                .setCreationDate(creationDate)
                .setLastModified(lastModified)
                .setCustomData(customData)
                .setSemanticData(semanticData)
                .build();
    }

    public static void main(String[] args) {
        PipeDoc pipeDoc = createFullPipeDoc();
        System.out.println("PipeDoc:\n" + pipeDoc);
    }
}