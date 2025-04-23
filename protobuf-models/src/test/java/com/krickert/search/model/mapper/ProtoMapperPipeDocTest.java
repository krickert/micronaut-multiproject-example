package com.krickert.search.model.mapper;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.*;
import com.krickert.search.model.pipe.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProtoMapper using PipeDoc definitions.
 */
public class ProtoMapperPipeDocTest {

    private static ProtoMapper mapper;
    private static Descriptor pipeDocDesc;

    @BeforeAll
    static void setUp() {
        mapper = new ProtoMapper();
        pipeDocDesc = PipeDoc.getDescriptor();
        assertNotNull(pipeDocDesc, "PipeDoc descriptor should be loaded");
    }

    // Helper to create a Timestamp
    private Timestamp createTimestamp(long seconds, int nanos) {
        return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
    }

    // Helper to create an Embedding
    private Embedding createEmbedding(List<Float> values) {
        return Embedding.newBuilder().addAllEmbedding(values).build();
    }

    @Test
    void testSimpleAssignment() throws InvalidProtocolBufferException, MappingException {
        PipeDoc source = PipeDoc.newBuilder()
                .setId("source-123")
                .setTitle("Source Title")
                .setBody("Source Body Content")
                .build();
        List<String> rules = Arrays.asList(
                "id = id", // Self-assignment for testing
                "title = title",
                "body = body"
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());
        assertEquals("source-123", target.getId());
        assertEquals("Source Title", target.getTitle());
        assertEquals("Source Body Content", target.getBody());
    }

    @Test
    void testTimestampAssignment() throws MappingException, InvalidProtocolBufferException {
        Timestamp ts = createTimestamp(1678886400, 5000); // Example timestamp
        PipeDoc source = PipeDoc.newBuilder()
                .setCreationDate(ts)
                .build();
        List<String> rules = Collections.singletonList("last_modified = creation_date");

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertTrue(target.hasLastModified());
        assertEquals(ts, target.getLastModified());
        assertFalse(target.hasCreationDate()); // Source field shouldn't be copied unless specified
    }

    @Test
    void testNestedAssignment() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .setId("parent-doc-id")
                .setChunkEmbeddings(SemanticDoc.newBuilder()
                        .setParentField("source_parent_field")
                        .setSemanticConfigId("source_config"))
                .build();
        List<String> rules = Arrays.asList(
                "chunk_embeddings.parent_id = id", // Assign top-level id to nested field
                "chunk_embeddings.semantic_config_id = chunk_embeddings.semantic_config_id" // Self-assign nested
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertTrue(target.hasChunkEmbeddings());
        assertEquals("parent-doc-id", target.getChunkEmbeddings().getParentId());
        assertEquals("source_config", target.getChunkEmbeddings().getSemanticConfigId());
        assertEquals("", target.getChunkEmbeddings().getParentField()); // Not mapped
    }

    @Test
    void testRepeatedAssignAndAppend() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .addKeywords("source_tag1")
                .addKeywords("source_tag2")
                .setTitle("append_me")
                .build();
        List<String> rules = Arrays.asList(
                "keywords = keywords", // Replace target with source list
                "keywords += title"    // Append the title string
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertEquals(Arrays.asList("source_tag1", "source_tag2", "append_me"), target.getKeywordsList());
    }

     @Test
     void testRepeatedAppendList() throws MappingException, InvalidProtocolBufferException {
         PipeDoc source = PipeDoc.newBuilder()
                 .addKeywords("srcA")
                 .addKeywords("srcB")
                 .build();
         // Simulate initial target state via rules
         List<String> rules = Arrays.asList(
             "keywords += title", // Add one element initially
             "keywords += keywords"  // Append the source list
         );

         PipeDoc sourceWithTitle = source.toBuilder().setTitle("initial_tag").build();
         Message result = mapper.map(sourceWithTitle, pipeDocDesc, rules);
         PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

         assertEquals(Arrays.asList("initial_tag", "srcA", "srcB"), target.getKeywordsList());
     }


