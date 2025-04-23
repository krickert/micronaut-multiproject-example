package com.krickert.search.model.mapper;

import com.google.protobuf.*;
import com.krickert.search.model.pipe.PipeDoc; // Using PipeDoc for concrete examples
import com.krickert.search.model.pipe.SemanticDoc;
import com.krickert.search.model.pipe.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("FieldCanBeLocal")
class ValueHandlerTest {

    @Mock
    private PathResolver mockPathResolver;

    private ValueHandler valueHandler;

    // Source messages/builders for testing
    private PipeDoc sourceMessage;
    private PipeDoc.Builder sourceBuilder;
    private PipeDoc.Builder targetBuilder;

    // Sample data
    private Timestamp sampleTimestamp;
    private Embedding sampleEmbedding1;
    private Embedding sampleEmbedding2;
    private Struct sampleStruct;
    private Value stringValue;
    private Value numberValue;
    private Value boolValue;
    private Value nullValueProto;
    private Value listValueProto;
    private ListValue listValueContent;


    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        valueHandler = new ValueHandler(mockPathResolver);

        sampleTimestamp = Timestamp.newBuilder().setSeconds(1609459200).setNanos(12345).build(); // Jan 1 2021
        sampleEmbedding1 = Embedding.newBuilder().addAllEmbedding(Arrays.asList(0.1f, 0.2f)).build();
        sampleEmbedding2 = Embedding.newBuilder().addAllEmbedding(Arrays.asList(0.3f, 0.4f)).build();
        stringValue = Value.newBuilder().setStringValue("struct string").build();
        numberValue = Value.newBuilder().setNumberValue(987.65).build();
        boolValue = Value.newBuilder().setBoolValue(true).build();
        nullValueProto = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        listValueContent = ListValue.newBuilder().addValues(stringValue).addValues(numberValue).build();
        listValueProto = Value.newBuilder().setListValue(listValueContent).build();

        sampleStruct = Struct.newBuilder()
                .putFields("strKey", stringValue)
                .putFields("numKey", numberValue)
                .putFields("boolKey", boolValue)
                .putFields("nullKey", nullValueProto)
                .putFields("listKey", listValueProto)
                .build();


        sourceMessage = PipeDoc.newBuilder()
                .setId("doc-1")
                .setTitle("Test Title")
                .addKeywords("tag1")
                .addKeywords("tag2")
                .setCreationDate(sampleTimestamp)
                .setChunkEmbeddings(SemanticDoc.newBuilder()
                        .setParentId("pid-1")
                        .setSemanticConfigId("cfg-abc")) // Used valid field
                .putEmbeddings("keyA", sampleEmbedding1)
                .putEmbeddings("keyB", sampleEmbedding2)
                .setCustomData(sampleStruct)
                .build();

