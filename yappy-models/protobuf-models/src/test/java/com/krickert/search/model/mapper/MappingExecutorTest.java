package com.krickert.search.model.mapper;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Embedding;
import com.krickert.search.model.PipeDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MappingExecutor without using Mockito.
 * This test uses real RuleParser, PathResolver, and ValueHandler objects.
 */
class MappingExecutorTest {

    private RuleParser ruleParser;
    private PathResolver pathResolver;
    private ValueHandler valueHandler;
    private MappingExecutor mappingExecutor;

    private PipeDoc sourceMessage;
    private PipeDoc.Builder targetBuilder;

    @BeforeEach
    void setUp() {
        // Create real objects instead of mocks
        ruleParser = new RuleParser();
        pathResolver = new PathResolver();
        valueHandler = new ValueHandler(pathResolver);
        mappingExecutor = new MappingExecutor(ruleParser, pathResolver, valueHandler);

        sourceMessage = PipeDoc.newBuilder()
                .setId("src-id")
                .setTitle("Src Title")
                .build();
        targetBuilder = PipeDoc.newBuilder();
    }

    @Test
    void applyRulesToBuilder_AssignAndAppend() throws MappingException {
        List<String> ruleStrings = Arrays.asList(
                "title = title",
                "keywords += id"
        );

        // Apply rules
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);

        // Verify results directly
        assertEquals("Src Title", targetBuilder.getTitle());
        assertEquals(1, targetBuilder.getKeywordsCount());
        assertEquals("src-id", targetBuilder.getKeywords(0));
    }

    @Test
    void applyRulesToBuilder_MapPut() throws MappingException {
        // Create a source with a named embedding
        Embedding embedding = Embedding.newBuilder().addVector(1.0f).build();
        PipeDoc sourceWithEmbedding = sourceMessage.toBuilder()
                .putNamedEmbeddings("old", embedding)
                .build();

        List<String> ruleStrings = Collections.singletonList(
                "named_embeddings[\"new\"] = named_embeddings[\"old\"]"
        );

        // Apply rules
        mappingExecutor.applyRulesToBuilder(sourceWithEmbedding, targetBuilder, ruleStrings);

        // Verify results directly
        assertEquals(1, targetBuilder.getNamedEmbeddingsCount());
        assertTrue(targetBuilder.getNamedEmbeddingsMap().containsKey("new"));
        assertEquals(embedding, targetBuilder.getNamedEmbeddingsMap().get("new"));
    }

    @Test
    void applyRulesToBuilder_DeleteField() throws MappingException {
        // Set up target with a field to delete
        targetBuilder.setTitle("Initial Title");

        List<String> ruleStrings = Collections.singletonList("-title");

        // Apply rules
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);

        // Verify results directly
        assertFalse(targetBuilder.hasTitle());
        assertEquals("", targetBuilder.getTitle()); // Default value for string
    }

    @Test
    void applyRulesToBuilder_DeleteStructKey() throws MappingException {
        // Set up target with a struct containing keys to delete and keep
        Struct.Builder structBuilder = Struct.newBuilder()
                .putFields("keep_me", Value.newBuilder().setStringValue("keep").build())
                .putFields("delete_me", Value.newBuilder().setNumberValue(123).build());
        targetBuilder.setCustomData(structBuilder);

        List<String> ruleStrings = Collections.singletonList("-custom_data.delete_me");

        // Apply rules
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);

        // Verify results directly
        assertTrue(targetBuilder.hasCustomData());
        assertTrue(targetBuilder.getCustomData().containsFields("keep_me"));
        assertFalse(targetBuilder.getCustomData().containsFields("delete_me"));
    }

    @Test
    void applyRulesToBuilder_DeleteField_NotFoundIgnored() throws MappingException {
        // No setup needed, just verify no exception is thrown
        List<String> ruleStrings = Collections.singletonList("-non_existent_field");

        // This should not throw an exception
        assertDoesNotThrow(() -> mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings));
    }

    @Test
    void applyRulesToBuilder_ErrorInRuleParsing() {
        List<String> invalidRules = Collections.singletonList("invalid rule syntax = =");

        // This should throw a MappingException
        MappingException e = assertThrows(MappingException.class,
                () -> mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, invalidRules));

        assertTrue(e.getMessage().contains("Invalid") || e.getMessage().contains("syntax"));
    }

    @Test
    void applyRulesToBuilder_ErrorInGetValue() {
        List<String> rules = Collections.singletonList("title = non_existent_field");

        // This should throw a MappingException
        MappingException e = assertThrows(MappingException.class,
                () -> mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, rules));

        assertTrue(e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve path"));
    }

    @Test
    void applyRulesToBuilder_ErrorInSetValue() {
        List<String> rules = Collections.singletonList("non_existent_field = title");

        // This should throw a MappingException
        MappingException e = assertThrows(MappingException.class,
                () -> mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, rules));

        assertTrue(e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve path"));
    }

    @Test
    void applyRulesToBuilder_ErrorTypeMismatch() {
        List<String> rules = Collections.singletonList("creation_date = title"); // String -> Timestamp

        // This should throw a MappingException
        MappingException e = assertThrows(MappingException.class,
                () -> mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, rules));

        assertTrue(e.getMessage().contains("Type mismatch") || e.getMessage().contains("Cannot convert"));
    }

    @Test
    void applyRulesToBuilder_MultipleRules() throws MappingException {
        List<String> rules = Arrays.asList(
                "id = id",
                "title = title",
                "body = \"New body content\"", // Literal assignment
                "keywords += \"tag1\"",        // Append to repeated field
                "keywords += \"tag2\""         // Append another item
        );

        // Apply rules
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, rules);

        // Verify results
        assertEquals("src-id", targetBuilder.getId());
        assertEquals("Src Title", targetBuilder.getTitle());
        assertEquals("New body content", targetBuilder.getBody());
        assertEquals(Arrays.asList("tag1", "tag2"), targetBuilder.getKeywordsList());
    }

    @Test
    void applyRulesToBuilder_StructOperations() throws MappingException {
        List<String> rules = Arrays.asList(
                "custom_data.string_field = title",
                "custom_data.number_field = 42.5",
                "custom_data.bool_field = true",
                "custom_data.null_field = null"
        );

        // Apply rules
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, rules);

        // Verify results
        assertTrue(targetBuilder.hasCustomData());
        Struct customData = targetBuilder.getCustomData();
        assertEquals("Src Title", customData.getFieldsOrThrow("string_field").getStringValue());
        assertEquals(42.5, customData.getFieldsOrThrow("number_field").getNumberValue());
        assertTrue(customData.getFieldsOrThrow("bool_field").getBoolValue());
        assertEquals(Value.KindCase.NULL_VALUE, customData.getFieldsOrThrow("null_field").getKindCase());
    }
}