    @Test
    void testStructAssignmentInto() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .setTitle("Title for Struct")
                .setId("doc-id-struct")
                .setRevisionId("rev-5") // Test deleting this later
                .build();

        // For now, test only field-to-struct mapping
        List<String> fieldOnlyRules = Arrays.asList(
                "custom_data.original_title = title",
                "custom_data.doc_id = id",
                "-revision_id" // Test deletion alongside struct mapping
        );


        Message result = mapper.map(source, pipeDocDesc, fieldOnlyRules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertTrue(target.hasCustomData());
        Struct data = target.getCustomData();
        assertEquals("Title for Struct", data.getFieldsOrThrow("original_title").getStringValue());
        assertEquals("doc-id-struct", data.getFieldsOrThrow("doc_id").getStringValue());
        assertTrue(target.getRevisionId().isEmpty()); // Check deletion worked
        // Assertions for literals would go here if supported
    }

    @Test
    void testStructAssignmentOutOf() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .setCustomData(Struct.newBuilder()
                        .putFields("new_title", Value.newBuilder().setStringValue("Title From Custom Data").build())
                        .putFields("is_published", Value.newBuilder().setBoolValue(true).build())
                        .putFields("rating", Value.newBuilder().setNumberValue(4.5).build()))
                .build();
        List<String> rules = Arrays.asList(
                "title = custom_data.new_title",
                "document_type = custom_data.rating" // Map number (double) to string
                // Add rule for bool -> string if supported: "revision_id = custom_data.is_published"
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertEquals("Title From Custom Data", target.getTitle());
        assertEquals("4.5", target.getDocumentType()); // Check double to string conversion
    }


    @Test
    void testMapAssignmentReplaceAndPut() throws MappingException, InvalidProtocolBufferException {
        Embedding emb1 = createEmbedding(Arrays.asList(0.1f, 0.2f));
        Embedding emb2 = createEmbedding(Arrays.asList(0.3f, 0.4f));
        Embedding emb3 = createEmbedding(Arrays.asList(0.5f, 0.6f));

        PipeDoc source = PipeDoc.newBuilder()
                .putEmbeddings("source_key1", emb1)
                .putEmbeddings("source_key2", emb2)
                .setChunkEmbeddings(SemanticDoc.newBuilder() // Source for map put
                        .addChunks(SemanticChunk.newBuilder()
                                .setEmbedding(ChunkEmbedding.newBuilder().addEmbedding(0.9f).addEmbedding(0.8f))))
                .build();

        // --- Test incompatible assignment (should fail, but we test the compatible one below) ---
        // List<String> rulesIncompatible = Arrays.asList(
        //        "embeddings = embeddings", // Replace target map with source map
        //        "embeddings[\"new_vector\"] = chunk_embeddings.chunks.embedding" // Put a new entry, value from nested repeated
        // );
        // assertThrows(ProtoMapper.MappingException.class, () -> {
        //     mapper.map(source, pipeDocDesc, rulesIncompatible);
        // }, "Should throw type mismatch for incompatible message types");
        // --- End incompatible test ---


        // --- Test compatible assignment ---
        List<String> rulesCompatiblePut = Arrays.asList(
                "embeddings = embeddings", // Replace target map with source map
                "embeddings[\"copied_vector\"] = embeddings[\"source_key1\"]"
        );
        Message resultCompatible = mapper.map(source, pipeDocDesc, rulesCompatiblePut);
        PipeDoc targetCompatible = PipeDoc.parseFrom(resultCompatible.toByteArray()); // Parse result back

        assertEquals(3, targetCompatible.getEmbeddingsMap().size());
        assertEquals(emb1, targetCompatible.getEmbeddingsMap().get("source_key1"));
        assertEquals(emb2, targetCompatible.getEmbeddingsMap().get("source_key2"));
        assertEquals(emb1, targetCompatible.getEmbeddingsMap().get("copied_vector"));
    }


