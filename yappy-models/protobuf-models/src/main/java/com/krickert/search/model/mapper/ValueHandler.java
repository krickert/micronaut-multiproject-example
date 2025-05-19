// src/main/java/com/krickert/search/model/ValueHandler.java (Corrected Yet Again)
package com.krickert.search.model.mapper;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles getting, setting, converting, and wrapping/unwrapping Protobuf values.
 * Logic extracted from the original ProtoMapper, with corrections based on test failures.
 */
public class ValueHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ValueHandler.class);

    private final PathResolver pathResolver;

    public ValueHandler(PathResolver pathResolver) {
        this.pathResolver = Objects.requireNonNull(pathResolver);
    }

    // --- getValue ---
    public Object getValue(Message sourceMessage, String sourcePath, String ruleForError) throws MappingException {
        // *** NEW: Check for literals FIRST ***
        sourcePath = sourcePath.trim(); // Ensure no leading/trailing spaces
        if (sourcePath.equals("null")) {
            return null;
        } else if (sourcePath.equals("true")) {
            return true;
        } else if (sourcePath.equals("false")) {
            return false;
        } else if (sourcePath.startsWith("\"") && sourcePath.endsWith("\"") && sourcePath.length() >= 2) {
            return sourcePath.substring(1, sourcePath.length() - 1); // Return string content without quotes
        } else {
            try {
                // Try parsing as a number (Double is a safe intermediate)
                return Double.parseDouble(sourcePath);
            } catch (NumberFormatException nfe) {
                // Not a number, proceed to path resolution
            }
        }
        try {
            PathResolverResult sourceRes = pathResolver.resolvePath(sourceMessage, sourcePath, false, ruleForError);

            if (sourceRes.isStructKey()) {
                Object parent = sourceRes.getParentMessageOrBuilder();
                if (!(parent instanceof Struct sourceStruct)) {
                    throw new MappingException("Parent object is not a Struct when reading Struct key for path: " + sourcePath, null, ruleForError);
                }
                Value valueProto = sourceStruct.getFieldsOrDefault(sourceRes.getFinalPathPart(), null);

                // *** FIX: Check if valueProto is null first ***
                //noinspection ConstantValue
                if (valueProto == null || valueProto.getKindCase() == Value.KindCase.NULL_VALUE) {
                    return null; // Key not found or explicitly null
                }
                // *** END FIX ***

                return unwrapValue(valueProto);

            } else if (sourceRes.isMapKey()) {
                FieldDescriptor mapField = sourceRes.getTargetField();
                Object parentObj = sourceRes.getParentMessageOrBuilder();
                if (!(parentObj instanceof MessageOrBuilder parentMsgOrBuilder)) {
                    throw new MappingException("Parent object is not readable when getting map value for path: " + sourcePath, null, ruleForError);
                }
                if (parentMsgOrBuilder.getRepeatedFieldCount(mapField) == 0) {
                    return null;
                }
                Object mapFieldValue = parentMsgOrBuilder.getField(mapField);
                if (!(mapFieldValue instanceof List<?> mapEntries)) {
                    throw new MappingException("Field '" + mapField.getName() + "' did not return a List object (got " + (mapFieldValue != null ? mapFieldValue.getClass().getName() : "null") + ").", null, ruleForError);
                }
                Descriptor mapEntryDesc = mapField.getMessageType();
                FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key");
                FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");
                if (keyDesc == null || valueDesc == null) {
                    throw new MappingException("Map entry descriptor missing key or value field: " + mapEntryDesc.getFullName(), ruleForError);
                }
                Object searchKey = convertSingleValue(sourceRes.getFinalPathPart(), keyDesc.getJavaType(), null, null, mapField.getName() + "[key]", ruleForError);
                for (Object entryObj : mapEntries) {
                    if (entryObj instanceof Message entryMsg) {
                        if (!entryMsg.getDescriptorForType().equals(mapEntryDesc)) {
                            LOG.warn("Map entry has unexpected type {} in field {}", entryMsg.getDescriptorForType().getFullName(), mapField.getName());
                            continue;
                        }
                        Object currentKey = entryMsg.getField(keyDesc);
                        if (Objects.equals(currentKey, searchKey)) {
                            return entryMsg.getField(valueDesc);
                        }
                    } else {
                        throw new MappingException("Map entry in field '" + mapField.getName() + "' was not a Message.", null, ruleForError);
                    }
                }
                return null;

            } else if (sourceRes.getTargetField() != null) {
                FieldDescriptor targetField = sourceRes.getTargetField();
                Object parent = sourceRes.getParentMessageOrBuilder();
                if (!(parent instanceof MessageOrBuilder parentMsgOrBuilder)) {
                    throw new MappingException("Parent object is not readable when getting field value for path: " + sourcePath, null, ruleForError);
                }
                boolean isPresent;
                if (targetField.isRepeated()) {
                    isPresent = parentMsgOrBuilder.getRepeatedFieldCount(targetField) > 0;
                } else {
                    isPresent = parentMsgOrBuilder.hasField(targetField);
                }
                if (!isPresent) {
                    if (targetField.isMapField()) return Collections.emptyMap();
                    if (targetField.isRepeated()) return Collections.emptyList();
                    return getDefaultForNullSource(targetField);
                }
                Object rawFieldValue = parentMsgOrBuilder.getField(targetField);
                if (targetField.isMapField()) {
                    if (!(rawFieldValue instanceof List<?> mapEntries)) {
                        if (rawFieldValue != null && rawFieldValue.equals(Collections.emptyList())) return Collections.emptyMap();
                        throw new MappingException("Map Field '" + targetField.getName() + "' did not return a List object (got " + (rawFieldValue != null ? rawFieldValue.getClass().getName() : "null") + ").", null, ruleForError);
                    }
                    return convertMapFieldListToJavaMap(mapEntries, targetField);
                } else {
                    return rawFieldValue;
                }
            } else {
                throw new MappingException("Could not resolve source path to a readable value: " + sourcePath, null, ruleForError);
            }
        } catch (MappingException e) {
            LOG.error("*** HANDLE path resolution errors if it's NOT a literal! Error getting value for source path '" + sourcePath + "'" +
                            " in rule '" + ruleForError + "'", e,
                    ruleForError);
            throw e;
        } catch (Exception e) {
            throw new MappingException("Error getting value for source path '" + sourcePath + "' in rule '" + ruleForError + "'", e, ruleForError);
        }
    }


    // --- setValue ---
    public void setValue(Message.Builder targetBuilder, String targetPath, Object sourceValue, String operator, String ruleForError) throws MappingException {
        try {
            PathResolverResult targetRes = pathResolver.resolvePath(targetBuilder, targetPath, true, ruleForError);

            if (targetRes.isStructKey()) {
                Object grandParentBuilderObj = targetRes.getGrandparentBuilder();
                Message.Builder structParentBuilder;
                if (grandParentBuilderObj == null && targetRes.getParentBuilder() instanceof Message.Builder) {
                    structParentBuilder = (Message.Builder) targetRes.getParentBuilder();
                } else if (grandParentBuilderObj instanceof Message.Builder) {
                    structParentBuilder = (Message.Builder) grandParentBuilderObj;
                } else {
                    throw new MappingException("Could not find valid parent builder for Struct field: " + targetPath, null, ruleForError);
                }

                FieldDescriptor actualStructField = targetRes.getParentField();
                if (actualStructField == null || !actualStructField.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName())) {
                    throw new MappingException("Resolved parent field is not a Struct for setting value: " + targetPath, null, ruleForError);
                }

                Struct.Builder structBuilder = getStructBuilder(structParentBuilder, actualStructField, ruleForError);
                Value valueProto = wrapValue(sourceValue);
                structBuilder.putFields(targetRes.getFinalPathPart(), valueProto);
                structParentBuilder.setField(actualStructField, structBuilder.build());

            } else if (targetRes.isMapKey()) {
                FieldDescriptor mapField = targetRes.getTargetField();
                if (!"[]=".equals(operator)) {
                    throw new MappingException("Invalid operator '" + operator + "' used with map key syntax.", null, ruleForError);
                }
                if (!(targetRes.getParentBuilder() instanceof Message.Builder mapParentBuilder)) {
                    throw new MappingException("Parent object is not a Message.Builder when setting map value for path: " + targetPath, null, ruleForError);
                }
                String keyString = targetRes.getFinalPathPart();

                Descriptor mapEntryDesc = mapField.getMessageType();
                FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key");
                FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");
                if (keyDesc == null || valueDesc == null) {
                    throw new MappingException("Map entry descriptor missing key or value field: " + mapEntryDesc.getFullName(), ruleForError);
                }

                Object mapKey = convertSingleValue(keyString, keyDesc.getJavaType(), null, null, mapField.getName() + "[key]", ruleForError);
                Object mapValue = convertSingleValue(sourceValue, valueDesc.getJavaType(),
                        (valueDesc.getJavaType() == JavaType.MESSAGE ? valueDesc.getMessageType() : null),
                        (valueDesc.getJavaType() == JavaType.ENUM ? valueDesc.getEnumType() : null),
                        mapField.getName() + "[value]", ruleForError);

                // *** CORRECTION for hasField START ***
                // Get current map entries (returns empty list if field not set) - DO NOT use hasField on mapField with Builder
                Object currentMapObj = mapParentBuilder.getField(mapField);
                // *** CORRECTION for hasField END ***
                Map<Object, Object> mutableMap = reconstructMapForSet(currentMapObj, mapField);

                mutableMap.put(mapKey, mapValue);

                List<Message> mapEntriesList = convertJavaMapToMapFieldList(mutableMap, mapField);
                mapParentBuilder.clearField(mapField);
                for (Message entryMsg : mapEntriesList) {
                    mapParentBuilder.addRepeatedField(mapField, entryMsg);
                }

            } else if (targetRes.getTargetField() != null) {
                FieldDescriptor targetField = targetRes.getTargetField();
                if (!(targetRes.getParentBuilder() instanceof Message.Builder parentBuilder)) {
                    throw new MappingException("Parent object is not a Message.Builder when setting field for path: " + targetPath, null, ruleForError);
                }

                if (targetField.isMapField()) {
                    Object convertedValue = convertValue(sourceValue, targetField, ruleForError, true);
                    if (!(convertedValue instanceof Map<?, ?> sourceMapRaw)) {
                        throw new MappingException("Type mismatch for map field '" + targetField.getName() + "': Cannot assign non-map value to Map using '=' or '+='", null, ruleForError);
                    }
                    Map<Object, Object> finalMap;
                    if ("=".equals(operator)) {
                        finalMap = convertAndTypeCheckJavaMap(sourceMapRaw, targetField, ruleForError);
                    } else if ("+=".equals(operator)) {
                        // *** CORRECTION: Avoid hasField on mapField with Builder ***
                        Object currentMapObj = parentBuilder.getField(targetField); // Get current list of entries
                        finalMap = reconstructMapForSet(currentMapObj, targetField);
                        Map<Object, Object> convertedSourceMap = convertAndTypeCheckJavaMap(sourceMapRaw, targetField, ruleForError);
                        finalMap.putAll(convertedSourceMap);
                    } else {
                        throw new MappingException("Unsupported operator '" + operator + "' for map field '" + targetField.getName() + "'", null, ruleForError);
                    }
                    List<Message> mapEntriesList = convertJavaMapToMapFieldList(finalMap, targetField);
                    parentBuilder.clearField(targetField);
                    for (Message entryMsg : mapEntriesList) {
                        parentBuilder.addRepeatedField(targetField, entryMsg);
                    }

                } else if (targetField.isRepeated()) {
                    if ("=".equals(operator)) {
                        Object convertedValue = convertValue(sourceValue, targetField, ruleForError, true);
                        if (!(convertedValue instanceof List<?> sourceList)) {
                            throw new MappingException("Cannot assign non-list value to repeated field '" + targetField.getName() + "' using '=' operator. Use '+=' to append.", null, ruleForError);
                        }
                        List<Object> targetList = new ArrayList<>(sourceList.size());
                        for (Object item : sourceList) {
                            targetList.add(convertRepeatedElement(item, targetField, ruleForError));
                        }
                        parentBuilder.setField(targetField, targetList);
                    } else if ("+=".equals(operator)) {
                        if (sourceValue instanceof List<?> sourceList) {
                            for (Object item : sourceList) {
                                parentBuilder.addRepeatedField(targetField, convertRepeatedElement(item, targetField, ruleForError));
                            }
                        } else {
                            parentBuilder.addRepeatedField(targetField, convertRepeatedElement(sourceValue, targetField, ruleForError));
                        }
                    } else {
                        throw new MappingException("Unsupported operator '" + operator + "' for repeated field '" + targetField.getName() + "'", null, ruleForError);
                    }
                } else { // Singular field
                    if (!"=".equals(operator)) {
                        throw new MappingException("Operator '" + operator + "' only supported for repeated or map fields.", null, ruleForError);
                    }
                    Object convertedValue = convertSingleValue(
                            sourceValue,
                            targetField.getJavaType(),
                            targetField.getJavaType() == JavaType.MESSAGE ? targetField.getMessageType() : null,
                            targetField.getJavaType() == JavaType.ENUM ? targetField.getEnumType() : null,
                            targetField.getName(),
                            ruleForError
                    );
                    if (convertedValue == null) {
                        parentBuilder.clearField(targetField); // Use clearField for null/default
                    } else {
                        parentBuilder.setField(targetField, convertedValue);
                    }
                }
            } else {
                throw new MappingException("Could not resolve target path to a settable location: " + targetPath, null, ruleForError);
            }
        } catch (MappingException e) {
            if (e.getFailedRule() == null && ruleForError != null) {
                throw new MappingException(e.getMessage(), e.getCause(), ruleForError);
            }
            throw e;
        } catch (Exception e) {
            throw new MappingException("Error setting value for target path '" + targetPath + "' in rule '" + ruleForError + "'", e, ruleForError);
        }
    }


    // --- Conversion and Helper Methods ---

    private Object convertValue(Object sourceValue, FieldDescriptor targetField, String ruleForError,
                                @SuppressWarnings("SameParameterValue") boolean allowListOrMapSource) throws MappingException {
        if (sourceValue == null) {
            return getDefaultForNullSource(targetField);
        }
        boolean targetIsList = targetField.isRepeated() && !targetField.isMapField();
        boolean targetIsMap = targetField.isMapField();
        boolean sourceIsList = sourceValue instanceof List;
        boolean sourceIsMap = sourceValue instanceof Map;

        if (targetIsMap) {
            if (allowListOrMapSource && sourceIsMap) return sourceValue;
            else if (!allowListOrMapSource && !sourceIsMap) {
                Descriptor mapEntryDesc = targetField.getMessageType();
                FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");
                if (valueDesc == null)
                    throw new MappingException("Map entry descriptor missing value field: " + mapEntryDesc.getFullName(), ruleForError);
                return convertSingleValue(sourceValue, valueDesc.getJavaType(),
                        (valueDesc.getJavaType() == JavaType.MESSAGE ? valueDesc.getMessageType() : null),
                        (valueDesc.getJavaType() == JavaType.ENUM ? valueDesc.getEnumType() : null),
                        targetField.getName() + "[value]", ruleForError);
            } else //noinspection ConstantValue
                if (allowListOrMapSource && !sourceIsMap) {
                    throw new MappingException(String.format("Type mismatch for map field '%s': Cannot assign non-map value %s to Map using '=' or '+='",
                            targetField.getName(), sourceValue.getClass().getSimpleName()), null, ruleForError);
                } else {
                    throw new MappingException(String.format("Type mismatch for map field '%s': Cannot assign Map value using map[\"key\"] syntax",
                            targetField.getName()), null, ruleForError);
                }
        }
        if (targetIsList) {
            if (allowListOrMapSource && sourceIsList) return sourceValue;
            else if (!allowListOrMapSource && !sourceIsList) return sourceValue;
            else //noinspection ConstantValue
                if (allowListOrMapSource && !sourceIsList) {
                    throw new MappingException(String.format("Type mismatch for repeated field '%s': Cannot assign single value %s to List using '='. Use '+=' to append.",
                            targetField.getName(), sourceValue.getClass().getSimpleName()), null, ruleForError);
                } else return sourceValue;
        }
        if (sourceIsList || sourceIsMap) {
            throw new MappingException(String.format("Type mismatch for field '%s': Cannot assign %s to singular field",
                    targetField.getName(), sourceIsList ? "List" : "Map"), null, ruleForError);
        }
        return convertSingleValue(
                sourceValue,
                targetField.getJavaType(),
                (targetField.getJavaType() == JavaType.MESSAGE ? targetField.getMessageType() : null),
                (targetField.getJavaType() == JavaType.ENUM ? targetField.getEnumType() : null),
                targetField.getName(),
                ruleForError
        );
    }

    private Object convertRepeatedElement(Object sourceValue, FieldDescriptor repeatedTargetField, String ruleForError) throws MappingException {
        JavaType elementJavaType = repeatedTargetField.getJavaType();
        Descriptor elementMessageDesc = (elementJavaType == JavaType.MESSAGE) ? repeatedTargetField.getMessageType() : null;
        EnumDescriptor elementEnumDesc = (elementJavaType == JavaType.ENUM) ? repeatedTargetField.getEnumType() : null;
        String targetFieldName = repeatedTargetField.getName() + "[]";
        return convertSingleValue(sourceValue, elementJavaType, elementMessageDesc, elementEnumDesc, targetFieldName, ruleForError);
    }

    private Object convertSingleValue(Object sourceValue,
                                      JavaType targetJavaType,
                                      Descriptor targetMessageDesc,
                                      EnumDescriptor targetEnumDesc,
                                      String targetFieldName,
                                      String ruleForError) throws MappingException {
        if (sourceValue == null) return null;
        JavaType sourceJavaType = getJavaTypeFromValue(sourceValue);
        if (sourceJavaType == null) {
            throw new MappingException(String.format("Unsupported source value type: %s for target field '%s'",
                    sourceValue.getClass().getName(), targetFieldName), null, ruleForError);
        }

        if (sourceJavaType == targetJavaType) {
            if (sourceJavaType == JavaType.MESSAGE) {
                if (targetMessageDesc == null)
                    throw new IllegalStateException("Internal Error: Target MessageDescriptor is null for MESSAGE type field " + targetFieldName);
                Descriptor sourceDesc = ((MessageOrBuilder) sourceValue).getDescriptorForType();
                if (!sourceDesc.getFullName().equals(targetMessageDesc.getFullName())) {
                    if (targetMessageDesc.getFullName().equals(Value.getDescriptor().getFullName())) {
                        if (sourceValue instanceof Struct || sourceValue instanceof Struct.Builder ||
                                sourceValue instanceof ListValue || sourceValue instanceof ListValue.Builder ||
                                sourceValue instanceof Value) {
                            try {
                                return wrapValue(sourceValue);
                            } catch (MappingException e) {
                                throw new MappingException(String.format("Failed to wrap WKT %s into Value for field '%s'", sourceDesc.getName(), targetFieldName), e, ruleForError);
                            }
                        }
                    }
                    throw new MappingException(String.format("Type mismatch for field '%s': Cannot assign message type '%s' to '%s'",
                            targetFieldName, sourceDesc.getFullName(), targetMessageDesc.getFullName()), null, ruleForError);
                }
                if (sourceValue instanceof Message.Builder) {
                    try {
                        return ((Message.Builder) sourceValue).build();
                    } catch (UninitializedMessageException e) {
                        throw new MappingException("Source message builder is uninitialized for target field '" + targetFieldName + "'", e, ruleForError);
                    }
                }
            }
            if (sourceJavaType == JavaType.ENUM) {
                if (targetEnumDesc == null)
                    throw new IllegalStateException("Internal Error: Target EnumDescriptor is null for ENUM type field " + targetFieldName);
                if (sourceValue instanceof EnumValueDescriptor) {
                    EnumDescriptor sourceEnumDesc = ((EnumValueDescriptor) sourceValue).getType();
                    if (!sourceEnumDesc.getFullName().equals(targetEnumDesc.getFullName())) {
                        throw new MappingException(String.format("Type mismatch for field '%s': Cannot assign enum type '%s' to '%s'",
                                targetFieldName, sourceEnumDesc.getFullName(), targetEnumDesc.getFullName()), null, ruleForError);
                    }
                } else {
                    throw new MappingException(String.format("Internal Error: Expected EnumValueDescriptor, got %s for field '%s'",
                            sourceValue.getClass().getName(), targetFieldName), null, ruleForError);
                }
            }
            return sourceValue;
        }

        try {
            if (targetJavaType == JavaType.MESSAGE && targetMessageDesc != null &&
                    targetMessageDesc.getFullName().equals(Value.getDescriptor().getFullName())) {
                try {
                    return wrapValue(sourceValue);
                } catch (MappingException e) {
                    throw new MappingException(String.format("Failed to wrap source type %s into Value for field '%s'", sourceJavaType, targetFieldName), e, ruleForError);
                }
            }
            switch (targetJavaType) {
                case LONG:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).longValue();
                    if (sourceJavaType == JavaType.STRING) return Long.parseLong((String) sourceValue);
                    break;
                case FLOAT:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).floatValue();
                    if (sourceJavaType == JavaType.STRING) return Float.parseFloat((String) sourceValue);
                    break;
                case DOUBLE:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).doubleValue();
                    if (sourceJavaType == JavaType.STRING) return Double.parseDouble((String) sourceValue);
                    break;
                case INT:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).intValue();
                    if (sourceJavaType == JavaType.STRING) return Integer.parseInt((String) sourceValue);
                    if (sourceJavaType == JavaType.ENUM && sourceValue instanceof EnumValueDescriptor)
                        return ((EnumValueDescriptor) sourceValue).getNumber();
                    break;
                case STRING:
                    if (sourceValue instanceof Number num) {
                        // *** FIX: Format whole numbers without .0 ***
                        if (num instanceof Double || num instanceof Float) {
                            double doubleVal = num.doubleValue();
                            if (doubleVal == Math.floor(doubleVal) && !Double.isInfinite(doubleVal)) {
                                // It's a whole number, format as long
                                return String.valueOf((long) doubleVal);
                            }
                        }
                        // Otherwise, use default string representation
                        return String.valueOf(num);
                        // *** END FIX ***
                    }
                    if (sourceJavaType == JavaType.BOOLEAN) return String.valueOf(sourceValue);
                    if (sourceJavaType == JavaType.ENUM && sourceValue instanceof EnumValueDescriptor)
                        return ((EnumValueDescriptor) sourceValue).getName();
                    if (sourceJavaType == JavaType.BYTE_STRING) return ((ByteString) sourceValue).toStringUtf8();
                    break;
                case ENUM:
                    if (targetEnumDesc == null)
                        throw new IllegalStateException("Internal Error: Target EnumDescriptor is null for ENUM type field " + targetFieldName);
                    if (sourceValue instanceof Number) {
                        int num = ((Number) sourceValue).intValue();
                        EnumValueDescriptor enumValue = targetEnumDesc.findValueByNumber(num);
                        if (enumValue == null)
                            throw new MappingException("Invalid enum number " + num + " for enum type " + targetEnumDesc.getFullName(), null, ruleForError);
                        return enumValue;
                    }
                    if (sourceJavaType == JavaType.STRING) {
                        EnumValueDescriptor enumValue = targetEnumDesc.findValueByName((String) sourceValue);
                        if (enumValue == null)
                            throw new MappingException("Invalid enum name '" + sourceValue + "' for enum type " + targetEnumDesc.getFullName(), null, ruleForError);
                        return enumValue;
                    }
                    break;
                case BOOLEAN:
                    if (sourceJavaType == JavaType.STRING) {
                        String strVal = ((String) sourceValue).trim();
                        if (strVal.equalsIgnoreCase("true")) return true;
                        if (strVal.equalsIgnoreCase("false")) return false;
                    }
                    if (sourceValue instanceof Number) return ((Number) sourceValue).doubleValue() != 0.0;
                    break;
                case BYTE_STRING:
                    if (sourceJavaType == JavaType.STRING) return ByteString.copyFromUtf8((String) sourceValue);
                    break;
                case MESSAGE:
                    break; // Handled by exact type match or Value wrapping
            }
        } catch (NumberFormatException e) {
            throw new MappingException(String.format("Error converting string '%s' to number type %s for field '%s'", sourceValue, targetJavaType, targetFieldName), e, ruleForError);
        } catch (Exception e) {
            if (e instanceof MappingException) throw (MappingException) e;
            throw new MappingException(String.format("Error converting value '%s' (%s) to type %s for field '%s'", sourceValue, sourceJavaType, targetJavaType, targetFieldName), e, ruleForError);
        }
        throw new MappingException(String.format("Type mismatch for field '%s': Cannot convert %s to %s", targetFieldName, sourceJavaType, targetJavaType), null, ruleForError);
    }

    private JavaType getJavaTypeFromValue(Object value) {
        return switch (value) {
            case null -> null;
            case Integer i -> JavaType.INT;
            case Long l -> JavaType.LONG;
            case Float v -> JavaType.FLOAT;
            case Double v -> JavaType.DOUBLE;
            case Boolean b -> JavaType.BOOLEAN;
            case String s -> JavaType.STRING;
            case ByteString bytes -> JavaType.BYTE_STRING;
            case EnumValueDescriptor enumValueDescriptor -> JavaType.ENUM;
            case MessageOrBuilder messageOrBuilder -> JavaType.MESSAGE;
            default -> null;
        };
    }

    private Value wrapValue(Object value) throws MappingException {
        Value.Builder valueBuilder = Value.newBuilder();
        if (value == null) valueBuilder.setNullValue(NullValue.NULL_VALUE);
        else if (value instanceof Value) return (Value) value;
        else if (value instanceof String) valueBuilder.setStringValue((String) value);
        else if (value instanceof Double || value instanceof Float) valueBuilder.setNumberValue(((Number) value).doubleValue());
        else if (value instanceof Number) valueBuilder.setNumberValue(((Number) value).doubleValue());
        else if (value instanceof Boolean) valueBuilder.setBoolValue((Boolean) value);
        else if (value instanceof Struct) valueBuilder.setStructValue((Struct) value);
        else if (value instanceof Struct.Builder) valueBuilder.setStructValue(((Struct.Builder) value));
        else if (value instanceof ListValue) valueBuilder.setListValue((ListValue) value);
        else if (value instanceof ListValue.Builder) valueBuilder.setListValue(((ListValue.Builder) value));
        else if (value instanceof List) {
            ListValue.Builder listBuilder = ListValue.newBuilder();
            for (Object item : (List<?>) value) listBuilder.addValues(wrapValue(item));
            valueBuilder.setListValue(listBuilder);
        } else if (value instanceof Map) {
            Struct.Builder structBuilder = Struct.newBuilder();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String))
                    throw new MappingException("Cannot wrap Map with non-String keys into Struct Value", null, null);
                structBuilder.putFields((String) entry.getKey(), wrapValue(entry.getValue()));
            }
            valueBuilder.setStructValue(structBuilder);
        } else if (value instanceof MessageOrBuilder)
            throw new MappingException("Cannot automatically wrap Message type " + ((MessageOrBuilder) value).getDescriptorForType().getFullName() + " into Protobuf Value.", null, null);
        else if (value instanceof EnumValueDescriptor) valueBuilder.setStringValue(((EnumValueDescriptor) value).getName());
        else if (value instanceof ByteString)
            valueBuilder.setStringValue(Base64.getEncoder().encodeToString(((ByteString) value).toByteArray()));
        else
            throw new MappingException("Cannot wrap unsupported Java type " + value.getClass().getName() + " into Protobuf Value", null, null);
        return valueBuilder.build();
    }

    private Object unwrapValue(Value valueProto) {
        if (valueProto == null) return null;
        switch (valueProto.getKindCase()) {
            case NUMBER_VALUE:
                return valueProto.getNumberValue();
            case STRING_VALUE:
                return valueProto.getStringValue();
            case BOOL_VALUE:
                return valueProto.getBoolValue();
            case STRUCT_VALUE:
                return valueProto.getStructValue();
            case LIST_VALUE:
                return valueProto.getListValue().getValuesList().stream().map(this::unwrapValue).collect(Collectors.toList());
            case NULL_VALUE:
            default:
                return null;
        }
    }

    private Object getDefaultForNullSource(FieldDescriptor targetField) {
        switch (targetField.getJavaType()) {
            case INT:
                return 0;
            case LONG:
                return 0L;
            case FLOAT:
                return 0.0f;
            case DOUBLE:
                return 0.0d;
            case BOOLEAN:
                return false;
            case STRING:
                return "";
            case BYTE_STRING:
                return ByteString.EMPTY;
            case ENUM:
                return targetField.getEnumType().getValues().get(0);
            case MESSAGE:
                String fn = targetField.getMessageType().getFullName();
                if (fn.equals(Struct.getDescriptor().getFullName())) return Struct.getDefaultInstance();
                if (fn.equals(ListValue.getDescriptor().getFullName())) return ListValue.getDefaultInstance();
                if (fn.equals(Value.getDescriptor().getFullName())) return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
                return DynamicMessage.getDefaultInstance(targetField.getMessageType());
            default:
                return null;
        }
    }

    Struct.Builder getStructBuilder(Message.Builder parentBuilder, FieldDescriptor structField, String ruleForError) throws MappingException {
        if (structField.getJavaType() != JavaType.MESSAGE || !structField.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName()))
            throw new IllegalArgumentException("Field is not a Struct field: " + structField.getFullName());
        try {
            Object currentValue = parentBuilder.hasField(structField) ? parentBuilder.getField(structField) : null;
            Struct.Builder structBuilder = Struct.newBuilder();
            if (currentValue instanceof Struct) structBuilder.mergeFrom((Struct) currentValue);
            else if (currentValue instanceof Message && ((Message) currentValue).getDescriptorForType().equals(Struct.getDescriptor()))
                structBuilder.mergeFrom((Message) currentValue);
            else if (currentValue != null && !currentValue.equals(Struct.getDefaultInstance()))
                throw new MappingException("Field '" + structField.getName() + "' exists but is not a Struct: " + currentValue.getClass().getName(), null, ruleForError);
            return structBuilder;
        } catch (Exception e) {
            if (e instanceof MappingException) throw (MappingException) e;
            throw new MappingException("Error getting/creating Struct builder for field '" + structField.getName() + "'", e, ruleForError);
        }
    }

    private List<Message> convertJavaMapToMapFieldList(Map<?, ?> javaMap, FieldDescriptor mapField) throws MappingException {
        List<Message> mapEntriesList = new ArrayList<>(javaMap.size());
        Descriptor entryDesc = mapField.getMessageType();
        FieldDescriptor keyDesc = entryDesc.findFieldByName("key"), valueDesc = entryDesc.findFieldByName("value");
        if (keyDesc == null || valueDesc == null)
            throw new MappingException("Map entry descriptor missing key or value field: " + entryDesc.getFullName());
        for (Map.Entry<?, ?> entry : javaMap.entrySet()) {
            Object key = entry.getKey(), value = entry.getValue();
            Message entryMsg = DynamicMessage.newBuilder(entryDesc).setField(keyDesc, key).setField(valueDesc, value).build();
            mapEntriesList.add(entryMsg);
        }
        return mapEntriesList;
    }

    private Map<Object, Object> convertMapFieldListToJavaMap(Object mapFieldValue, FieldDescriptor mapField) throws MappingException {
        if (!mapField.isMapField()) throw new IllegalArgumentException("Field is not a map field: " + mapField.getFullName());
        if (!(mapFieldValue instanceof List<?> entries)) {
            if (mapFieldValue != null && mapFieldValue.equals(Collections.emptyList())) return Collections.emptyMap();
            throw new MappingException("Map Field '" + mapField.getName() + "' did not return a List object for reconstruction (got " + (mapFieldValue != null ? mapFieldValue.getClass().getName() : "null") + ").");
        }
        Map<Object, Object> resultMap = new HashMap<>();
        Descriptor entryDesc = mapField.getMessageType();
        FieldDescriptor keyDesc = entryDesc.findFieldByName("key"), valueDesc = entryDesc.findFieldByName("value");
        if (keyDesc == null || valueDesc == null)
            throw new MappingException("Map entry descriptor missing key or value field: " + entryDesc.getFullName());
        for (Object entryObj : entries) {
            if (!(entryObj instanceof Message entryMsg))
                throw new MappingException("Map entry in field '" + mapField.getName() + "' was not a Message during reconstruction.");
            if (!entryMsg.getDescriptorForType().equals(entryDesc))
                throw new MappingException("Map entry message has incorrect type: expected " + entryDesc.getFullName() + ", got " + entryMsg.getDescriptorForType().getFullName());
            resultMap.put(entryMsg.getField(keyDesc), entryMsg.getField(valueDesc));
        }
        return resultMap;
    }

    private Map<Object, Object> reconstructMapForSet(Object currentMapObj, FieldDescriptor mapField) throws MappingException {
        return new HashMap<>(convertMapFieldListToJavaMap(currentMapObj, mapField));
    }

    private Map<Object, Object> convertAndTypeCheckJavaMap(Map<?, ?> sourceMapRaw, FieldDescriptor mapField, String ruleForError) throws MappingException {
        Map<Object, Object> finalMap = new HashMap<>();
        Descriptor mapEntryDesc = mapField.getMessageType();
        FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key"), valueDesc = mapEntryDesc.findFieldByName("value");
        if (keyDesc == null || valueDesc == null)
            throw new MappingException("Map entry descriptor missing key or value field: " + mapEntryDesc.getFullName(), ruleForError);
        for (Map.Entry<?, ?> entry : sourceMapRaw.entrySet()) {
            Object mapKey = convertSingleValue(entry.getKey(), keyDesc.getJavaType(), (keyDesc.getJavaType() == JavaType.MESSAGE ? keyDesc.getMessageType() : null), (keyDesc.getJavaType() == JavaType.ENUM ? keyDesc.getEnumType() : null), mapField.getName() + "[key]", ruleForError);
            Object mapValue = convertSingleValue(entry.getValue(), valueDesc.getJavaType(), (valueDesc.getJavaType() == JavaType.MESSAGE ? valueDesc.getMessageType() : null), (valueDesc.getJavaType() == JavaType.ENUM ? valueDesc.getEnumType() : null), mapField.getName() + "[value]", ruleForError);
            finalMap.put(mapKey, mapValue);
        }
        return finalMap;
    }
}