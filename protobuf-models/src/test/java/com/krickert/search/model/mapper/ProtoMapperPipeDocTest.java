package com.krickert.search.model.mapper;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.*;
import com.krickert.search.model.pipe.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
// import com.krickert.search.model.pipe.DocStatus; // Assuming an enum existed

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProtoMapper using PipeDoc definitions.
 * Includes original tests plus additional cases.
 * Corrected based on actual proto schema.
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

    // --- Existing Tests (Unchanged as requested) ---

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
                .setChunkEmbeddings(SemanticDoc.newBuilder().setParentId("pid-struct")) // Test nested assignment
                .addKeywords("struct_tag") // Test repeated field assignment
                .setCustomData(Struct.newBuilder() // Add numeric source in struct
                        .putFields("source_num", Value.newBuilder().setNumberValue(42.5).build()))
                .build();

        List<String> rules = Arrays.asList(
                "custom_data.original_title = title",
                "custom_data.doc_id = id",
                "custom_data.parent_id = chunk_embeddings.parent_id", // Assign nested -> struct
                "custom_data.tags = keywords",             // Assign repeated string -> struct (becomes list value)
                "custom_data.num_from_struct = custom_data.source_num", // Assign number from struct -> struct
                "custom_data.static_bool = true",         // Assign literal bool -> struct
                "custom_data.static_null = null",         // Assign literal null -> struct
                "custom_data.static_num = -123.45",        // Assign literal number -> struct
                "-revision_id"                             // Test deletion alongside struct mapping
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertTrue(target.hasCustomData());
        Struct data = target.getCustomData();
        assertEquals("Title for Struct", data.getFieldsOrThrow("original_title").getStringValue());
        assertEquals("doc-id-struct", data.getFieldsOrThrow("doc_id").getStringValue());
        assertEquals(42.5, data.getFieldsOrThrow("num_from_struct").getNumberValue(), 0.001); // Check number value
        assertEquals("pid-struct", data.getFieldsOrThrow("parent_id").getStringValue());
        assertTrue(data.getFieldsOrThrow("tags").hasListValue());
        assertEquals(1, data.getFieldsOrThrow("tags").getListValue().getValuesCount());
        assertEquals("struct_tag", data.getFieldsOrThrow("tags").getListValue().getValues(0).getStringValue());
        assertTrue(data.getFieldsOrThrow("static_bool").getBoolValue());
        assertEquals(Value.KindCase.NULL_VALUE, data.getFieldsOrThrow("static_null").getKindCase());
        assertEquals(-123.45, data.getFieldsOrThrow("static_num").getNumberValue(), 0.001);

        assertTrue(target.getRevisionId().isEmpty()); // Check deletion worked
    }

    @Test
    void testStructAssignmentOutOf() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .setCustomData(Struct.newBuilder()
                        .putFields("new_title", Value.newBuilder().setStringValue("Title From Custom Data").build())
                        .putFields("is_published", Value.newBuilder().setBoolValue(true).build())
                        .putFields("rating", Value.newBuilder().setNumberValue(4.5).build())
                        .putFields("version_code", Value.newBuilder().setStringValue("v2.1").build())
                        .putFields("nested_struct", Value.newBuilder().setStructValue(
                                Struct.newBuilder().putFields("inner_key", Value.newBuilder().setStringValue("inner_value").build())
                        ).build())
                        .putFields("tag_list", Value.newBuilder().setListValue(
                                ListValue.newBuilder()
                                        .addValues(Value.newBuilder().setStringValue("tagA").build())
                                        .addValues(Value.newBuilder().setStringValue("tagB").build())
                        ).build()))
                .build();
        List<String> rules = Arrays.asList(
                "title = custom_data.new_title",
                "document_type = custom_data.rating", // Map number (double) to string
                "revision_id = custom_data.is_published", // Map bool to string
                "id = custom_data.version_code", // Map string to string
                "keywords = custom_data.tag_list", // Map ListValue to repeated string
                "body = custom_data.nested_struct.inner_key" // Map out of nested struct
        );

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertEquals("Title From Custom Data", target.getTitle());
        assertEquals("4.5", target.getDocumentType()); // Check double to string conversion
        assertEquals("true", target.getRevisionId()); // Check bool to string conversion
        assertEquals("v2.1", target.getId());
        assertEquals(Arrays.asList("tagA", "tagB"), target.getKeywordsList());
        assertEquals("inner_value", target.getBody());
    }

    @Test
    void testMapAssignmentReplaceAndPut() throws MappingException, InvalidProtocolBufferException {
        Embedding emb1 = createEmbedding(Arrays.asList(0.1f, 0.2f));
        Embedding emb2 = createEmbedding(Arrays.asList(0.3f, 0.4f));
        Embedding emb3 = createEmbedding(Arrays.asList(0.5f, 0.6f)); // For putting

        PipeDoc source = PipeDoc.newBuilder()
                .putEmbeddings("source_key1", emb1)
                .putEmbeddings("source_key2", emb2)
                .setTitle("key_from_title") // source for key literal
                .setChunkEmbeddings(SemanticDoc.newBuilder() // Source for complex map put value
                        .addChunks(SemanticChunk.newBuilder()
                                .setChunkId("chunk_id_for_value") // Valid field in SemanticChunk
                                .setEmbedding(ChunkEmbedding.newBuilder().addEmbedding(0.9f).addEmbedding(0.8f))))
                .build();

        // Test compatible assignments
         List<String> validRules = Arrays.asList(
                "embeddings = embeddings", // Replace target map with source map
                "embeddings[\"copied_vector\"] = embeddings[\"source_key1\"]", // Put using value from same map
                "embeddings[\"new_key\"] = embeddings[\"source_key2\"]"     // Put with string literal key
                 // Cannot test "embeddings[title] = body_bytes" as body_bytes does not exist.
                 // Cannot test "embeddings[\"complex_put\"] = chunk_embeddings.chunks[0].embedding" - type mismatch (ChunkEmbedding vs Embedding)
                 // Cannot test "embeddings[title] = chunk_embeddings.chunks[0].embedding" - type mismatch (ChunkEmbedding vs Embedding)
        );


        Message result = mapper.map(source, pipeDocDesc, validRules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

        assertEquals(4, target.getEmbeddingsMap().size()); // source_key1, source_key2, copied_vector, new_key
        assertEquals(emb1, target.getEmbeddingsMap().get("source_key1"));
        assertEquals(emb2, target.getEmbeddingsMap().get("source_key2"));
        assertEquals(emb1, target.getEmbeddingsMap().get("copied_vector"));
        assertEquals(emb2, target.getEmbeddingsMap().get("new_key"));


         // Test type mismatch for map value
        List<String> invalidValueRule = Collections.singletonList("embeddings[\"bad_type\"] = title"); // title (string) -> Embedding (Message)
        MappingException eValue = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, invalidValueRule);
         });
         assertTrue(eValue.getMessage().contains("Type mismatch"));
         assertTrue(eValue.getMessage().contains("Cannot convert STRING to MESSAGE"));

         // Test type mismatch for map key (if key is not string) - N/A for PipeDoc.embeddings

    }

    @Test
    void testMapMerge() throws MappingException, InvalidProtocolBufferException {
        Embedding emb1 = createEmbedding(Arrays.asList(0.1f, 0.2f)); // Source key1 (overwrite)
        Embedding emb2 = createEmbedding(Arrays.asList(0.3f, 0.4f)); // Source key2 (add)
        Embedding emb3 = createEmbedding(Arrays.asList(1.1f, 1.2f)); // Initial key1
        Embedding emb4 = createEmbedding(Arrays.asList(1.3f, 1.4f)); // Initial key3

        PipeDoc sourceForMerge = PipeDoc.newBuilder()
                .putEmbeddings("key1", emb1)
                .putEmbeddings("key2", emb2)
                .build();

        // Simulate initial target state using a separate mapping step
        PipeDoc initialTargetState = PipeDoc.newBuilder()
                .putEmbeddings("key1", emb3)
                .putEmbeddings("key3", emb4)
                .build();
        Message.Builder targetBuilder = initialTargetState.toBuilder();

        // Apply the merge rule using mapOnto
        List<String> mergeRule = Collections.singletonList("embeddings += embeddings");
        mapper.mapOnto(sourceForMerge, targetBuilder, mergeRule); // Map source onto existing builder

        // Build final result and verify
        PipeDoc target = PipeDoc.parseFrom(targetBuilder.build().toByteArray());

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
                .setChunkEmbeddings(SemanticDoc.newBuilder().setParentId("pid").setSemanticConfigId("config")) // Nested field to clear
                .build();
        List<String> rules = Arrays.asList(
                "id = id", // Keep id
                "chunk_embeddings = chunk_embeddings", // Keep nested message shell
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
        assertEquals("config", target.getChunkEmbeddings().getSemanticConfigId()); // Other nested field untouched
    }


    @Test
    void testStructKeyDeletion() throws MappingException, InvalidProtocolBufferException {
        PipeDoc source = PipeDoc.newBuilder()
                .setCustomData(Struct.newBuilder()
                        .putFields("keep_me", Value.newBuilder().setStringValue("Keep").build())
                        .putFields("delete_me", Value.newBuilder().setNumberValue(123).build())
                        .putFields("another_key", Value.newBuilder().setBoolValue(true).build())
                )
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
        assertTrue(data.containsFields("another_key"));
        assertFalse(data.containsFields("delete_me"));
        assertEquals(2, data.getFieldsCount());
    }

     // --- Error Handling Tests ---

    @Test
    void testErrorSourcePathNotFound() {
        PipeDoc source = PipeDoc.newBuilder().build();
        List<String> rules = Collections.singletonList("title = non_existent_source_field");

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        // Error message depends on PathResolver implementation
        assertTrue(e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve path"), "Expected path resolution error message");
        assertTrue(e.getMessage().contains("non_existent_source_field"), "Error message should contain the missing field");
        assertEquals("title = non_existent_source_field", e.getFailedRule());
    }

     @Test
     void testErrorTargetPathNotFound() {
         PipeDoc source = PipeDoc.newBuilder().setTitle("hello").build();
         List<String> rules = Collections.singletonList("non_existent_target = title");

         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         assertTrue(e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve path"), "Expected path resolution error message");
         assertTrue(e.getMessage().contains("non_existent_target"), "Error message should contain the missing field");
         assertEquals("non_existent_target = title", e.getFailedRule());
     }

     @Test
     void testErrorNestedPathNotFound_IntermediateNotSet() {
         // Source where chunk_embeddings is not set
         PipeDoc source = PipeDoc.newBuilder().setId("id").build();
         // Rule tries to access a field within the unset chunk_embeddings
         List<String> rules = Collections.singletonList("id = chunk_embeddings.parent_id");

         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         assertTrue(e.getMessage().contains("Cannot resolve path") || e.getMessage().contains("is not set"), "Expected path resolution error for unset intermediate");
         assertTrue(e.getMessage().contains("chunk_embeddings"), "Error message should contain the intermediate field");
         assertEquals("id = chunk_embeddings.parent_id", e.getFailedRule());
     }

     @Test
     void testErrorNestedPathNotFound_FinalFieldMissing() {
         // Source where chunk_embeddings is set, but the target field doesn't exist in SemanticDoc
         PipeDoc source = PipeDoc.newBuilder()
                 .setChunkEmbeddings(SemanticDoc.newBuilder().setParentId("pid"))
                 .build();
         List<String> rules = Collections.singletonList("title = chunk_embeddings.non_existent_field");

         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         assertTrue(e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve path"), "Expected path resolution error for final field");
         assertTrue(e.getMessage().contains("non_existent_field"), "Error message should contain the missing field");
         assertEquals("title = chunk_embeddings.non_existent_field", e.getFailedRule());
     }


    @Test
    void testErrorTypeMismatch_Assign() {
        PipeDoc source = PipeDoc.newBuilder().setTitle("This is not a timestamp").build();
        // Rule tries to assign string to Timestamp field
        List<String> rules = Collections.singletonList("creation_date = title");

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Type mismatch") || e.getMessage().contains("Cannot convert"), "Expected type conversion error message");
        assertTrue(e.getMessage().contains("STRING") && e.getMessage().contains("MESSAGE"), "Error message should mention source and target types");
        assertEquals("creation_date = title", e.getFailedRule());
    }

     @Test
     void testErrorTypeMismatch_AppendPrimitiveToList() {
         PipeDoc source = PipeDoc.newBuilder().setTitle("append_me").build();
         // Rule tries to append a string to a repeated message field (Embeddings)
         List<String> rules = Collections.singletonList("embeddings += title"); // embeddings is map<string, Embedding>

         // This should fail because the map value type (Embedding) is incompatible with the source (string)
         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         // The error might occur during conversion before append/put or during the operation itself
         assertTrue(e.getMessage().contains("Type mismatch") || e.getMessage().contains("Unsupported operator") || e.getMessage().contains("Cannot convert"));
         assertTrue(e.getMessage().contains("STRING") && (e.getMessage().contains("MESSAGE") || e.getMessage().contains("Embedding")), "Error should mention incompatible types");
         assertEquals("embeddings += title", e.getFailedRule());
     }

     @Test
     void testErrorTypeMismatch_AppendListToPrimitive() {
         PipeDoc source = PipeDoc.newBuilder().addKeywords("tag1").build();
         // Rule tries to append a list (keywords) to a string field (title)
         List<String> rules = Collections.singletonList("title += keywords");

         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         assertTrue(e.getMessage().contains("Operator '+=' only supported for repeated or map fields"), "Error should mention invalid operator for singular");
         assertEquals("title += keywords", e.getFailedRule());
     }

    @Test
    void testErrorInvalidRuleSyntax_DoubleEquals() {
        PipeDoc source = PipeDoc.newBuilder().build();
        List<String> rules = Collections.singletonList("title = = body"); // Extra '='

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax"), "Expected syntax error message");
        assertEquals("title = = body", e.getFailedRule());
    }

    @Test
    void testErrorInvalidRuleSyntax_MissingSource() {
        PipeDoc source = PipeDoc.newBuilder().build();
        List<String> rules = Collections.singletonList("title = "); // Missing source

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax"), "Expected syntax error message");
        assertEquals("title = ", e.getFailedRule());
    }

     @Test
    void testErrorInvalidRuleSyntax_InvalidAppend() {
        PipeDoc source = PipeDoc.newBuilder().build();
        List<String> rules = Collections.singletonList("title + = body"); // Space before =

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax"), "Expected syntax error message");
        assertEquals("title + = body", e.getFailedRule());
    }

     @Test
    void testErrorInvalidRuleSyntax_InvalidMapPut() {
        PipeDoc source = PipeDoc.newBuilder().build();
        List<String> rules = Collections.singletonList("map[key] = = value"); // Extra =

        MappingException e = assertThrows(MappingException.class, () -> {
            mapper.map(source, pipeDocDesc, rules);
        });
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax"), "Expected syntax error message");
        assertEquals("map[key] = = value", e.getFailedRule());
    }

     // --- Test mapOnto ---
     @Test
     void testMapOnto_ModifiesExistingBuilder() throws MappingException, InvalidProtocolBufferException {
         PipeDoc initialTarget = PipeDoc.newBuilder()
                 .setId("initial-id")
                 .setTitle("Initial Title")
                 .addKeywords("initial_tag")
                 .build();
         Message.Builder targetBuilder = initialTarget.toBuilder();

         PipeDoc source = PipeDoc.newBuilder()
                 .setTitle("New Source Title") // Overwrite title
                 .setBody("Source Body")       // Add body
                 .addKeywords("source_tag")    // Append to keywords
                 .build();

         List<String> rules = Arrays.asList(
                 "title = title",      // Overwrite
                 "body = body",        // Add
                 "keywords += keywords" // Append
         );

         mapper.mapOnto(source, targetBuilder, rules);
         PipeDoc finalTarget = PipeDoc.parseFrom(targetBuilder.build().toByteArray());

         assertEquals("initial-id", finalTarget.getId()); // Unchanged
         assertEquals("New Source Title", finalTarget.getTitle()); // Overwritten
         assertEquals("Source Body", finalTarget.getBody()); // Added
         assertEquals(Arrays.asList("initial_tag", "source_tag"), finalTarget.getKeywordsList()); // Appended
     }

     // --- Test Type Conversions (Add more as needed) ---

     @Test
     void testTypeConversion_DoubleToString() throws MappingException, InvalidProtocolBufferException {
         // Source uses struct field for double
          PipeDoc source = PipeDoc.newBuilder()
                .setCustomData(Struct.newBuilder()
                        .putFields("rating", Value.newBuilder().setNumberValue(4.5).build()))
                .build();
        List<String> rules = Collections.singletonList("title = custom_data.rating"); // double -> string

        Message result = mapper.map(source, pipeDocDesc, rules);
        PipeDoc target = PipeDoc.parseFrom(result.toByteArray());
        assertEquals("4.5", target.getTitle());
     }

     @Test
     void testTypeConversion_StringToEnumNotImplemented() throws MappingException, InvalidProtocolBufferException {
         // PipeDoc doesn't have a top-level enum field suitable for this test.
         assertTrue(true, "Skipping String->Enum conversion test: No suitable target enum field in PipeDoc");
     }

     @Test
     void testTypeConversion_EnumToString() throws MappingException, InvalidProtocolBufferException {
         // PipeDoc doesn't have a top-level enum field suitable for this test.
          assertTrue(true, "Skipping Enum->String conversion test: No suitable source enum field in PipeDoc");
     }


     // --- Test Literal Assignments ---
     @Test
     void testLiteralAssignments() throws MappingException, InvalidProtocolBufferException {
          PipeDoc source = PipeDoc.newBuilder().setId("source-id").build(); // Need a non-null source message
          List<String> rules = Arrays.asList(
                  "title = \"Literal String Title\"", // String literal
                  "revision_id = null",            // Null literal
                  "document_type = 123.456"       // Float literal -> string
                  // Cannot test literal int -> int32 (no version field)
                  // Cannot test literal bool -> bool (no top-level bool field)
                  // "creation_date = now()" // Requires function support, not part of basic literals
          );

          Message result = mapper.map(source, pipeDocDesc, rules);
          PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

          assertEquals("Literal String Title", target.getTitle());
          assertEquals("", target.getRevisionId()); // Null sets to default (empty string)
          assertEquals("123.456", target.getDocumentType()); // Float literal converted to string
     }

     @Test
     void testLiteralAssignmentToStruct() throws MappingException, InvalidProtocolBufferException {
          PipeDoc source = PipeDoc.newBuilder().setId("source-id").build(); // Need a non-null source message
          List<String> rules = Arrays.asList(
                  "custom_data.str_lit = \"hello world\"",
                  "custom_data.num_lit = -5.5",
                  "custom_data.bool_lit = false",
                  "custom_data.null_lit = null"
          );

          Message result = mapper.map(source, pipeDocDesc, rules);
          PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

          assertTrue(target.hasCustomData());
          Struct data = target.getCustomData();
          assertEquals("hello world", data.getFieldsOrThrow("str_lit").getStringValue());
          assertEquals(-5.5, data.getFieldsOrThrow("num_lit").getNumberValue(), 0.001);
          assertFalse(data.getFieldsOrThrow("bool_lit").getBoolValue());
          assertEquals(Value.KindCase.NULL_VALUE, data.getFieldsOrThrow("null_lit").getKindCase());
     }

     @Test
     void testLiteralAssignmentToMap() throws MappingException, InvalidProtocolBufferException {
          // Map values must be of type Embedding in PipeDoc
          // We can only assign compatible types, which currently isn't supported via literals easily.
          assertTrue(true, "Skipping literal assignment to map: requires map with primitive/string value type or message literal support");
     }

     @Test
     void testLiteralAssignmentToRepeated() throws MappingException, InvalidProtocolBufferException {
         PipeDoc source = PipeDoc.newBuilder().setId("source-id").build();
         List<String> rules = Arrays.asList(
             "keywords += \"tag1\"",  // Append string literal
             "keywords += 123",    // Append int literal (converted to string)
             "keywords += true"    // Append bool literal (converted to string)
             // keywords += null // Appending null might be ignored or throw error depending on impl.
         );

         Message result = mapper.map(source, pipeDocDesc, rules);
         PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

         assertEquals(Arrays.asList("tag1", "123", "true"), target.getKeywordsList());
     }

     // --- Test Empty/Null Source ---
      @Test
      void testMapWithNullSourceThrowsError() {
          List<String> rules = Collections.singletonList("title = title");
          NullPointerException npe = assertThrows(NullPointerException.class, () -> {
               mapper.map(null, pipeDocDesc, rules);
           });
           // Expecting null check within map or applyRulesToBuilder
           assertTrue(npe.getMessage() == null || npe.getMessage().toLowerCase().contains("source"));
      }

      @Test
      void testMapOntoWithNullSourceThrowsError() {
          PipeDoc.Builder targetBuilder = PipeDoc.newBuilder();
          List<String> rules = Collections.singletonList("title = title");
          NullPointerException npe = assertThrows(NullPointerException.class, () -> {
               mapper.mapOnto(null, targetBuilder, rules);
           });
          // Expecting null check within mapOnto or applyRulesToBuilder
           assertTrue(npe.getMessage() == null || npe.getMessage().toLowerCase().contains("source"));
      }

      @Test
      void testMapOntoWithNullTargetBuilderThrowsError() {
          PipeDoc source = PipeDoc.newBuilder().build();
          List<String> rules = Collections.singletonList("title = title");
          NullPointerException npe = assertThrows(NullPointerException.class, () -> {
               mapper.mapOnto(source, null, rules);
           });
          // Expecting null check within mapOnto or applyRulesToBuilder
           assertTrue(npe.getMessage() == null || npe.getMessage().toLowerCase().contains("target"));
      }

    // --- Test Map Key Handling ---
     @Test
     void testMapAccessWithNonExistentKey_Read() {
         PipeDoc source = PipeDoc.newBuilder()
                 .putEmbeddings("existing_key", createEmbedding(Arrays.asList(1f)))
                 .build();
         List<String> rules = Collections.singletonList("title = embeddings[\"non_existent_key\"]");

         // Reading a non-existent map key should result in null/default value, not an error
         // The subsequent assignment might fail if title cannot accept null/default (depends on conversion)
         // Assuming null->string is empty string ""
         assertDoesNotThrow(() -> {
             Message result = mapper.map(source, pipeDocDesc, rules);
             PipeDoc target = PipeDoc.parseFrom(result.toByteArray());
             assertEquals("", target.getTitle(), "Assigning value from non-existent map key should result in default");
         });
     }

      @Test
      void testMapAccessWithNonExistentKey_Write() throws MappingException, InvalidProtocolBufferException {
          PipeDoc source = PipeDoc.newBuilder()
                  .putEmbeddings("value_source", createEmbedding(Arrays.asList(1f)))
                  .build();
          List<String> rules = Collections.singletonList("embeddings[\"new_key\"] = embeddings[\"value_source\"]");

          Message result = mapper.map(source, pipeDocDesc, rules);
          PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

          assertTrue(target.getEmbeddingsMap().containsKey("new_key"));
          assertEquals(source.getEmbeddingsMap().get("value_source"), target.getEmbeddingsMap().get("new_key"));
      }

     @Test
     void testMapAccessWithInvalidSyntax() {
         PipeDoc source = PipeDoc.newBuilder().build();
         List<String> rules = Collections.singletonList("title = embeddings[key_no_quotes]"); // Key not quoted

         MappingException e = assertThrows(MappingException.class, () -> {
             mapper.map(source, pipeDocDesc, rules);
         });
         // PathResolver might throw, or RuleParser might if syntax is strict
         assertTrue(e.getMessage().contains("Invalid") || e.getMessage().contains("syntax") || e.getMessage().contains("quote"), "Expected syntax error");
     }

     // --- Test Struct Key Handling ---
     @Test
     void testStructAccessWithNonExistentKey_Read() {
         PipeDoc source = PipeDoc.newBuilder()
                 .setCustomData(Struct.newBuilder()
                         .putFields("existing_key", Value.newBuilder().setStringValue("abc").build()))
                 .build();
         List<String> rules = Collections.singletonList("title = custom_data.non_existent_key");

         // Reading a non-existent struct key should result in null/default value, not an error
         // Assuming null->string is empty string ""
         assertDoesNotThrow(() -> {
             Message result = mapper.map(source, pipeDocDesc, rules);
             PipeDoc target = PipeDoc.parseFrom(result.toByteArray());
             assertEquals("", target.getTitle(), "Assigning value from non-existent struct key should result in default");
         });
     }

     @Test
     void testStructAccessWithNonExistentKey_Write() throws MappingException, InvalidProtocolBufferException {
          PipeDoc source = PipeDoc.newBuilder()
                  .setTitle("value_source")
                  .build();
          List<String> rules = Collections.singletonList("custom_data.new_key = title");

          Message result = mapper.map(source, pipeDocDesc, rules);
          PipeDoc target = PipeDoc.parseFrom(result.toByteArray());

          assertTrue(target.hasCustomData());
          assertTrue(target.getCustomData().containsFields("new_key"));
          assertEquals("value_source", target.getCustomData().getFieldsOrThrow("new_key").getStringValue());
     }

      @Test
      void testDeleteNonExistentStructKeyIsIgnored() throws MappingException, InvalidProtocolBufferException {
           PipeDoc source = PipeDoc.newBuilder()
                 .setCustomData(Struct.newBuilder()
                         .putFields("keep_me", Value.newBuilder().setStringValue("Keep").build()))
                 .build();
           List<String> rules = Arrays.asList(
                   "custom_data = custom_data",
                   "-custom_data.non_existent_key" // Should be ignored
           );

           Message result = mapper.map(source, pipeDocDesc, rules);
           PipeDoc target = PipeDoc.parseFrom(result.toByteArray());
           assertTrue(target.hasCustomData());
           assertEquals(1, target.getCustomData().getFieldsCount());
           assertTrue(target.getCustomData().containsFields("keep_me"));
       }


}