    @Test
    void testMapMerge() throws MappingException, InvalidProtocolBufferException {
        Embedding emb1 = createEmbedding(Arrays.asList(0.1f, 0.2f));
        Embedding emb2 = createEmbedding(Arrays.asList(0.3f, 0.4f));
        Embedding emb3 = createEmbedding(Arrays.asList(1.1f, 1.2f)); // For target initial
        Embedding emb4 = createEmbedding(Arrays.asList(1.3f, 1.4f)); // For target initial

        PipeDoc sourceForMerge = PipeDoc.newBuilder()
                .putEmbeddings("key1", emb1) // Will overwrite target's key1
                .putEmbeddings("key2", emb2) // Will be added
                .build();

        // Simulate initial target state via rules
        List<String> setupRules = Arrays.asList(
                "embeddings[\"key1\"] = embeddings[\"initial_key1\"]",
                "embeddings[\"key3\"] = embeddings[\"initial_key3\"]"
        );

        // Create a 'source' message that holds the initial target values for the setup rules
        PipeDoc initialTargetSetupSource = PipeDoc.newBuilder()
                .putEmbeddings("initial_key1", emb3)
                .putEmbeddings("initial_key3", emb4)
                .build();


        // --- Corrected Merge Test Logic ---
        // 1. Create initial state using map
        Message intermediateResult = mapper.map(initialTargetSetupSource, pipeDocDesc, setupRules);
        Message.Builder targetBuilder = intermediateResult.toBuilder(); // Start builder with initial state

        // 2. Apply the merge rule using mapOnto
        List<String> mergeRule = Collections.singletonList("embeddings += embeddings");
        Message.Builder builder = mapper.mapOnto(sourceForMerge, targetBuilder, mergeRule); // Map source onto existing builder

        // 3. Build final result and verify
        PipeDoc target = PipeDoc.parseFrom(builder.build().toByteArray());

        Map<String, Embedding> targetEmbeddings = target.getEmbeddingsMap();
        assertEquals(3, targetEmbeddings.size(), "Map should have 3 entries after merge");
        assertEquals(emb1, targetEmbeddings.get("key1"), "key1 should be overwritten by source");
        assertEquals(emb2, targetEmbeddings.get("key2"), "key2 should be added from source");
        assertEquals(emb4, targetEmbeddings.get("key3"), "key3 should be kept from initial state");
    }