        sourceBuilder = sourceMessage.toBuilder();
        targetBuilder = PipeDoc.newBuilder();
    }

    // --- getValue Tests ---

    private void mockResolvePathForGet(String path, PathResolverResult result) throws MappingException {
        when(mockPathResolver.resolvePath(any(MessageOrBuilder.class), eq(path), eq(false), anyString()))
                .thenReturn(result);
         // Mock specifically for sourceMessage if needed, but MessageOrBuilder should cover it
         when(mockPathResolver.resolvePath(same(sourceMessage), eq(path), eq(false), anyString()))
                .thenReturn(result);
         when(mockPathResolver.resolvePath(same(sourceBuilder), eq(path), eq(false), anyString()))
                 .thenReturn(result);
    }

    @Test
    void getValue_SimpleField() throws MappingException {
        String path = "title";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule1");
        assertEquals("Test Title", value);
    }

     @Test
    void getValue_DoubleFromStruct() throws MappingException {
        String path = "custom_data.numKey";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getCustomData(), sourceMessage, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "numKey", true, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "ruleGetDouble");
        assertEquals(987.65, value); // Should be Double
         assertInstanceOf(Double.class, value);
    }

      @Test
    void getValue_TimestampField() throws MappingException {
        String path = "creation_date";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule1");
        assertEquals(sampleTimestamp, value);
    }

    @Test
    void getValue_NestedField() throws MappingException {
        String path = "chunk_embeddings.parent_id"; // Use existing field
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getChunkEmbeddings(), // Parent is the SemanticDoc message
                sourceMessage, // Grandparent
                PipeDoc.getDescriptor().findFieldByName("chunk_embeddings"), // Field leading to parent
                SemanticDoc.getDescriptor().findFieldByName("parent_id"), // Target field
                null, false, false);
        mockResolvePathForGet(path, mockResult); // Assumes path resolver was called recursively

        Object value = valueHandler.getValue(sourceMessage, path, "rule2");
        assertEquals("pid-1", value);
    }

    @Test
    void getValue_MapValue() throws MappingException {
        String path = "embeddings[\"keyA\"]";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage, null, null, PipeDoc.getDescriptor().findFieldByName("embeddings"), "keyA", false, true);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule3");
        assertEquals(sampleEmbedding1, value);
    }

     @Test
    void getValue_MapValue_NotFound() throws MappingException {
        String path = "embeddings[\"nonExistentKey\"]";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage, null, null, PipeDoc.getDescriptor().findFieldByName("embeddings"), "nonExistentKey", false, true);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule3");
        assertNull(value); // Should return null if key not found
    }


    @Test
    void getValue_StructValue_String() throws MappingException {
        String path = "custom_data.strKey";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getCustomData(), // Parent is Struct
                sourceMessage, // Grandparent is PipeDoc
                PipeDoc.getDescriptor().findFieldByName("custom_data"), // Field for Struct
                null, // Target field is null for struct key access
                "strKey", // Final part is key
                true, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule4");
        assertEquals("struct string", value); // Should be unwrapped Java String
    }

     @Test
    void getValue_StructValue_Number() throws MappingException {
        String path = "custom_data.numKey";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getCustomData(), sourceMessage, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "numKey", true, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule4");
        assertEquals(987.65, value); // Should be unwrapped Java Double
    }

     @Test
    void getValue_StructValue_Boolean() throws MappingException {
        String path = "custom_data.boolKey";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getCustomData(), sourceMessage, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "boolKey", true, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule4");
        assertEquals(true, value); // Should be unwrapped Java Boolean
    }

     @Test
    void getValue_StructValue_Null() throws MappingException {
        String path = "custom_data.nullKey";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getCustomData(), sourceMessage, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "nullKey", true, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule4");
        assertNull(value); // Should be unwrapped Java null
    }

      @Test
    void getValue_StructValue_List() throws MappingException {
        String path = "custom_data.listKey";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getCustomData(), sourceMessage, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "listKey", true, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule4");
        // Should be unwrapped Java List containing unwrapped Java values
          assertInstanceOf(List.class, value);
        List<?> list = (List<?>) value;
        assertEquals(2, list.size());
        assertEquals("struct string", list.get(0));
        assertEquals(987.65, list.get(1));
    }

     @Test
    void getValue_StructValue_NotFound() throws MappingException {
        String path = "custom_data.nonExistentKey";
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage.getCustomData(), sourceMessage, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "nonExistentKey", true, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule4");
        assertNull(value); // Should return null if key not found
    }

    @Test
    void getValue_RepeatedField() throws MappingException {
        String path = "keywords";
         PathResolverResult mockResult = new PathResolverResult(
                sourceMessage, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule5");
        assertInstanceOf(List.class, value);
        assertEquals(Arrays.asList("tag1", "tag2"), value);
    }

    @Test
    void getValue_FieldNotSet() throws MappingException {
        String path = "body"; // Body is not set in sourceMessage
        PathResolverResult mockResult = new PathResolverResult(
                sourceMessage, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForGet(path, mockResult);

        Object value = valueHandler.getValue(sourceMessage, path, "rule6");
        assertEquals("", value); // Default for string
    }

     @Test
    void getValue_MapFieldNotSet() throws MappingException {
         PipeDoc emptySource = PipeDoc.newBuilder().build();
         String path = "embeddings";
         PathResolverResult mockResult = new PathResolverResult(
                 emptySource, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
          when(mockPathResolver.resolvePath(same(emptySource), eq(path), eq(false), anyString()))
                 .thenReturn(mockResult);

         Object value = valueHandler.getValue(emptySource, path, "ruleMapEmpty");
          assertNotNull(value);
         assertInstanceOf(Map.class, value);
          assertTrue(((Map<?,?>)value).isEmpty());
     }

    @Test
    void getValue_RepeatedFieldNotSet() throws MappingException {
         PipeDoc emptySource = PipeDoc.newBuilder().build();
         String path = "keywords";
         PathResolverResult mockResult = new PathResolverResult(
                 emptySource, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
          when(mockPathResolver.resolvePath(same(emptySource), eq(path), eq(false), anyString()))
                 .thenReturn(mockResult);

         Object value = valueHandler.getValue(emptySource, path, "ruleListEmpty");
          assertNotNull(value);
        assertInstanceOf(List.class, value);
          assertTrue(((List<?>)value).isEmpty());
     }


    @Test
    void getValue_ErrorDuringPathResolution() throws MappingException {
        String path = "invalid.path";
        MappingException pathException = new MappingException("Path error", "rule7");
        when(mockPathResolver.resolvePath(any(MessageOrBuilder.class), eq(path), eq(false), anyString()))
                .thenThrow(pathException);

        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.getValue(sourceMessage, path, "rule7");
        });
        assertSame(pathException, e);
    }


    // --- setValue Tests ---

    private void mockResolvePathForSet(String path, PathResolverResult result) throws MappingException {
         when(mockPathResolver.resolvePath(any(Message.Builder.class), eq(path), eq(true), anyString()))
                .thenReturn(result);
         when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                 .thenReturn(result);
    }

    @Test
    void setValue_SimpleField_Assign() throws MappingException {
        String path = "title";
        Object sourceValue = "New Title";
        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSet1");
        assertEquals("New Title", targetBuilder.getTitle());
    }

     @Test
    void setValue_SimpleField_Assign_TypeConversion_DoubleToString() throws MappingException {
        String path = "title";
        Object sourceValue = 123.45; // Double
        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetConvertDouble");
        assertEquals("123.45", targetBuilder.getTitle()); // Should be converted to String
    }

      @Test
    void setValue_SimpleField_Assign_TypeConversion_StringToTimestampError() throws MappingException {
        String path = "creation_date"; // Timestamp field
        Object sourceValue = "not a timestamp"; // String
        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        MappingException e = assertThrows(MappingException.class, () -> {
             valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetConvertFail");
         });
         assertTrue(e.getMessage().contains("Type mismatch") || e.getMessage().contains("Cannot convert"));
         assertTrue(e.getMessage().contains("STRING to MESSAGE"));
     }


    @Test
    void setValue_NestedField_Assign() throws MappingException {
        String path = "chunk_embeddings.parent_id"; // Use existing field
        Object sourceValue = "new-pid";
        SemanticDoc.Builder semanticBuilder = targetBuilder.getChunkEmbeddingsBuilder();
        PathResolverResult mockResult = new PathResolverResult(
                semanticBuilder, // Parent builder
                targetBuilder, // Grandparent builder
                 PipeDoc.getDescriptor().findFieldByName("chunk_embeddings"), // Field for parent
                SemanticDoc.getDescriptor().findFieldByName("parent_id"), // Target field
                null, false, false);
        mockResolvePathForSet(path, mockResult);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetNested");
        assertEquals("new-pid", targetBuilder.getChunkEmbeddings().getParentId());
         assertEquals("new-pid", semanticBuilder.getParentId());
    }

    @Test
    void setValue_RepeatedField_AssignList() throws MappingException {
        String path = "keywords";
        Object sourceValue = Arrays.asList("newTag1", "newTag2");
        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetListAssign");
        assertEquals(Arrays.asList("newTag1", "newTag2"), targetBuilder.getKeywordsList());
    }

    @Test
    void setValue_RepeatedField_AppendItem() throws MappingException {
        String path = "keywords";
        Object sourceValue = "appendTag";
        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetListAppend");
        assertEquals(Arrays.asList("oldTag", "appendTag"), targetBuilder.getKeywordsList());
    }

    @Test
    void setValue_RepeatedField_AppendList() throws MappingException {
        String path = "keywords";
        Object sourceValue = Arrays.asList("appendTag1", "appendTag2");
        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetListAppendList");
        assertEquals(Arrays.asList("oldTag", "appendTag1", "appendTag2"), targetBuilder.getKeywordsList());
    }

     @Test
    void setValue_RepeatedField_AppendItem_TypeConversion() throws MappingException {
        String path = "keywords"; // List<String>
        Object sourceValue = 987; // int
        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetListAppendConvert");
        assertEquals(Arrays.asList("oldTag", "987"), targetBuilder.getKeywordsList());
    }

     @Test
    void setValue_RepeatedField_Assign_WrongSourceType() throws MappingException {
         String path = "keywords"; // List<String>
         Object sourceValue = 123; // int (not a List)
         PathResolverResult mockResult = new PathResolverResult(
                 targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
         mockResolvePathForSet(path, mockResult);

         MappingException e = assertThrows(MappingException.class, () -> {
             valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetListAssignWrong");
         });
         // **FIXED:** Assert the correct error message fragment
         assertTrue(e.getMessage().contains("Cannot assign single value"), "Expected message about assigning single value to list");
         assertTrue(e.getMessage().contains("using '='"), "Expected message mentioning '=' operator");
     }


    @Test
    void setValue_MapField_AssignMap() throws MappingException {
        String path = "embeddings"; // map<string, Embedding>
        Map<String, Embedding> sourceValue = new HashMap<>();
        sourceValue.put("newK1", sampleEmbedding1);
        sourceValue.put("newK2", sampleEmbedding2);

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
        mockResolvePathForSet(path, mockResult);

        targetBuilder.putEmbeddings("oldK", sampleEmbedding1);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetMapAssign");
        assertEquals(2, targetBuilder.getEmbeddingsMap().size());
        assertEquals(sampleEmbedding1, targetBuilder.getEmbeddingsMap().get("newK1"));
        assertEquals(sampleEmbedding2, targetBuilder.getEmbeddingsMap().get("newK2"));
    }

    @Test
    void setValue_MapField_AppendMap() throws MappingException {
         String path = "embeddings"; // map<string, Embedding>
         Map<String, Embedding> sourceValue = new HashMap<>();
         sourceValue.put("keyB", sampleEmbedding1); // Overwrite existing keyB
         sourceValue.put("keyC", sampleEmbedding2); // Add new keyC

         PathResolverResult mockResult = new PathResolverResult(
                 targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
         mockResolvePathForSet(path, mockResult);

         targetBuilder.putEmbeddings("keyA", sampleEmbedding1); // Pre-existing A
         targetBuilder.putEmbeddings("keyB", sampleEmbedding2); // Pre-existing B

         valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetMapAppend");
         assertEquals(3, targetBuilder.getEmbeddingsMap().size());
         assertEquals(sampleEmbedding1, targetBuilder.getEmbeddingsMap().get("keyA"));
         assertEquals(sampleEmbedding1, targetBuilder.getEmbeddingsMap().get("keyB")); // Overwritten
         assertEquals(sampleEmbedding2, targetBuilder.getEmbeddingsMap().get("keyC"));
    }

    @Test
    void setValue_MapField_MapPut() throws MappingException {
        String path = "embeddings[\"putKey\"]"; // Special path format for map put
        Object sourceValue = sampleEmbedding1;
        String mapFieldName = "embeddings";
        String mapKey = "putKey";

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(mapFieldName), mapKey, false, true);
         when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                .thenReturn(mockResult);


        targetBuilder.putEmbeddings("existingKey", sampleEmbedding2);

        valueHandler.setValue(targetBuilder, path, sourceValue, "[]=", "ruleSetMapPut");

        assertEquals(2, targetBuilder.getEmbeddingsMap().size());
        assertEquals(sampleEmbedding2, targetBuilder.getEmbeddingsMap().get("existingKey"));
        assertEquals(sampleEmbedding1, targetBuilder.getEmbeddingsMap().get("putKey"));
    }

    @Test
    void setValue_MapField_MapPut_TypeConversionError() throws MappingException {
        String path = "embeddings[\"putKey\"]";
        Object sourceValue = "not an embedding"; // Wrong type
        String mapFieldName = "embeddings";
        String mapKey = "putKey";

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(mapFieldName), mapKey, false, true);
         when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                .thenReturn(mockResult);

        MappingException e = assertThrows(MappingException.class, () -> {
             valueHandler.setValue(targetBuilder, path, sourceValue, "[]=", "ruleSetMapPutFail");
         });
        assertTrue(e.getMessage().contains("Type mismatch") || e.getMessage().contains("Cannot convert"));
        assertTrue(e.getMessage().contains("STRING") && e.getMessage().contains("MESSAGE"));
    }

    @Test
    void setValue_StructField_Assign_String() throws MappingException {
        String path = "custom_data.strKey";
        Object sourceValue = "new struct string";
        Value expectedValueProto = Value.newBuilder().setStringValue("new struct string").build();

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, // Parent builder containing struct field
                null, // Grandparent (null for top-level)
                PipeDoc.getDescriptor().findFieldByName("custom_data"), // Parent field descriptor (the struct field)
                null, // Target field (null for struct key)
                "strKey", // Final part (the key)
                true, false); // Is struct key = true
         when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                .thenReturn(mockResult);

        targetBuilder.getCustomDataBuilder().putFields("existingKey", numberValue);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetStructStr");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(2, targetBuilder.getCustomData().getFieldsCount());
        assertEquals(numberValue, targetBuilder.getCustomData().getFieldsMap().get("existingKey"));
        assertEquals(expectedValueProto, targetBuilder.getCustomData().getFieldsMap().get("strKey"));
    }

    @Test
    void setValue_StructField_Assign_Number() throws MappingException {
        String path = "custom_data.numKey";
        Object sourceValue = 111.22;
        Value expectedValueProto = Value.newBuilder().setNumberValue(111.22).build();

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "numKey", true, false);
        when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                .thenReturn(mockResult);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetStructNum");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("numKey"));
    }

     @Test
    void setValue_StructField_Assign_Boolean() throws MappingException {
        String path = "custom_data.boolKey";
        Object sourceValue = false;
        Value expectedValueProto = Value.newBuilder().setBoolValue(false).build();

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "boolKey", true, false);
        when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                .thenReturn(mockResult);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetStructBool");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("boolKey"));
    }

     @Test
    void setValue_StructField_Assign_Null() throws MappingException {
        String path = "custom_data.nullKey";
        Object sourceValue = null;
        Value expectedValueProto = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "nullKey", true, false);
        when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                .thenReturn(mockResult);

         targetBuilder.getCustomDataBuilder().putFields("nullKey", numberValue);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetStructNull");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("nullKey"));
    }

     @Test
    void setValue_StructField_Assign_List() throws MappingException {
        String path = "custom_data.listKey";
        Object sourceValue = Arrays.asList("a", true, 123); // Mixed list -> ListValue
        Value expectedValueProto = Value.newBuilder().setListValue(ListValue.newBuilder()
                .addValues(Value.newBuilder().setStringValue("a"))
                .addValues(Value.newBuilder().setBoolValue(true))
                .addValues(Value.newBuilder().setNumberValue(123.0))
                ).build();

        PathResolverResult mockResult = new PathResolverResult(
                targetBuilder, null, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                null, "listKey", true, false);
        when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                .thenReturn(mockResult);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetStructList");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("listKey"));
    }

     @Test
    void setValue_StructField_Assign_Map() throws MappingException {
         String path = "custom_data.mapKey";
         Map<String, Object> sourceMap = new HashMap<>();
         sourceMap.put("innerStr", "hello");
         sourceMap.put("innerNum", 42);
         Value expectedValueProto = Value.newBuilder().setStructValue(Struct.newBuilder()
                 .putFields("innerStr", Value.newBuilder().setStringValue("hello").build())
                 .putFields("innerNum", Value.newBuilder().setNumberValue(42.0).build())
                 ).build();

         PathResolverResult mockResult = new PathResolverResult(
                 targetBuilder, null, PipeDoc.getDescriptor().findFieldByName("custom_data"),
                 null, "mapKey", true, false);
         when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                 .thenReturn(mockResult);

         valueHandler.setValue(targetBuilder, path, sourceMap, "=", "ruleSetStructMap");

         assertTrue(targetBuilder.hasCustomData());
         assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("mapKey"));
     }


    @Test
    void setValue_ErrorDuringPathResolution() throws MappingException {
        String path = "invalid.path";
        Object sourceValue = "value";
        MappingException pathException = new MappingException("Path error setting", "ruleSetFail");
         when(mockPathResolver.resolvePath(any(Message.Builder.class), eq(path), eq(true), anyString()))
                .thenThrow(pathException);

        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetFail");
        });
        assertSame(pathException, e);
    }

     @Test
     void setValue_SingularField_AppendError() throws MappingException {
         String path = "title";
         Object sourceValue = "append title";
          PathResolverResult mockResult = new PathResolverResult(
                 targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName(path), null, false, false);
         mockResolvePathForSet(path, mockResult);

         MappingException e = assertThrows(MappingException.class, () -> {
              valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetAppendFail");
          });
          assertTrue(e.getMessage().contains("Operator '+=' only supported for repeated or map fields"));
     }

      @Test
      void setValue_MapKeyAccess_WrongOperator() throws MappingException {
         String path = "embeddings[\"putKey\"]"; // Map key path
         Object sourceValue = sampleEmbedding1;
         PathResolverResult mockResult = new PathResolverResult(
                 targetBuilder, null, null, PipeDoc.getDescriptor().findFieldByName("embeddings"), "putKey", false, true);
          when(mockPathResolver.resolvePath(same(targetBuilder), eq(path), eq(true), anyString()))
                 .thenReturn(mockResult);

         MappingException e = assertThrows(MappingException.class, () -> {
              valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetMapPutWrongOp"); // Use = instead of []=
          });
         assertTrue(e.getMessage().contains("Invalid operator '=' used with map key syntax"));
     }


}