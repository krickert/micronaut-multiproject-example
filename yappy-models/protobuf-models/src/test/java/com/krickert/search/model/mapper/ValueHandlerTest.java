package com.krickert.search.model.mapper;

import com.google.protobuf.*;
import com.krickert.search.model.Embedding;
import com.krickert.search.model.PipeDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ValueHandler class without using Mockito.
 * This test uses real PathResolver instead of mocks.
 */
class ValueHandlerTest {

    private PathResolver pathResolver;
    private ValueHandler valueHandler;

    // Source messages/builders for testing
    private PipeDoc sourceMessage;
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
        pathResolver = new PathResolver();
        valueHandler = new ValueHandler(pathResolver);

        sampleTimestamp = Timestamp.newBuilder().setSeconds(1609459200).setNanos(12345).build(); // Jan 1 2021
        sampleEmbedding1 = Embedding.newBuilder().addVector(0.1f).addVector(0.2f).build();
        sampleEmbedding2 = Embedding.newBuilder().addVector(0.3f).addVector(0.4f).build();
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

        // Create a source message with various field types for testing
        sourceMessage = PipeDoc.newBuilder()
                .setId("doc-1")
                .setTitle("Test Title")
                .addKeywords("tag1")
                .addKeywords("tag2")
                .setCreationDate(sampleTimestamp)
                .putNamedEmbeddings("keyA", sampleEmbedding1)
                .putNamedEmbeddings("keyB", sampleEmbedding2)
                .setCustomData(sampleStruct)
                .build();