    @Test
    void testFieldDeletion() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .setId("doc-to-clear")
                .setRevisionId("rev-1")
                .setChunkEmbeddings(SemanticDoc.newBuilder().setParentId("pid")) // Nested field to clear
                .build();
        List<String> rules = Arrays.asList(
                "id = id", // Keep id
                "-revision_id", // Delete top-level field
                "-chunk_embeddings.parent_id" // Delete nested field
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());
        assertEquals("doc-to-clear", target.getId());
        assertTrue(target.getRevisionId().isEmpty()); // Check field is cleared (or default)
        assertEquals("", target.getRevisionId()); // Check default value
        assertTrue(target.hasChunkEmbeddings()); // Parent message should still exist
        assertEquals("", target.getChunkEmbeddings().getParentId()); // Nested field cleared
    }

    @Test
    void testStructKeyDeletion() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .setCustomData(Struct.newBuilder()
                        .putFields("keep_me", Value.newBuilder().setStringValue("Keep").build())
                        .putFields("delete_me", Value.newBuilder().setNumberValue(123).build()))
                .build();
        List<String> rules = Arrays.asList(
                "custom_data = custom_data", // Copy struct first
                "-custom_data.delete_me"     // Delete a key
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());
        assertTrue(target.hasCustomData());
        Struct data = target.getCustomData();
        assertTrue(data.containsFields("keep_me"));
        assertFalse(data.containsFields("delete_me"));
    }

    @Test
    void testErrorSourcePathNotFound() {
        PipeDoc source = PipeDoc.newBuilder().build();
        List<String> rules = Collections.singletonList("title = non_existent_source_field");

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Field not found: 'non_existent_source_field'"));
        assertEquals("title = non_existent_source_field", e.getFailedRule());
    }

     @Test
     void testErrorTargetPathNotFound() {
         PipeDoc source = PipeDoc.newBuilder().setTitle("hello").build();
         List<String> rules = Collections.singletonList("non_existent_target = title");

         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         assertTrue(e.getMessage().contains("Field not found: 'non_existent_target'"));
         assertEquals("non_existent_target = title", e.getFailedRule());
     }

     @Test
     void testErrorNestedPathNotFound() {
         PipeDoc source = PipeDoc.newBuilder().setId("id").build();
         List<String> rules = Collections.singletonList("chunk_embeddings.non_existent = id");

         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         // Error message might vary depending on where exactly path resolution fails
         assertTrue(e.getMessage().contains("Field not found: 'non_existent'"));
         assertEquals("chunk_embeddings.non_existent = id", e.getFailedRule());
     }


    @Test
    void testErrorTypeMismatch() {
        PipeDoc source = PipeDoc.newBuilder().setTitle("This is not a timestamp").build();
        // Rule tries to assign string to Timestamp field
        List<String> rules = Collections.singletonList("creation_date = title");

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Type mismatch"));
        assertTrue(e.getMessage().contains("Cannot convert STRING to MESSAGE")); // Timestamp is a MESSAGE type
        assertEquals("creation_date = title", e.getFailedRule());
    }

    @Test
    void testErrorInvalidRuleSyntax() {
        PipeDoc source = PipeDoc.newBuilder().build();
        List<String> rules = Collections.singletonList("title = = body"); // Extra '='

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax"));
        assertEquals("title = = body", e.getFailedRule());
    }


    @Test
    void testMapAssignmentReplaceAndPut1() throws InvalidProtocolBufferException, MappingException {
        Embedding emb1 = createEmbedding(Arrays.asList(0.1f, 0.2f));
        Embedding emb2 = createEmbedding(Arrays.asList(0.3f, 0.4f));
        Embedding emb3 = createEmbedding(Arrays.asList(0.5f, 0.6f));

        PipeDoc source = PipeDoc.newBuilder()
                .putEmbeddings("source_key1", emb1)
                .putEmbeddings("source_key2", emb2)
                .setChunkEmbeddings(SemanticDoc.newBuilder() // Source for map put
                        .addChunks(SemanticChunk.newBuilder()
                                .setEmbedding(ChunkEmbedding.newBuilder().addEmbedding(0.9f).addEmbedding(0.8f))))
                .build();

        // --- Test incompatible assignment (should fail, but we test the compatible one below) ---
        // List<String> rulesIncompatible = Arrays.asList(
        //        "embeddings = embeddings", // Replace target map with source map
        //        "embeddings[\"new_vector\"] = chunk_embeddings.chunks.embedding" // Put a new entry, value from nested repeated
        // );
        // assertThrows(ProtoMapper.MappingException.class, () -> {
        //     mapper.map(source, pipeDocDesc, rulesIncompatible);
        // }, "Should throw type mismatch for incompatible message types");
        // --- End incompatible test ---


        // --- Test compatible assignment ---
        List<String> rulesCompatiblePut = Arrays.asList(
                "embeddings = embeddings", // Replace target map with source map
                "embeddings[\"copied_vector\"] = embeddings[\"source_key1\"]"
        );
        Message resultCompatible = mapper.map(source, pipeDocDesc, rulesCompatiblePut);
        PipeDoc targetCompatible = PipeDoc.parseFrom(resultCompatible.toByteArray()); // Parse result back

        assertEquals(3, targetCompatible.getEmbeddingsMap().size());
        assertEquals(emb1, targetCompatible.getEmbeddingsMap().get("source_key1"));
        assertEquals(emb2, targetCompatible.getEmbeddingsMap().get("source_key2"));
        assertEquals(emb1, targetCompatible.getEmbeddingsMap().get("copied_vector"));
    }

}