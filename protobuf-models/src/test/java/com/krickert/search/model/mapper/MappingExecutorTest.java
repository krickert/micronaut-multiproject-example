package com.krickert.search.model.mapper;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.pipe.Embedding;
import com.krickert.search.model.pipe.PipeDoc; // Using PipeDoc for concrete examples
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MappingExecutorTest {

    @Mock
    private RuleParser mockRuleParser;
    @Mock
    private PathResolver mockPathResolver; // Needed for deletion path resolution
    @Mock
    private ValueHandler mockValueHandler;

    private MappingExecutor mappingExecutor;

    private PipeDoc sourceMessage;
    private PipeDoc.Builder targetBuilder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Pass dependencies
        mappingExecutor = new MappingExecutor(mockRuleParser, mockPathResolver, mockValueHandler);

        sourceMessage = PipeDoc.newBuilder().setId("src-id").setTitle("Src Title").build();
        targetBuilder = PipeDoc.newBuilder();
    }

    @Test
    void applyRulesToBuilder_AssignAndAppend() throws MappingException {
        String ruleStr1 = "title = title";
        String ruleStr2 = "keywords += id";
        List<String> ruleStrings = Arrays.asList(ruleStr1, ruleStr2);

        MappingRule rule1 = MappingRule.createAssignRule("title", "title", ruleStr1);
        MappingRule rule2 = MappingRule.createAppendRule("keywords", "id", ruleStr2);
        List<MappingRule> parsedRules = Arrays.asList(rule1, rule2);

        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(parsedRules);
        when(mockValueHandler.getValue(same(sourceMessage), eq("title"), eq(ruleStr1))).thenReturn("Src Title");
        when(mockValueHandler.getValue(same(sourceMessage), eq("id"), eq(ruleStr2))).thenReturn("src-id");

        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);

        // Verify mocks were called in order
        InOrder inOrder = inOrder(mockRuleParser, mockValueHandler);
        inOrder.verify(mockRuleParser).parseRules(ruleStrings);
        // Rule 1 (Assign)
        inOrder.verify(mockValueHandler).getValue(same(sourceMessage), eq("title"), eq(ruleStr1));
        inOrder.verify(mockValueHandler).setValue(same(targetBuilder), eq("title"), eq("Src Title"), eq("="), eq(ruleStr1));
        // Rule 2 (Append)
        inOrder.verify(mockValueHandler).getValue(same(sourceMessage), eq("id"), eq(ruleStr2));
        inOrder.verify(mockValueHandler).setValue(same(targetBuilder), eq("keywords"), eq("src-id"), eq("+="), eq(ruleStr2));

        verifyNoMoreInteractions(mockRuleParser, mockValueHandler);
         // PathResolver is not directly used for assign/append in this structure
         verifyNoInteractions(mockPathResolver);
    }

     @Test
    void applyRulesToBuilder_MapPut() throws MappingException {
        String ruleStr = "embeddings[\"new\"] = embeddings[\"old\"]";
        List<String> ruleStrings = Collections.singletonList(ruleStr);

        MappingRule rule = MappingRule.createMapPutRule("embeddings", "new", "embeddings[\"old\"]", ruleStr);
        List<MappingRule> parsedRules = Collections.singletonList(rule);

        Object mockEmbedding =
                PipeDoc.newBuilder().putEmbeddings("mock", Embedding.newBuilder().addEmbedding(1f).build()).getEmbeddingsMap().get("mock"); // Example value

        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(parsedRules);
        when(mockValueHandler.getValue(same(sourceMessage), eq("embeddings[\"old\"]"), eq(ruleStr))).thenReturn(mockEmbedding);

        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);

        verify(mockRuleParser).parseRules(ruleStrings);
        verify(mockValueHandler).getValue(same(sourceMessage), eq("embeddings[\"old\"]"), eq(ruleStr));
        // Verify setValue called with the full target spec and special operator
        verify(mockValueHandler).setValue(same(targetBuilder), eq("embeddings[\"new\"]"), same(mockEmbedding), eq("[]="), eq(ruleStr));
        verifyNoMoreInteractions(mockRuleParser, mockValueHandler);
        verifyNoInteractions(mockPathResolver);
    }

     @Test
    void applyRulesToBuilder_DeleteField() throws MappingException {
        String ruleStr = "-title";
        List<String> ruleStrings = Collections.singletonList(ruleStr);

        MappingRule rule = MappingRule.createDeleteRule("title", ruleStr);
        List<MappingRule> parsedRules = Collections.singletonList(rule);

        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(parsedRules);

        // Mock PathResolver directly for deletion path (as ValueHandler doesn't handle delete)
        PathResolverResult mockResolveResult = new PathResolverResult(
            targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName("title"), null, false, false);
        when(mockPathResolver.resolvePath(same(targetBuilder), eq("title"), eq(true), eq(ruleStr)))
            .thenReturn(mockResolveResult);

        // Set an initial value to verify it gets cleared
        targetBuilder.setTitle("Initial Title");

        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);

        // Verify PathResolver was called for deletion
        verify(mockPathResolver).resolvePath(same(targetBuilder), eq("title"), eq(true), eq(ruleStr));
        // Verify the field was cleared on the builder (no direct mock for clearField, check result)
        assertEquals("", targetBuilder.getTitle()); // Check it's default/empty

        verify(mockRuleParser).parseRules(ruleStrings);
         verifyNoInteractions(mockValueHandler); // ValueHandler not used for delete
        verifyNoMoreInteractions(mockPathResolver);
    }

    @Test
    void applyRulesToBuilder_DeleteStructKey() throws MappingException {
        String ruleStr = "-custom_data.keyToDelete";
        String targetPath = "custom_data.keyToDelete";
        String structFieldName = "custom_data";
        String key = "keyToDelete";
        List<String> ruleStrings = Collections.singletonList(ruleStr);

        MappingRule rule = MappingRule.createDeleteRule(targetPath, ruleStr);
        List<MappingRule> parsedRules = Collections.singletonList(rule);

        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(parsedRules);

        // Mock PathResolver for struct key deletion
        PathResolverResult mockResolveResult = new PathResolverResult(
            targetBuilder, // Parent builder holding the struct field
            null,
            PipeDoc.getDescriptor().findFieldByName(structFieldName), // The struct field itself
            null,
            key, // The key to delete
            true, false); // isStructKey = true
        when(mockPathResolver.resolvePath(same(targetBuilder), eq(targetPath), eq(true), eq(ruleStr)))
            .thenReturn(mockResolveResult);

        // Mock ValueHandler's getStructBuilder helper
        // This builder will be manipulated by the executor
        Struct.Builder mockStructBuilderReturnedByHandler = Struct.newBuilder();
        mockStructBuilderReturnedByHandler.putFields("keepKey", Value.newBuilder().setStringValue("keep").build());
        mockStructBuilderReturnedByHandler.putFields(key, Value.newBuilder().setNumberValue(123).build()); // Key exists initially
        when(mockValueHandler.getStructBuilder(same(targetBuilder), eq(PipeDoc.getDescriptor().findFieldByName(structFieldName)), eq(ruleStr)))
            .thenReturn(mockStructBuilderReturnedByHandler);

        // Set initial struct data on the actual targetBuilder to simulate state before deletion
        targetBuilder.setCustomData(Struct.newBuilder()
            .putFields("keepKey", Value.newBuilder().setStringValue("keep").build())
            .putFields(key, Value.newBuilder().setNumberValue(123).build()));

        // --- Execute the method under test ---
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);

        // --- Verify interactions with mocks ---
        verify(mockRuleParser).parseRules(ruleStrings);
        verify(mockPathResolver).resolvePath(same(targetBuilder), eq(targetPath), eq(true), eq(ruleStr));
        // Verify getStructBuilder was called to get the builder to modify
        verify(mockValueHandler).getStructBuilder(same(targetBuilder), eq(PipeDoc.getDescriptor().findFieldByName(structFieldName)), eq(ruleStr));

        // --- Assert the final state of the actual targetBuilder ---
        // Check the key was removed from the mock builder that the handler returned (important internal step)
        assertFalse(mockStructBuilderReturnedByHandler.containsFields(key), "Key should have been removed from the mock struct builder returned by getStructBuilder");

        // Assert the final state of the *actual* targetBuilder reflects the deletion
        assertTrue(targetBuilder.hasCustomData(), "Target builder should still have custom data");
        assertFalse(targetBuilder.getCustomData().containsFields(key), "keyToDelete should be absent in targetBuilder's final custom_data");
        assertTrue(targetBuilder.getCustomData().containsFields("keepKey"), "keepKey should still be present in targetBuilder's final custom_data");
        assertEquals(1, targetBuilder.getCustomData().getFieldsCount(), "Final field count should be 1");

        // --- Verify no other interactions ---
        // getStructBuilder was called, but not getValue/setValue directly for this deletion path
        verifyNoMoreInteractions(mockValueHandler);
        verifyNoMoreInteractions(mockPathResolver);
    }


     @Test
     void applyRulesToBuilder_DeleteField_NotFoundIgnored() throws MappingException {
         String ruleStr = "-non_existent_field";
         List<String> ruleStrings = Collections.singletonList(ruleStr);

         MappingRule rule = MappingRule.createDeleteRule("non_existent_field", ruleStr);
         List<MappingRule> parsedRules = Collections.singletonList(rule);

         when(mockRuleParser.parseRules(ruleStrings)).thenReturn(parsedRules);

         // Mock PathResolver to throw a "not found" type error during deletion attempt
         MappingException notFoundException = new MappingException("Field not found: 'non_existent_field'", ruleStr);
         when(mockPathResolver.resolvePath(same(targetBuilder), eq("non_existent_field"), eq(true), eq(ruleStr)))
             .thenThrow(notFoundException);

         // Execution should NOT throw an exception
         assertDoesNotThrow(() -> {
             mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
         });

         // Verify PathResolver was called
         verify(mockPathResolver).resolvePath(same(targetBuilder), eq("non_existent_field"), eq(true), eq(ruleStr));
         // Verify no other interactions happened after the path error
         verify(mockRuleParser).parseRules(ruleStrings);
         verifyNoInteractions(mockValueHandler);
         verifyNoMoreInteractions(mockPathResolver);
     }

     @Test
    void applyRulesToBuilder_ErrorInRuleParsing() throws MappingException {
        List<String> ruleStrings = Collections.singletonList("invalid rule syntax = =");
        MappingException parseException = new MappingException("Invalid syntax", "invalid rule syntax = =");
        when(mockRuleParser.parseRules(ruleStrings)).thenThrow(parseException);

        MappingException e = assertThrows(MappingException.class, () -> {
            mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
        });

        assertSame(parseException, e); // Should propagate the parser exception
        verify(mockRuleParser).parseRules(ruleStrings);
        verifyNoInteractions(mockPathResolver, mockValueHandler);
    }

    @Test
    void applyRulesToBuilder_ErrorInGetValue() throws MappingException {
        String ruleStr = "title = source_error";
        List<String> ruleStrings = Collections.singletonList(ruleStr);
        MappingRule rule = MappingRule.createAssignRule("title", "source_error", ruleStr);
        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(Collections.singletonList(rule));

        MappingException valueException = new MappingException("Cannot get value", ruleStr);
        when(mockValueHandler.getValue(any(), eq("source_error"), eq(ruleStr))).thenThrow(valueException);

        MappingException e = assertThrows(MappingException.class, () -> {
            mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
        });

        assertSame(valueException, e); // Should propagate the getValue exception
        verify(mockRuleParser).parseRules(ruleStrings);
        verify(mockValueHandler).getValue(any(), eq("source_error"), eq(ruleStr));
        verifyNoMoreInteractions(mockValueHandler); // setValue should not be called
        verifyNoInteractions(mockPathResolver);
    }

    @Test
    void applyRulesToBuilder_ErrorInSetValue() throws MappingException {
        String ruleStr = "error_target = title";
        List<String> ruleStrings = Collections.singletonList(ruleStr);
        MappingRule rule = MappingRule.createAssignRule("error_target", "title", ruleStr);
        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(Collections.singletonList(rule));
        when(mockValueHandler.getValue(any(), eq("title"), eq(ruleStr))).thenReturn("Some Title");

        MappingException valueException = new MappingException("Cannot set value", ruleStr);
        doThrow(valueException).when(mockValueHandler).setValue(any(), eq("error_target"), any(), eq("="), eq(ruleStr));

        MappingException e = assertThrows(MappingException.class, () -> {
            mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
        });

        assertSame(valueException, e); // Should propagate the setValue exception
        verify(mockRuleParser).parseRules(ruleStrings);
        verify(mockValueHandler).getValue(any(), eq("title"), eq(ruleStr));
        verify(mockValueHandler).setValue(any(), eq("error_target"), eq("Some Title"), eq("="), eq(ruleStr));
        verifyNoMoreInteractions(mockValueHandler);
        verifyNoInteractions(mockPathResolver);
    }

     @Test
    void applyRulesToBuilder_ErrorInDeletionPathResolve() throws MappingException {
        String ruleStr = "-error_path";
        List<String> ruleStrings = Collections.singletonList(ruleStr);
        MappingRule rule = MappingRule.createDeleteRule("error_path", ruleStr);
        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(Collections.singletonList(rule));

        MappingException pathException = new MappingException("Cannot resolve path", ruleStr);
         when(mockPathResolver.resolvePath(same(targetBuilder), eq("error_path"), eq(true), eq(ruleStr)))
             .thenThrow(pathException);

        MappingException e = assertThrows(MappingException.class, () -> {
            mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
        });

        assertSame(pathException, e); // Should propagate the path exception
        verify(mockRuleParser).parseRules(ruleStrings);
        verify(mockPathResolver).resolvePath(same(targetBuilder), eq("error_path"), eq(true), eq(ruleStr));
        verifyNoInteractions(mockValueHandler);
        verifyNoMoreInteractions(mockPathResolver);
    }


    @Test
    void applyRulesToBuilder_UnexpectedExceptionGetValue() throws MappingException {
        String ruleStr = "a = b";
        List<String> ruleStrings = Collections.singletonList(ruleStr);
        MappingRule rule = MappingRule.createAssignRule("a", "b", ruleStr);
        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(Collections.singletonList(rule));

        RuntimeException unexpectedEx = new RuntimeException("Something went wrong");
        when(mockValueHandler.getValue(any(), eq("b"), eq(ruleStr))).thenThrow(unexpectedEx);

        MappingException e = assertThrows(MappingException.class, () -> {
            mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
        });

        assertTrue(e.getMessage().contains("Unexpected error executing rule"));
        assertSame(unexpectedEx, e.getCause()); // Check underlying cause
        assertEquals(ruleStr, e.getFailedRule()); // Check rule is attached
    }

     @Test
    void applyRulesToBuilder_UnexpectedExceptionSetValue() throws MappingException {
        String ruleStr = "a = b";
        List<String> ruleStrings = Collections.singletonList(ruleStr);
        MappingRule rule = MappingRule.createAssignRule("a", "b", ruleStr);
        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(Collections.singletonList(rule));
        when(mockValueHandler.getValue(any(), eq("b"), eq(ruleStr))).thenReturn("Value");

        RuntimeException unexpectedEx = new RuntimeException("Something went wrong");
        doThrow(unexpectedEx).when(mockValueHandler).setValue(any(), eq("a"), any(), eq("="), eq(ruleStr));


        MappingException e = assertThrows(MappingException.class, () -> {
            mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
        });

        assertTrue(e.getMessage().contains("Unexpected error executing rule"));
        assertSame(unexpectedEx, e.getCause());
        assertEquals(ruleStr, e.getFailedRule());
    }

     @Test
    void applyRulesToBuilder_UnexpectedExceptionDeletion() throws MappingException {
        String ruleStr = "-a";
        List<String> ruleStrings = Collections.singletonList(ruleStr);
        MappingRule rule = MappingRule.createDeleteRule("a", ruleStr);
        when(mockRuleParser.parseRules(ruleStrings)).thenReturn(Collections.singletonList(rule));

        RuntimeException unexpectedEx = new RuntimeException("Something went wrong");
        when(mockPathResolver.resolvePath(any(), eq("a"), eq(true), eq(ruleStr))).thenThrow(unexpectedEx);

        MappingException e = assertThrows(MappingException.class, () -> {
            mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, ruleStrings);
        });

        assertTrue(e.getMessage().contains("Unexpected error executing rule") || e.getMessage().contains("Error executing deletion rule")); // Message might differ slightly
        assertSame(unexpectedEx, e.getCause());
        assertEquals(ruleStr, e.getFailedRule());
    }
}