        targetBuilder = PipeDoc.newBuilder();
    }

    // --- getValue Tests ---

    @Test
    void getValue_SimpleField() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "title", "rule1");
        assertEquals("Test Title", value);
    }

    @Test
    void getValue_DoubleFromStruct() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "custom_data.numKey", "ruleGetDouble");
        assertEquals(987.65, value); // Should be Double
        assertInstanceOf(Double.class, value);
    }

    @Test
    void getValue_TimestampField() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "creation_date", "rule1");
        assertEquals(sampleTimestamp, value);
    }

    @Test
    void getValue_MapValue() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "named_embeddings[\"keyA\"]", "rule3");
        assertEquals(sampleEmbedding1, value);
    }

    @Test
    void getValue_MapValue_NotFound() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "named_embeddings[\"nonExistentKey\"]", "rule3");
        assertNull(value); // Should return null if key not found
    }

    @Test
    void getValue_StructValue_String() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "custom_data.strKey", "rule4");
        assertEquals("struct string", value); // Should be unwrapped Java String
    }

    @Test
    void getValue_StructValue_Number() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "custom_data.numKey", "rule4");
        assertEquals(987.65, value); // Should be unwrapped Java Double
    }

    @Test
    void getValue_StructValue_Boolean() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "custom_data.boolKey", "rule4");
        assertEquals(true, value); // Should be unwrapped Java Boolean
    }

    @Test
    void getValue_StructValue_Null() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "custom_data.nullKey", "rule4");
        assertNull(value); // Should be unwrapped Java null
    }

    @Test
    void getValue_StructValue_List() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "custom_data.listKey", "rule4");
        // Should be unwrapped Java List containing unwrapped Java values
        assertInstanceOf(List.class, value);
        List<?> list = (List<?>) value;
        assertEquals(2, list.size());
        assertEquals("struct string", list.get(0));
        assertEquals(987.65, list.get(1));
    }

    @Test
    void getValue_StructValue_NotFound() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "custom_data.nonExistentKey", "rule4");
        assertNull(value); // Should return null if key not found
    }

    @Test
    void getValue_RepeatedField() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "keywords", "rule5");
        assertInstanceOf(List.class, value);
        assertEquals(Arrays.asList("tag1", "tag2"), value);
    }

    @Test
    void getValue_FieldNotSet() throws MappingException {
        Object value = valueHandler.getValue(sourceMessage, "body", "rule6");
        assertEquals("", value); // Default for string
    }

    @Test
    void getValue_MapFieldNotSet() throws MappingException {
        PipeDoc emptySource = PipeDoc.newBuilder().build();
        Object value = valueHandler.getValue(emptySource, "named_embeddings", "ruleMapEmpty");
        assertNotNull(value);
        assertInstanceOf(Map.class, value);
        assertTrue(((Map<?, ?>) value).isEmpty());
    }

    @Test
    void getValue_RepeatedFieldNotSet() throws MappingException {
        PipeDoc emptySource = PipeDoc.newBuilder().build();
        Object value = valueHandler.getValue(emptySource, "keywords", "ruleListEmpty");
        assertNotNull(value);
        assertInstanceOf(List.class, value);
        assertTrue(((List<?>) value).isEmpty());
    }

    @Test
    void getValue_ErrorDuringPathResolution() {
        String path = "invalid.path.that.does.not.exist";
        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.getValue(sourceMessage, path, "rule7");
        });
        assertTrue(e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve path"));
    }

    // --- setValue Tests ---

    @Test
    void setValue_SimpleField_Assign() throws MappingException {
        String path = "title";
        Object sourceValue = "New Title";

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSet1");
        assertEquals("New Title", targetBuilder.getTitle());
    }

    @Test
    void setValue_SimpleField_Assign_TypeConversion_DoubleToString() throws MappingException {
        String path = "title";
        Object sourceValue = 123.45; // Double

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetConvertDouble");
        assertEquals("123.45", targetBuilder.getTitle()); // Should be converted to String
    }

    @Test
    void setValue_SimpleField_Assign_TypeConversion_StringToTimestampError() {
        String path = "creation_date"; // Timestamp field
        Object sourceValue = "not a timestamp"; // String

        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetConvertFail");
        });
        assertTrue(e.getMessage().contains("Type mismatch") || e.getMessage().contains("Cannot convert"));
        assertTrue(e.getMessage().contains("STRING to MESSAGE"));
    }

    @Test
    void setValue_RepeatedField_AssignList() throws MappingException {
        String path = "keywords";
        Object sourceValue = Arrays.asList("newTag1", "newTag2");

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetListAssign");
        assertEquals(Arrays.asList("newTag1", "newTag2"), targetBuilder.getKeywordsList());
    }

    @Test
    void setValue_RepeatedField_AppendItem() throws MappingException {
        String path = "keywords";
        Object sourceValue = "appendTag";

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetListAppend");
        assertEquals(Arrays.asList("oldTag", "appendTag"), targetBuilder.getKeywordsList());
    }

    @Test
    void setValue_RepeatedField_AppendList() throws MappingException {
        String path = "keywords";
        Object sourceValue = Arrays.asList("appendTag1", "appendTag2");

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetListAppendList");
        assertEquals(Arrays.asList("oldTag", "appendTag1", "appendTag2"), targetBuilder.getKeywordsList());
    }

    @Test
    void setValue_RepeatedField_AppendItem_TypeConversion() throws MappingException {
        String path = "keywords"; // List<String>
        Object sourceValue = 987; // int

        targetBuilder.addKeywords("oldTag");

        valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetListAppendConvert");
        assertEquals(Arrays.asList("oldTag", "987"), targetBuilder.getKeywordsList());
    }

    @Test
    void setValue_RepeatedField_Assign_WrongSourceType() {
        String path = "keywords"; // List<String>
        Object sourceValue = 123; // int (not a List)

        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetListAssignWrong");
        });
        assertTrue(e.getMessage().contains("Cannot assign single value"), "Expected message about assigning single value to list");
        assertTrue(e.getMessage().contains("using '='"), "Expected message mentioning '=' operator");
    }

    @Test
    void setValue_MapField_AssignMap() throws MappingException {
        String path = "named_embeddings"; // map<string, Embedding>
        Map<String, Embedding> sourceValue = new HashMap<>();
        sourceValue.put("newK1", sampleEmbedding1);
        sourceValue.put("newK2", sampleEmbedding2);

        targetBuilder.putNamedEmbeddings("oldK", sampleEmbedding1);

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetMapAssign");
        assertEquals(2, targetBuilder.getNamedEmbeddingsMap().size());
        assertEquals(sampleEmbedding1, targetBuilder.getNamedEmbeddingsMap().get("newK1"));
        assertEquals(sampleEmbedding2, targetBuilder.getNamedEmbeddingsMap().get("newK2"));
        assertFalse(targetBuilder.getNamedEmbeddingsMap().containsKey("oldK"));
    }

    @Test
    void setValue_MapField_AppendMap() throws MappingException {
        String path = "named_embeddings"; // map<string, Embedding>
        Map<String, Embedding> sourceValue = new HashMap<>();
        sourceValue.put("keyB", sampleEmbedding1); // Overwrite existing keyB
        sourceValue.put("keyC", sampleEmbedding2); // Add new keyC

        targetBuilder.putNamedEmbeddings("keyA", sampleEmbedding1); // Pre-existing A
        targetBuilder.putNamedEmbeddings("keyB", sampleEmbedding2); // Pre-existing B

        valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetMapAppend");
        assertEquals(3, targetBuilder.getNamedEmbeddingsMap().size());
        assertEquals(sampleEmbedding1, targetBuilder.getNamedEmbeddingsMap().get("keyA"));
        assertEquals(sampleEmbedding1, targetBuilder.getNamedEmbeddingsMap().get("keyB")); // Overwritten
        assertEquals(sampleEmbedding2, targetBuilder.getNamedEmbeddingsMap().get("keyC"));
    }

    @Test
    void setValue_MapField_MapPut() throws MappingException {
        String path = "named_embeddings[\"putKey\"]"; // Special path format for map put
        Object sourceValue = sampleEmbedding1;

        targetBuilder.putNamedEmbeddings("existingKey", sampleEmbedding2);

        valueHandler.setValue(targetBuilder, path, sourceValue, "[]=", "ruleSetMapPut");

        assertEquals(2, targetBuilder.getNamedEmbeddingsMap().size());
        assertEquals(sampleEmbedding2, targetBuilder.getNamedEmbeddingsMap().get("existingKey"));
        assertEquals(sampleEmbedding1, targetBuilder.getNamedEmbeddingsMap().get("putKey"));
    }

    @Test
    void setValue_MapField_MapPut_TypeConversionError() {
        String path = "named_embeddings[\"putKey\"]";
        Object sourceValue = "not an embedding"; // Wrong type

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

        targetBuilder.setCustomData(Struct.newBuilder().putFields("existingKey", numberValue).build());

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

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetStructNum");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("numKey"));
    }

    @Test
    void setValue_StructField_Assign_Boolean() throws MappingException {
        String path = "custom_data.boolKey";
        Object sourceValue = false;
        Value expectedValueProto = Value.newBuilder().setBoolValue(false).build();

        valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetStructBool");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("boolKey"));
    }

    @Test
    void setValue_StructField_Assign_Null() throws MappingException {
        String path = "custom_data.nullKey";
        Object sourceValue = null;
        Value expectedValueProto = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();

        targetBuilder.setCustomData(Struct.newBuilder().putFields("nullKey", numberValue).build());

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

        valueHandler.setValue(targetBuilder, path, sourceMap, "=", "ruleSetStructMap");

        assertTrue(targetBuilder.hasCustomData());
        assertEquals(expectedValueProto, targetBuilder.getCustomDataOrBuilder().getFieldsOrThrow("mapKey"));
    }

    @Test
    void setValue_ErrorDuringPathResolution() {
        String path = "invalid.path.that.does.not.exist";
        Object sourceValue = "value";

        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetFail");
        });
        assertTrue(e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve path"));
    }

    @Test
    void setValue_SingularField_AppendError() {
        String path = "title";
        Object sourceValue = "append title";

        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.setValue(targetBuilder, path, sourceValue, "+=", "ruleSetAppendFail");
        });
        assertTrue(e.getMessage().contains("Operator '+=' only supported for repeated or map fields"));
    }

    @Test
    void setValue_MapKeyAccess_WrongOperator() {
        String path = "named_embeddings[\"putKey\"]"; // Map key path
        Object sourceValue = sampleEmbedding1;

        MappingException e = assertThrows(MappingException.class, () -> {
            valueHandler.setValue(targetBuilder, path, sourceValue, "=", "ruleSetMapPutWrongOp"); // Use = instead of []=
        });
        assertTrue(e.getMessage().contains("Invalid operator '=' used with map key syntax"));
    }
}
