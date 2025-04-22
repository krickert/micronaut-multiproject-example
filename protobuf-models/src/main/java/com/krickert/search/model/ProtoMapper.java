package com.krickert.search.model; // Assuming ProtoMapper is in this package

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class to dynamically map fields between Protocol Buffer messages based on string rules.
 * Uses Protobuf reflection (Descriptors, DynamicMessage) and does not require generated classes
 * for the source or target messages at compile time (though Descriptors must be available).
 */
public class ProtoMapper {
    private static final Logger LOG = LoggerFactory.getLogger(ProtoMapper.class);

    // Regex patterns for parsing rules
    private static final Pattern DELETE_PATTERN = Pattern.compile("^-(.+)"); // Matches "-target.path"
    // Updated ASSIGN_PATTERN to prevent matching '=' at the start of the source part
    private static final Pattern ASSIGN_PATTERN = Pattern.compile("^([^=\\s]+)\\s*=\\s*([^=\\s].*)$"); // Matches "target.path = source.path" (source cannot start with =)
    private static final Pattern APPEND_PATTERN = Pattern.compile("^([^+\\s]+)\\s*\\+=\\s*(.+)"); // Matches "target.list += source.item" or "target.map += source.map"
    private static final Pattern MAP_PUT_PATTERN = Pattern.compile("^([^\\[\\s]+)\\[\"?([^\"]+)\"?\\]\\s*=\\s*(.+)"); // Matches "target.map[\"key\"] = source.path"

    // Separator for nested paths
    private static final String PATH_SEPARATOR_REGEX = "\\.";

    /**
     * Applies mapping rules to transform a source message into a *new* target message type.
     * The returned message is a generic Message object (likely DynamicMessage).
     * Use MyType.parseFrom(result.toByteArray()) to get the specific generated type.
     *
     * @param sourceMessage The source protobuf message instance.
     * @param targetDescriptor The Descriptor for the target message type.
     * @param mappingRules A list of strings defining the mapping operations.
     * @return A new Message instance of the target type, populated according to the rules.
     * @throws MappingException If any error occurs during parsing or mapping.
     */
    public Message map(Message sourceMessage,
                       Descriptor targetDescriptor,
                       List<String> mappingRules) throws MappingException {

        // Use DynamicMessage.Builder for the target
        DynamicMessage.Builder targetBuilder = DynamicMessage.newBuilder(targetDescriptor);
        // Apply rules to the new builder
        applyRulesToBuilder(sourceMessage, targetBuilder, mappingRules);

        try {
            // Build the final message
            return targetBuilder.build();
        } catch (UninitializedMessageException e) {
            // Handle cases where required fields (proto2) might not be set after mapping
            throw new MappingException("Error building final target message: Required fields missing. " + e.getMessage(), e, null);
        } catch (Exception e) {
            throw new MappingException("Error building final target message", e, null);
        }
    }

    /**
     * Applies mapping rules from a source message onto an *existing* target message builder.
     * This allows merging or updating existing messages.
     *
     * @param sourceMessage The source protobuf message instance.
     * @param targetBuilder The builder for the target message, potentially pre-populated.
     * @param mappingRules A list of strings defining the mapping operations.
     * @return The same targetBuilder instance, modified according to the rules.
     * @throws MappingException If any error occurs during parsing or mapping.
     */
    public Message.Builder mapOnto(Message sourceMessage,
                                   Message.Builder targetBuilder,
                                   List<String> mappingRules) throws MappingException {
        // Apply rules directly to the provided builder
        applyRulesToBuilder(sourceMessage, targetBuilder, mappingRules);
        return targetBuilder; // Return the modified builder
    }


    /**
     * Internal helper to apply rules to a given builder.
     */
    private void applyRulesToBuilder(Message sourceMessage, Message.Builder targetBuilder, List<String> mappingRules) throws MappingException {
        // Process Deletions First
        for (String rule : mappingRules) {
            rule = rule.trim();
            if (rule.isEmpty() || rule.startsWith("#")) continue; // Skip comments and empty lines

            Matcher deleteMatcher = DELETE_PATTERN.matcher(rule);
            if (deleteMatcher.matches()) {
                try {
                    executeDeletion(targetBuilder, deleteMatcher.group(1).trim()); // Trim path
                } catch (MappingException e) {
                    // If field not found during deletion, ignore silently
                    if (e.getMessage() != null && (e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve key") || e.getMessage().contains("is not set"))) {
                        LOG.warn("Warning: Field/key not found or not set for deletion rule: {}", rule); // Use WARN
                    } else {
                        LOG.error("Error executing deletion rule: {}", rule, e);
                        throw e; // Re-throw other specific mapping exceptions
                    }
                } catch (Exception e) {
                    LOG.error("Error executing deletion rule: {}", rule, e);
                    throw new MappingException("Error executing deletion rule: " + rule, e, rule);
                }
            }
        }

        // Process Assignments/Appends Second
        for (String rule : mappingRules) {
            rule = rule.trim();
            if (rule.isEmpty() || rule.startsWith("#") || rule.startsWith("-")) continue; // Skip comments, empty, deletions

            try {
                executeAssignment(sourceMessage, targetBuilder, rule);
            } catch (MappingException e) {
                throw e; // Re-throw specific mapping exceptions
            } catch (Exception e) {
                throw new MappingException("Error executing assignment rule: " + rule, e, rule);
            }
        }
    }


    // --- Rule Execution ---

    /**
     * Executes a deletion rule (-target.path).
     */
    private void executeDeletion(Message.Builder targetBuilder, String targetPath) throws MappingException {
        PathResolverResult targetRes = resolvePath(targetBuilder, targetPath, true); // Resolve for setting

        if (targetRes.isStructKey()) {
            // Delete key from Struct
            // FIX: Use the grandparent builder (the one containing the struct field)
            Object grandParentBuilderObj = targetRes.getGrandparentBuilder();
            Message.Builder structParentBuilder; // The builder containing the struct field

            // Handle case where the struct field is top-level
            if (grandParentBuilderObj == null && targetRes.getParentBuilder() instanceof Message.Builder) {
                structParentBuilder = (Message.Builder) targetRes.getParentBuilder();
            } else if (grandParentBuilderObj instanceof Message.Builder) {
                structParentBuilder = (Message.Builder) grandParentBuilderObj;
            } else {
                throw new MappingException("Could not find valid parent builder for Struct field: " + targetPath, null, "-"+targetPath);
            }

            // Use the parentField descriptor obtained from resolvePath (this IS the struct field descriptor)
            FieldDescriptor actualStructField = targetRes.getParentField();
            if (actualStructField == null || !actualStructField.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName())) {
                throw new MappingException("Resolved parent field is not a Struct for deletion: " + targetPath, null, "-"+targetPath);
            }

            // Pass the correct parent builder and struct field descriptor
            Struct.Builder structBuilder = getStructBuilder(structParentBuilder, actualStructField, targetPath);
            structBuilder.removeFields(targetRes.getFinalPathPart());
            // Set the modified struct back onto the correct parent builder
            structParentBuilder.setField(actualStructField, structBuilder.build());

        } else if (targetRes.getTargetField() != null) {
            // Clear regular field
            Object parentBuilderObj = targetRes.getParentBuilder();
            if (!(parentBuilderObj instanceof Message.Builder parentMsgBuilder)) {
                throw new MappingException("Parent object is not a Message.Builder when clearing field for path: " + targetPath, null, "-"+targetPath);
            }
            parentMsgBuilder.clearField(targetRes.getTargetField());
        } else {
            // This case implies the path resolved to a map key or something unexpected for deletion.
            throw new MappingException("Cannot resolve path to a deletable field or struct key: " + targetPath, null, "-"+targetPath);
        }
    }

    /**
     * Executes an assignment or append rule.
     */
    private void executeAssignment(Message sourceMessage, Message.Builder targetBuilder, String rule) throws MappingException {
        String operator = "="; // Default operator
        String targetSpec;
        String sourceSpec;

        // Check for different rule patterns
        Matcher appendMatcher = APPEND_PATTERN.matcher(rule);
        Matcher mapPutMatcher = MAP_PUT_PATTERN.matcher(rule);
        Matcher assignMatcher = ASSIGN_PATTERN.matcher(rule); // Check last

        if (appendMatcher.matches()) {
            operator = "+=";
            targetSpec = appendMatcher.group(1).trim();
            sourceSpec = appendMatcher.group(2).trim();
        } else if (mapPutMatcher.matches()) {
            operator = "[]="; // Special operator for map put
            targetSpec = mapPutMatcher.group(1).trim() + "[\"" + mapPutMatcher.group(2).trim() + "\"]"; // Reconstruct for path resolver
            sourceSpec = mapPutMatcher.group(3).trim();
        } else if (assignMatcher.matches()) {
            operator = "=";
            targetSpec = assignMatcher.group(1).trim();
            sourceSpec = assignMatcher.group(2).trim();
            // Additional check for invalid syntax like "a = = b"
            if (sourceSpec.trim().startsWith("=")) {
                throw new MappingException("Invalid assignment rule syntax: multiple '=' found: " + rule, null, rule);
            }
        } else {
            // If no pattern matches, it's invalid syntax
            throw new MappingException("Invalid assignment rule syntax: " + rule, null, rule);
        }

        // Resolve source value
        Object sourceValue = getValue(sourceMessage, sourceSpec, rule);

        // Resolve target path and set value
        setValue(targetBuilder, targetSpec, sourceValue, operator, rule);
    }


    // --- Path Resolution and Value Access ---

    /**
     * Internal class to hold the result of path resolution.
     * Contains the final parent builder/message, the target FieldDescriptor (if applicable),
     * and the final path part (if a struct/map key).
     */
    private static class PathResolverResult {
        private final Object parent; // Message, Message.Builder, or Struct.Builder
        private final Object grandparent; // Message or Message.Builder containing parent if parent is Struct.Builder
        private final FieldDescriptor parentField; // FieldDescriptor for the parent if it's a Struct.Builder
        private final FieldDescriptor targetField; // Null if struct key or map key target
        private final String finalPathPart; // The key if target is a Struct or Map key
        private final boolean isStructKey;
        private final boolean isMapKey;

        PathResolverResult(Object parent, Object grandparent, FieldDescriptor parentField, FieldDescriptor targetField, String finalPathPart, boolean isStructKey, boolean isMapKey) {
            this.parent = parent;
            this.grandparent = grandparent;
            this.parentField = parentField;
            this.targetField = targetField;
            this.finalPathPart = finalPathPart;
            this.isStructKey = isStructKey;
            this.isMapKey = isMapKey;
        }

        Object getParentBuilder() { // Convenience for setting
            if (!(parent instanceof Message.Builder || parent instanceof Struct.Builder)) {
                if (parent instanceof MessageOrBuilder) return parent; // Allow reading from message
                throw new IllegalStateException("Parent is not a builder or message type: " + parent.getClass().getName());
            }
            return parent;
        }
        Object getParentMessageOrBuilder() { // Convenience for getting
            return parent;
        }
        Object getGrandparentBuilder() { // Convenience for setting struct back
            // Allow grandparent to be null (if parent is the root builder)
            if (grandparent != null && !(grandparent instanceof Message.Builder)) {
                throw new IllegalStateException("Grandparent is not a Message.Builder");
            }
            return grandparent;
        }
        FieldDescriptor getParentField() { return parentField; }
        FieldDescriptor getTargetField() { return targetField; }
        String getFinalPathPart() { return finalPathPart; }
        boolean isStructKey() { return isStructKey; }
        boolean isMapKey() { return isMapKey; }
    }

    /**
     * Resolves a path within a message or builder.
     *
     * @param root          The starting Message or Message.Builder.
     * @param path          The dot-separated path string (e.g., "field.nested.key", "map[\"key\"]").
     * @param resolveForSet If true, creates/gets builders for nested messages; if false, only reads.
     * @return PathResolverResult containing the resolution details.
     * @throws MappingException If the path is invalid or cannot be resolved.
     */
    private PathResolverResult resolvePath(Object root, String path, boolean resolveForSet) throws MappingException {
        String[] parts = path.split(PATH_SEPARATOR_REGEX);
        Object currentObj = root; // Can be Message or Message.Builder
        Object parentObj = null;
        FieldDescriptor parentFd = null;
        Descriptor currentDesc;

        // Get initial descriptor
        if (root instanceof MessageOrBuilder) {
            currentDesc = ((MessageOrBuilder) root).getDescriptorForType();
        } else {
            throw new MappingException("Invalid root object type for path resolution: " + root.getClass().getName(), null, path);
        }


        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String mapKey = null;

            // Check for map access syntax: field["key"]
            if (part.contains("[") && part.endsWith("]")) {
                int bracketStart = part.indexOf('[');
                if (bracketStart > 0) {
                    mapKey = part.substring(bracketStart + 1, part.length() - 1).replace("\"", ""); // Extract key
                    part = part.substring(0, bracketStart); // Field name is before bracket
                } else {
                    // Invalid syntax like ["key"] without field name
                    throw new MappingException("Invalid map access syntax in path: " + path, null, path);
                }
            }


            FieldDescriptor fd = currentDesc.findFieldByName(part);
            if (fd == null) {
                throw new MappingException("Field not found: '" + part + "' in path '" + path + "' for type " + currentDesc.getFullName(), null, path);
            }

            boolean isLastPart = (i == parts.length - 1);

            // If accessing a map key
            if (mapKey != null) {
                if (!fd.isMapField()) {
                    throw new MappingException("Field '" + part + "' is not a map field, cannot access with key in path '" + path + "'", null, path);
                }
                if (!isLastPart) {
                    // This check was correct, map key access must be the end.
                    throw new MappingException("Map key access must be the last part of the path: '" + path + "'", null, path);
                }
                // For map key access, the 'parent' is the message/builder containing the map field.
                // The 'targetField' is the map field itself. 'finalPathPart' is the key.
                return new PathResolverResult(currentObj, parentObj, parentFd, fd, mapKey, false, true);
            }

            // If accessing a struct key
            if (fd.getJavaType() == JavaType.MESSAGE && fd.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName())) {
                if (isLastPart) {
                    // Path ends at the Struct field itself
                    return new PathResolverResult(currentObj, parentObj, parentFd, fd, null, false, false);
                } else {
                    // Next part must be the key within the struct
                    String structKey = parts[i + 1];
                    if (i + 1 != parts.length - 1) {
                        throw new MappingException("Path cannot continue after Struct key: '" + path + "'", null, path);
                    }
                    Object structParent = currentObj; // Message or Builder containing the struct field
                    FieldDescriptor structFieldDesc = fd;

                    if (resolveForSet) {
                        if (!(structParent instanceof Message.Builder)) {
                            throw new MappingException("Cannot resolve path for setting: intermediate object is not a Builder at Struct field '" + part + "'", null, path);
                        }
                        // Get the builder for the Struct field. This might be DynamicMessage.Builder
                        //Message.Builder structBuilder = ((Message.Builder) structParent).getFieldBuilder(structFieldDesc);
                        // Return the PARENT builder and the STRUCT field descriptor
                        return new PathResolverResult(structParent, parentObj, structFieldDesc, null, structKey, true, false);
                    } else {
                        // For reading, we get the Struct message itself
                        if (!(structParent instanceof MessageOrBuilder)) {
                            throw new MappingException("Cannot resolve path for getting: intermediate object is not a MessageOrBuilder at Struct field '" + part + "'", null, path);
                        }
                        Object structMsg = ((MessageOrBuilder) structParent).getField(structFieldDesc);
                        // If reading and struct isn't set, we can't resolve the key
                        if (structMsg == null || structMsg.equals(Struct.getDefaultInstance())) { // Compare with Struct default
                            throw new MappingException("Cannot resolve key '" + structKey + "' in unset Struct field '" + part + "' in path '" + path + "'", null, path);
                        }
                        // Parent is the Struct message itself when reading a key
                        return new PathResolverResult(structMsg, structParent, structFieldDesc, null, structKey, true, false);
                    }
                }
            }

            // If it's the last part, return the parent and the final field descriptor
            if (isLastPart) {
                return new PathResolverResult(currentObj, parentObj, parentFd, fd, null, false, false);
            }

            // If not the last part, check field type for traversal
            if (fd.isMapField()) {
                throw new MappingException("Cannot traverse into map field '" + part + "' using dot notation in path '" + path + "'. Use map[\"key\"] syntax.", null, path);
            }
            // FIX: Allow traversal into repeated fields (implicitly index 0 for now)
            if (fd.isRepeated()) {
                Object listOrBuilder;
                if (resolveForSet) {
                    if (!(currentObj instanceof Message.Builder)) {
                        throw new MappingException("Cannot resolve path for setting: intermediate object is not a Builder at repeated field '" + part + "'", null, path);
                    }
                    // For setting, we might need the builder, but getFieldBuilder isn't for repeated fields.
                    // We need the element at index 0, which requires getting the list first.
                    listOrBuilder = ((Message.Builder) currentObj).getField(fd);
                } else {
                    if (!(currentObj instanceof MessageOrBuilder)) {
                        throw new MappingException("Cannot resolve path for getting: intermediate object is not a MessageOrBuilder at repeated field '" + part + "'", null, path);
                    }
                    listOrBuilder = ((MessageOrBuilder) currentObj).getField(fd);
                }

                if (!(listOrBuilder instanceof List<?> listValue)) {
                    throw new MappingException("Field '" + part + "' did not return a List object.", null, path);
                }

                if (listValue.isEmpty()) {
                    throw new MappingException("Cannot resolve path '" + path + "': intermediate repeated field '" + part + "' is empty.", null, path);
                }
                currentObj = listValue.get(0); // Implicitly take the first element
                if (currentObj == null) {
                    throw new MappingException("Cannot resolve path '" + path + "': first element of intermediate repeated field '" + part + "' is null.", null, path);
                }
                // Update descriptor for the element type
                if (fd.getJavaType() == JavaType.MESSAGE) {
                    if (!(currentObj instanceof MessageOrBuilder)) {
                        throw new MappingException("Element in repeated field '" + part + "' is not a MessageOrBuilder", null, path);
                    }
                    currentDesc = ((MessageOrBuilder) currentObj).getDescriptorForType();
                } else {
                    // Cannot traverse further into primitive repeated field elements
                    throw new MappingException("Cannot traverse into primitive element of repeated field '" + part + "' in path '" + path + "'", null, path);
                }
                parentObj = null; // Reset parent context as we've indexed into a list element
                parentFd = null;
                continue; // Continue to next part of the path relative to the list element

            } else if (fd.getJavaType() == JavaType.MESSAGE) {
                // Handle regular message traversal
                parentObj = currentObj;
                parentFd = fd;
                if (resolveForSet) {
                    if (!(currentObj instanceof Message.Builder)) {
                        throw new MappingException("Cannot resolve path for setting: intermediate object is not a Builder at field '" + part + "'", null, path);
                    }
                    currentObj = ((Message.Builder) currentObj).getFieldBuilder(fd); // Get or create builder
                } else {
                    if (!(currentObj instanceof MessageOrBuilder)) {
                        throw new MappingException("Cannot resolve path for getting: intermediate object is not a MessageOrBuilder at field '" + part + "'", null, path);
                    }
                    currentObj = ((MessageOrBuilder) currentObj).getField(fd); // Get message instance
                    if (currentObj == null || currentObj.equals(DynamicMessage.getDefaultInstance(fd.getMessageType()))) {
                        throw new MappingException("Cannot resolve path '" + path + "': intermediate message field '" + part + "' is not set.", null, path);
                    }
                }
                if (currentObj instanceof MessageOrBuilder) {
                    currentDesc = ((MessageOrBuilder) currentObj).getDescriptorForType();
                } else {
                    // This case might happen if getFieldBuilder returns something unexpected
                    throw new MappingException("Unexpected intermediate object type " + (currentObj != null ? currentObj.getClass().getName() : "null") + " during path resolution at '" + part + "'", null, path);
                }
            } else {
                // Cannot traverse into non-message, non-repeated field
                throw new MappingException("Cannot traverse into non-message field '" + part + "' in path '" + path + "'", null, path);
            }
        }

        // Should not be reached if path is valid
        throw new MappingException("Path resolution failed unexpectedly for path: " + path, null, path);
    }

    /**
     * Gets the value from the source message based on the path.
     */
    private Object getValue(Message sourceMessage, String sourcePath, String ruleForError) throws MappingException {
        try {
            PathResolverResult sourceRes = resolvePath(sourceMessage, sourcePath, false); // Resolve for reading

            if (sourceRes.isStructKey()) {
                // Extract value from Struct
                Struct sourceStruct = (Struct) sourceRes.getParentMessageOrBuilder(); // Parent is the Struct message when reading
                Value valueProto = sourceStruct.getFieldsOrDefault(sourceRes.getFinalPathPart(), null);
                if (valueProto == null || valueProto.getKindCase() == Value.KindCase.NULL_VALUE) {
                    return null; // Treat missing key or explicit null as null
                }
                return unwrapValue(valueProto); // Convert Value to Java object
            } else if (sourceRes.isMapKey()) {
                // Extract value from Map
                FieldDescriptor mapField = sourceRes.getTargetField();
                Object parentObj = sourceRes.getParentMessageOrBuilder();
                if (!(parentObj instanceof MessageOrBuilder parentMsgOrBuilder)) {
                    throw new MappingException("Parent object is not readable when getting map value for path: " + sourcePath, null, ruleForError);
                }
                Object mapFieldValue = parentMsgOrBuilder.getField(mapField);

                // FIX: getField for map fields returns a List<Message> of entries, not a Map.
                if (!(mapFieldValue instanceof List<?> mapEntries)) {
                    // Handle case where field is not set (returns empty list)
                    if (mapFieldValue != null && mapFieldValue.equals(Collections.emptyList())) return null; // Key not found in empty map
                    throw new MappingException("Field '" + mapField.getName() + "' did not return a List object (got " + (mapFieldValue != null ? mapFieldValue.getClass().getName() : "null") + ").", null, ruleForError);
                }

                // Convert the key from the path string to the map's actual key type
                Descriptor mapEntryDesc = mapField.getMessageType();
                FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key");
                FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");
                // FIX: Pass null for EnumDescriptor when converting map key
                Object searchKey = convertSingleValue(sourceRes.getFinalPathPart(), keyDesc.getJavaType(), null, null, mapField.getName()+"[key]", ruleForError);

                // Iterate through the entry messages to find the matching key
                for (Object entryObj : mapEntries) {
                    if (entryObj instanceof Message entryMsg) {
                        Object currentKey = entryMsg.getField(keyDesc);
                        if (Objects.equals(currentKey, searchKey)) {
                            return entryMsg.getField(valueDesc); // Return the value from the matching entry
                        }
                    } else {
                        throw new MappingException("Map entry in field '" + mapField.getName() + "' was not a Message.", null, ruleForError);
                    }
                }
                return null; // Key not found

            }
            else if (sourceRes.getTargetField() != null) {
                // FIX: Handle case where the field itself is a map field (return reconstructed Map)
                FieldDescriptor targetField = sourceRes.getTargetField();
                if (targetField.isMapField()) {
                    Object mapFieldValue = ((MessageOrBuilder) sourceRes.getParentMessageOrBuilder()).getField(targetField);
                    if (!(mapFieldValue instanceof List<?> mapEntries)) {
                        if (mapFieldValue != null && mapFieldValue.equals(Collections.emptyList())) return Collections.emptyMap();
                        throw new MappingException("Map Field '" + targetField.getName() + "' did not return a List object (got " + (mapFieldValue != null ? mapFieldValue.getClass().getName() : "null") + ").", null, ruleForError);
                    }
                    // Reconstruct the Map
                    Map<Object, Object> resultMap = new HashMap<>();
                    Descriptor mapEntryDesc = targetField.getMessageType();
                    FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key");
                    FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");
                    for (Object entryObj : mapEntries) {
                        if (entryObj instanceof Message entryMsg) {
                            resultMap.put(entryMsg.getField(keyDesc), entryMsg.getField(valueDesc));
                        } else {
                            throw new MappingException("Map entry in field '" + targetField.getName() + "' was not a Message.", null, ruleForError);
                        }
                    }
                    return resultMap;
                } else {
                    // Get value from regular field
                    return ((MessageOrBuilder) sourceRes.getParentMessageOrBuilder()).getField(targetField);
                }
            } else {
                throw new MappingException("Could not resolve source path to a readable value: " + sourcePath, null, ruleForError);
            }
        } catch (MappingException e) {
            // Add rule context to existing mapping exceptions
            throw new MappingException("Error resolving source path '" + sourcePath + "' in rule '" + ruleForError + "': " + e.getMessage(), e.getCause(), ruleForError);
        } catch (Exception e) {
            throw new MappingException("Error getting value for source path '" + sourcePath + "' in rule '" + ruleForError + "'", e, ruleForError);
        }
    }

    /**
     * Sets the value on the target builder based on the path and operator.
     */
    private void setValue(Message.Builder targetBuilder, String targetPath, Object sourceValue, String operator, String ruleForError) throws MappingException {
        try {
            PathResolverResult targetRes = resolvePath(targetBuilder, targetPath, true); // Resolve for setting

            if (targetRes.isStructKey()) {
                // Set value within a Struct field
                // FIX: Use the grandparent builder (the one containing the struct field)
                Object grandParentBuilderObj = targetRes.getGrandparentBuilder();
                Message.Builder structParentBuilder; // The builder containing the struct field

                // Handle case where the struct field is top-level
                if (grandParentBuilderObj == null && targetRes.getParentBuilder() instanceof Message.Builder) {
                    structParentBuilder = (Message.Builder) targetRes.getParentBuilder();
                } else if (grandParentBuilderObj instanceof Message.Builder) {
                    structParentBuilder = (Message.Builder) grandParentBuilderObj;
                } else {
                    throw new MappingException("Could not find valid parent builder for Struct field: " + targetPath, null, ruleForError);
                }

                // Use the parentField descriptor obtained from resolvePath (this IS the struct field descriptor)
                FieldDescriptor actualStructField = targetRes.getParentField();
                if (actualStructField == null || !actualStructField.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName())) {
                    throw new MappingException("Resolved parent field is not a Struct for setting value: " + targetPath, null, ruleForError);
                }

                // Pass the correct parent builder and struct field descriptor
                Struct.Builder structBuilder = getStructBuilder(structParentBuilder, actualStructField, targetPath);
                Value valueProto = wrapValue(sourceValue); // Convert Java object to Value
                structBuilder.putFields(targetRes.getFinalPathPart(), valueProto);
                // Set the struct back onto the correct parent builder
                structParentBuilder.setField(actualStructField, structBuilder.build());


            } else if (targetRes.isMapKey()) {
                // Set value within a Map field
                FieldDescriptor mapField = targetRes.getTargetField();
                if (!(targetRes.getParentBuilder() instanceof Message.Builder mapParentBuilder)) {
                    throw new MappingException("Parent object is not a Message.Builder when setting map value for path: " + targetPath, null, ruleForError);
                }
                String keyString = targetRes.getFinalPathPart();

                // Get Key/Value types from Map Field Descriptor
                Descriptor mapEntryDesc = mapField.getMessageType();
                FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key");
                FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");

                // Convert/Validate Key (using string from syntax)
                Object mapKey = convertValue(keyString, keyDesc, ruleForError, false);
                // Convert/Validate Value
                Object mapValue = convertValue(sourceValue, valueDesc, ruleForError, false);

                // Reconstruct current map entries into a mutable map
                Object currentMapObj = mapParentBuilder.getField(mapField);
                Map<Object, Object> mutableMap = new HashMap<>();
                if (currentMapObj instanceof List<?> currentMapEntries && !currentMapEntries.isEmpty()) {
                    for(Object entryObj : currentMapEntries) {
                        if (entryObj instanceof Message entryMsg) {
                            mutableMap.put(entryMsg.getField(keyDesc), entryMsg.getField(valueDesc));
                        }
                    }
                }

                // Put the new key-value pair
                mutableMap.put(mapKey, mapValue);

                // FIX: Convert the final Java Map back to a List<Message> and use addRepeatedField
                List<Message> mapEntriesList = convertJavaMapToMapFieldList(mutableMap, mapField);
                mapParentBuilder.clearField(mapField); // Clear existing entries
                for (Message entryMsg : mapEntriesList) {
                    mapParentBuilder.addRepeatedField(mapField, entryMsg);
                }


            } else if (targetRes.getTargetField() != null) {
                // Set value for a regular field
                FieldDescriptor targetField = targetRes.getTargetField();
                if (!(targetRes.getParentBuilder() instanceof Message.Builder parentBuilder)) {
                    throw new MappingException("Parent object is not a Message.Builder when setting field for path: " + targetPath, null, ruleForError);
                }

                // Handle operators and field types
                // FIX: Check isMapField BEFORE isRepeated
                if (targetField.isMapField()) {
                    Object convertedValue = convertValue(sourceValue, targetField, ruleForError, true); // Allow Map source for '=' and '+='
                    if (!(convertedValue instanceof Map<?, ?> sourceMapRaw)){
                        throw new MappingException("Type mismatch for map field '" + targetField.getName() + "': Cannot assign non-map value to Map", null, ruleForError);
                    }

                    // Type check and convert keys/values before setting/merging
                    Descriptor mapEntryDesc = targetField.getMessageType();
                    FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key");
                    FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");
                    Map<Object, Object> finalMap = new HashMap<>();

                    if ("=".equals(operator)) {
                        // Convert source map directly
                        for(Map.Entry<?,?> entry : sourceMapRaw.entrySet()) {
                            Object mapKey = convertValue(entry.getKey(), keyDesc, ruleForError, false);
                            Object mapValue = convertValue(entry.getValue(), valueDesc, ruleForError, false);
                            finalMap.put(mapKey, mapValue);
                        }
                    } else if ("+=".equals(operator)) {
                        // Merge maps: Reconstruct current map first
                        Object currentMapObj = parentBuilder.getField(targetField);
                        List<?> currentMapEntries;
                        if (currentMapObj instanceof List<?> list) {
                            currentMapEntries = list;
                        } else if (currentMapObj != null && currentMapObj.equals(Collections.emptyList())) {
                            currentMapEntries = Collections.emptyList(); // Start fresh if empty
                        } else {
                            throw new MappingException("Map Field '" + targetField.getName() + "' did not return a List object for merging (got " + (currentMapObj != null ? currentMapObj.getClass().getName() : "null") + ").", null, ruleForError);
                        }
                        // Reconstruct current map to merge into
                        for (Object entryObj : currentMapEntries) {
                            if (entryObj instanceof Message entryMsg) {
                                finalMap.put(entryMsg.getField(keyDesc), entryMsg.getField(valueDesc));
                            }
                        }
                        // Convert and add source map entries
                        for(Map.Entry<?,?> entry : sourceMapRaw.entrySet()) {
                            Object mapKey = convertValue(entry.getKey(), keyDesc, ruleForError, false);
                            Object mapValue = convertValue(entry.getValue(), valueDesc, ruleForError, false);
                            finalMap.put(mapKey, mapValue); // Overwrites existing keys
                        }
                    } else {
                        throw new MappingException("Unsupported operator '" + operator + "' for map field '" + targetField.getName() + "'", null, ruleForError);
                    }
                    // FIX: Convert the final Java Map back to a List<Message> and use addRepeatedField
                    List<Message> mapEntriesList = convertJavaMapToMapFieldList(finalMap, targetField);
                    parentBuilder.clearField(targetField); // Clear existing entries
                    for (Message entryMsg : mapEntriesList) {
                        parentBuilder.addRepeatedField(targetField, entryMsg);
                    }

                } else if (targetField.isRepeated()) { // Handle repeated fields AFTER maps
                    // Note: isMapField is false for repeated fields that are not maps
                    if ("=".equals(operator)) {
                        Object convertedValue = convertValue(sourceValue, targetField, ruleForError, true); // Allow list conversion
                        if (!(convertedValue instanceof List<?> sourceList)) {
                            throw new MappingException("Cannot assign non-list value to repeated field '" + targetField.getName() + "' using '=' operator. Use '+=' to append.", null, ruleForError);
                        }
                        // Type check elements within the list before setting
                        List<Object> targetList = new ArrayList<>(sourceList.size());
                        for(Object item : sourceList) {
                            targetList.add(convertRepeatedElement(item, targetField, ruleForError));
                        }
                        parentBuilder.setField(targetField, targetList); // Replace entire list
                    } else if ("+=".equals(operator)) {
                        if (sourceValue instanceof List<?> sourceList) {
                            // Append list elements
                            for (Object item : sourceList) {
                                parentBuilder.addRepeatedField(targetField, convertRepeatedElement(item, targetField, ruleForError)); // Append each item
                            }
                        } else {
                            // Append single item
                            parentBuilder.addRepeatedField(targetField, convertRepeatedElement(sourceValue, targetField, ruleForError)); // Append single item
                        }
                    } else {
                        throw new MappingException("Unsupported operator '" + operator + "' for repeated field '" + targetField.getName() + "'", null, ruleForError);
                    }
                } else { // Singular field
                    if (!"=".equals(operator)) {
                        throw new MappingException("Operator '" + operator + "' only supported for repeated or map fields.", null, ruleForError);
                    }
                    Object convertedValue = convertValue(sourceValue, targetField, ruleForError, false);
                    parentBuilder.setField(targetField, convertedValue);
                }
            } else {
                throw new MappingException("Could not resolve target path to a settable location: " + targetPath, null, ruleForError);
            }
        } catch (MappingException e) {
            // Add rule context to existing mapping exceptions
            throw new MappingException("Error setting value for target path '" + targetPath + "' in rule '" + ruleForError + "': " + e.getMessage(), e.getCause(), ruleForError);
        } catch (Exception e) {
            throw new MappingException("Error setting value for target path '" + targetPath + "' in rule '" + ruleForError + "'", e, ruleForError);
        }
    }


    // --- Type Conversion and Struct/Value Handling ---

    /**
     * Safely gets a Struct.Builder for a given field, handling DynamicMessage builders.
     * It merges the current value into a new Struct.Builder to avoid casting issues.
     */
    private Struct.Builder getStructBuilder(Message.Builder parentBuilder, FieldDescriptor structField, String pathForError) throws MappingException {
        // FIX: Simplify - trust the structField passed in (already validated by caller)
        // and use getFieldBuilder directly.
        Message.Builder genericBuilder;
        try {
            genericBuilder = parentBuilder.getFieldBuilder(structField);
        } catch (IllegalArgumentException e) {
            // This might still happen if the parentBuilder is somehow not what we expect,
            // or if the structField instance is subtly wrong despite having the right name/type.
            throw new MappingException("Error getting Struct builder for field '" + structField.getName() + "': " + e.getMessage()
                    + " (Parent Builder Type: " + parentBuilder.getDescriptorForType().getFullName()
                    + ", Field Containing Type: " + structField.getContainingType().getFullName() + ")", e, pathForError);
        }

        Message currentFieldValue = genericBuilder.buildPartial(); // Get current state

        Struct.Builder structBuilder = Struct.newBuilder(); // Always start fresh

        if (currentFieldValue instanceof Struct) {
            structBuilder.mergeFrom((Struct) currentFieldValue);
        } else if (currentFieldValue != null && !currentFieldValue.equals(Struct.getDefaultInstance())) {
            // Handle case where current value might be a DynamicMessage representing a Struct
            if (currentFieldValue.getDescriptorForType().equals(Struct.getDescriptor())) {
                // mergeFrom(Message) handles DynamicMessage correctly if descriptor matches
                structBuilder.mergeFrom(currentFieldValue);
            } else {
                // Log or throw? Let's throw for clarity.
                throw new MappingException("Cannot get Struct.Builder for field '" + structField.getName() + "': existing value is of unexpected type " + currentFieldValue.getClass().getName(), null, pathForError);
            }
        }
        // If currentFieldValue is null or default instance, we just return the empty builder

        return structBuilder;
    }


    /**
     * Converts a single element intended for a repeated field.
     * This method performs the type checking and conversion based on the
     * target repeated field's element type characteristics.
     *
     * @param sourceValue The source element value.
     * @param repeatedTargetField The descriptor for the target repeated field.
     * @param ruleForError The rule string for error context.
     * @return The converted value suitable for adding to the repeated field.
     * @throws MappingException If conversion fails.
     */
    private Object convertRepeatedElement(Object sourceValue, FieldDescriptor repeatedTargetField, String ruleForError) throws MappingException {
        // Extract element type information directly from the repeated field descriptor
        JavaType elementJavaType = repeatedTargetField.getJavaType();
        Descriptor elementMessageDesc = (elementJavaType == JavaType.MESSAGE) ? repeatedTargetField.getMessageType() : null;
        EnumDescriptor elementEnumDesc = (elementJavaType == JavaType.ENUM) ? repeatedTargetField.getEnumType() : null;
        String targetFieldName = repeatedTargetField.getName() + "[]"; // Indicate it's an element

        // Reuse the core conversion logic, passing the element's type info
        return convertSingleValue(sourceValue, elementJavaType, elementMessageDesc, elementEnumDesc, targetFieldName, ruleForError);
    }


    /**
     * Converts the source value if necessary to match the target field type.
     * Handles singular fields, map keys, and map values.
     *
     * @param sourceValue Source value object.
     * @param targetField Descriptor of the target field.
     * @param ruleForError The rule string for error context.
     * @param allowListOrMapSource If true, allows the sourceValue itself to be a List or Map (for direct list/map assignment).
     * @return The potentially converted value.
     * @throws MappingException If conversion is not possible or types mismatch.
     */
    private Object convertValue(Object sourceValue, FieldDescriptor targetField, String ruleForError, boolean allowListOrMapSource) throws MappingException {
        if (sourceValue == null) {
            // Handle null source value based on target type
            return getDefaultForNullSource(targetField);
        }

        boolean targetIsList = targetField.isRepeated() && !targetField.isMapField();
        boolean targetIsMap = targetField.isMapField();

        // Get source type information
        boolean sourceIsList = sourceValue instanceof List;
        boolean sourceIsMap = sourceValue instanceof Map;

        // --- Direct Assignment Checks (List/Map source/target) ---
        if (targetIsMap) { // Check map first, as map fields are also repeated
            if (sourceIsMap) {
                return sourceValue; // Assigning Map to Map (key/value conversion happens in setValue)
            } else {
                throw new MappingException(String.format("Type mismatch for map field '%s': Cannot assign %s to Map",
                        targetField.getName(), sourceIsMap ? "Map" : "non-map value"), null, ruleForError);
            }
        }
        if (targetIsList) {
            if (allowListOrMapSource && sourceIsList) {
                return sourceValue; // Assigning List to List (element conversion happens in setValue)
            } else if (!allowListOrMapSource && !sourceIsList) {
                // This case is for appending a single item to a list, handled by convertRepeatedElement
                return sourceValue;
            }
            else {
                throw new MappingException(String.format("Type mismatch for repeated field '%s': Cannot assign %s to List",
                        targetField.getName(), sourceIsList ? "List" : "single value"), null, ruleForError);
            }
        }

        // If target is singular, source cannot be List or Map
        if (sourceIsList || sourceIsMap) {
            throw new MappingException(String.format("Type mismatch for field '%s': Cannot assign %s to singular field",
                    targetField.getName(), sourceIsList ? "List" : "Map"), null, ruleForError);
        }

        // --- Singular Value Conversion ---
        return convertSingleValue(
                sourceValue,
                targetField.getJavaType(),
                targetField.getJavaType() == JavaType.MESSAGE ? targetField.getMessageType() : null, // Pass only if target is message
                targetField.getJavaType() == JavaType.ENUM ? targetField.getEnumType() : null,    // Pass only if target is enum
                targetField.getName(),
                ruleForError
        );
    }

    /**
     * Gets the appropriate default value for a target field when the source value is null.
     */
    private Object getDefaultForNullSource(FieldDescriptor targetField) {
        switch (targetField.getJavaType()) {
            case INT:       return 0;
            case LONG:      return 0L;
            case FLOAT:     return 0.0f;
            case DOUBLE:    return 0.0d;
            case BOOLEAN:   return false;
            case STRING:    return "";
            case BYTE_STRING: return ByteString.EMPTY;
            case ENUM:      return targetField.getEnumType().getValues().get(0); // Standard default is first value (index 0)
            case MESSAGE:
                // Check if it's Struct, return empty Struct if so
                if (targetField.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName())) {
                    return Struct.getDefaultInstance();
                }
                // Otherwise, return default instance of the message type
                return DynamicMessage.getDefaultInstance(targetField.getMessageType());
            default:
                // Should not happen
                return null;
        }
    }

    /**
     * Converts a single (non-list, non-map) source value to the target JavaType.
     *
     * @param sourceValue The source value (must not be List or Map).
     * @param targetJavaType The target JavaType.
     * @param targetMessageDesc The target message descriptor (only required if targetJavaType is MESSAGE).
     * @param targetEnumDesc The target enum descriptor (only required if targetJavaType is ENUM).
     * @param targetFieldName Name of the target field for error messages.
     * @param ruleForError The rule string for error context.
     * @return The converted value.
     * @throws MappingException If conversion fails.
     */
    private Object convertSingleValue(Object sourceValue,
                                      JavaType targetJavaType,
                                      Descriptor targetMessageDesc,
                                      EnumDescriptor targetEnumDesc,
                                      String targetFieldName,
                                      String ruleForError) throws MappingException {

        // Determine source type (must be singular at this point)
        JavaType sourceJavaType = getJavaTypeFromValue(sourceValue);
        if (sourceJavaType == null) {
            throw new MappingException(String.format("Unsupported source value type: %s for target field '%s'",
                    sourceValue.getClass().getName(), targetFieldName), null, ruleForError);
        }

        // --- Exact Type Match ---
        if (sourceJavaType == targetJavaType) {
            // Additional check for message types
            if (sourceJavaType == JavaType.MESSAGE) {
                if (targetMessageDesc == null) throw new IllegalStateException("Target MessageDescriptor is null for MESSAGE type field " + targetFieldName);
                if (sourceValue instanceof Message) {
                    Descriptor sourceDesc = ((Message) sourceValue).getDescriptorForType();
                    if (!sourceDesc.getFullName().equals(targetMessageDesc.getFullName())) {
                        throw new MappingException(String.format("Type mismatch for field '%s': Cannot assign message type '%s' to '%s'",
                                targetFieldName, sourceDesc.getFullName(), targetMessageDesc.getFullName()), null, ruleForError);
                    }
                } else { // Should not happen
                    throw new MappingException(String.format("Internal Error: Expected Message, got %s for field '%s'",
                            sourceValue.getClass().getName(), targetFieldName), null, ruleForError);
                }
            }
            // Additional check for enum types
            if (sourceJavaType == JavaType.ENUM) {
                if (targetEnumDesc == null) throw new IllegalStateException("Target EnumDescriptor is null for ENUM type field " + targetFieldName);
                if (sourceValue instanceof EnumValueDescriptor) {
                    EnumDescriptor sourceEnumDesc = ((EnumValueDescriptor) sourceValue).getType();
                    if (!sourceEnumDesc.getFullName().equals(targetEnumDesc.getFullName())) {
                        throw new MappingException(String.format("Type mismatch for field '%s': Cannot assign enum type '%s' to '%s'",
                                targetFieldName, sourceEnumDesc.getFullName(), targetEnumDesc.getFullName()), null, ruleForError);
                    }
                } else { // Should not happen
                    throw new MappingException(String.format("Internal Error: Expected EnumValueDescriptor, got %s for field '%s'",
                            sourceValue.getClass().getName(), targetFieldName), null, ruleForError);
                }
            }
            return sourceValue; // Types match
        }

        // --- Allowed Conversions ---
        try {
            switch (targetJavaType) {
                case LONG:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).longValue();
                    break;
                case FLOAT:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).floatValue();
                    break;
                case DOUBLE:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).doubleValue();
                    break;
                case INT:
                    if (sourceValue instanceof Number) return ((Number) sourceValue).intValue();
                    break;
                case STRING:
                    if (sourceJavaType == JavaType.INT || sourceJavaType == JavaType.LONG || sourceJavaType == JavaType.FLOAT ||
                            sourceJavaType == JavaType.DOUBLE || sourceJavaType == JavaType.BOOLEAN) {
                        return String.valueOf(sourceValue);
                    }
                    if (sourceJavaType == JavaType.ENUM) {
                        return ((EnumValueDescriptor) sourceValue).getName();
                    }
                    if (sourceJavaType == JavaType.BYTE_STRING) {
                        return ((ByteString) sourceValue).toStringUtf8();
                    }
                    break;
                case ENUM:
                    if (targetEnumDesc == null) {
                        throw new IllegalStateException("Target EnumDescriptor is null for ENUM type field " + targetFieldName);
                    }
                    if (sourceJavaType == JavaType.INT) {
                        EnumValueDescriptor enumValue = targetEnumDesc.findValueByNumber((Integer) sourceValue);
                        if (enumValue == null) {
                            throw new MappingException("Invalid enum number " + sourceValue + " for enum type " + targetEnumDesc.getFullName(), null, ruleForError);
                        }
                        return enumValue;
                    }
                    if (sourceJavaType == JavaType.STRING) {
                        EnumValueDescriptor enumValue = targetEnumDesc.findValueByName((String) sourceValue);
                        if (enumValue == null) {
                            throw new MappingException("Invalid enum name '" + sourceValue + "' for enum type " + targetEnumDesc.getFullName(), null, ruleForError);
                        }
                        return enumValue;
                    }
                    break;
                case BOOLEAN:
                    if (sourceJavaType == JavaType.STRING) {
                        String strVal = ((String) sourceValue).trim();
                        if (strVal.equalsIgnoreCase("true")) return true;
                        if (strVal.equalsIgnoreCase("false")) return false;
                    }
                    if (sourceValue instanceof Number) {
                        return ((Number) sourceValue).doubleValue() != 0.0;
                    }
                    break;
                case BYTE_STRING:
                    if (sourceJavaType == JavaType.STRING) {
                        return ByteString.copyFromUtf8((String) sourceValue);
                    }
                    break;
                // MESSAGE type handled by exact match check earlier
            }
        } catch (Exception e) {
            throw new MappingException(String.format("Error converting value '%s' (%s) to type %s for field '%s'",
                    sourceValue, sourceJavaType, targetJavaType, targetFieldName), e, ruleForError);
        }


        throw new MappingException(String.format("Type mismatch for field '%s': Cannot convert %s to %s",
                targetFieldName, sourceJavaType, targetJavaType), null, ruleForError);
    }


    /**
     * Determines the JavaType of a given non-list, non-map value. Returns null if type is unknown.
     */
    private JavaType getJavaTypeFromValue(Object value) {
        if (value instanceof Integer) return JavaType.INT;
        if (value instanceof Long) return JavaType.LONG;
        if (value instanceof Float) return JavaType.FLOAT;
        if (value instanceof Double) return JavaType.DOUBLE;
        if (value instanceof Boolean) return JavaType.BOOLEAN;
        if (value instanceof String) return JavaType.STRING;
        if (value instanceof ByteString) return JavaType.BYTE_STRING;
        if (value instanceof EnumValueDescriptor) return JavaType.ENUM;
        if (value instanceof Message) return JavaType.MESSAGE;
        // Lists and Maps are handled by checking instanceof List/Map directly
        return null; // Use null to indicate unknown/unhandled type
    }


    /**
     * Wraps a Java object into a Protobuf Value object for storing in a Struct.
     */
    private Value wrapValue(Object value) throws MappingException {
        Value.Builder valueBuilder = Value.newBuilder();
        if (value == null) {
            valueBuilder.setNullValue(NullValue.NULL_VALUE);
        } else if (value instanceof String) {
            valueBuilder.setStringValue((String) value);
        } else if (value instanceof Double || value instanceof Float) { // Handle floating point first
            valueBuilder.setNumberValue(((Number) value).doubleValue());
        } else if (value instanceof Number) { // Catches Integer, Long, Short, Byte
            valueBuilder.setNumberValue(((Number) value).doubleValue()); // Store all numbers as double in Struct
        } else if (value instanceof Boolean) {
            valueBuilder.setBoolValue((Boolean) value);
        } else if (value instanceof Struct) { // Handle Struct itself
            valueBuilder.setStructValue((Struct) value);
        } else if (value instanceof Struct.Builder) { // Handle Struct builder
            valueBuilder.setStructValue(((Struct.Builder) value));
        } else if (value instanceof ListValue) { // Handle ListValue itself
            valueBuilder.setListValue((ListValue) value);
        } else if (value instanceof ListValue.Builder) { // Handle ListValue builder
            valueBuilder.setListValue(((ListValue.Builder) value));
        }
        else if (value instanceof List) { // Handle generic List
            ListValue.Builder listBuilder = ListValue.newBuilder();
            for (Object item : (List<?>) value) {
                listBuilder.addValues(wrapValue(item)); // Recursive call
            }
            valueBuilder.setListValue(listBuilder);
        }
        else if (value instanceof Message) {
            // Convert arbitrary message to Struct via JSON as a fallback?
            // This adds dependency on protobuf-java-util JsonFormat.
            // For now, treat other messages as unsupported for direct wrapping.
            throw new MappingException("Cannot automatically wrap Message type " + value.getClass().getName() + " into Protobuf Value. Consider mapping fields individually or using JSON conversion.", null, null);
        }
        // Add handling for other types if needed (e.g., Enum -> String/Number?)
        else {
            throw new MappingException("Cannot wrap type " + value.getClass().getName() + " into Protobuf Value", null, null);
        }
        return valueBuilder.build();
    }

    /**
     * Unwraps a Protobuf Value object into its corresponding Java object.
     */
    private Object unwrapValue(Value valueProto) {
        switch (valueProto.getKindCase()) {
            case NUMBER_VALUE: return valueProto.getNumberValue(); // Returns double
            case STRING_VALUE: return valueProto.getStringValue();
            case BOOL_VALUE: return valueProto.getBoolValue();
            case STRUCT_VALUE: return valueProto.getStructValue(); // Returns Struct
            case LIST_VALUE:
                // Recursively unwrap list elements
                return valueProto.getListValue().getValuesList().stream()
                        .map(this::unwrapValue)
                        .collect(Collectors.toList());
            case NULL_VALUE:
            case KIND_NOT_SET:
            default:
                return null;
        }
    }

    /**
     * Converts a standard Java Map into the List<Message> representation
     * expected by DynamicMessage.Builder.setField for map fields.
     */
    private List<Message> convertJavaMapToMapFieldList(Map<Object, Object> javaMap, FieldDescriptor mapField) {
        List<Message> mapEntriesList = new ArrayList<>(javaMap.size());
        Descriptor mapEntryDesc = mapField.getMessageType();
        FieldDescriptor keyDesc = mapEntryDesc.findFieldByName("key");
        FieldDescriptor valueDesc = mapEntryDesc.findFieldByName("value");

        for (Map.Entry<Object, Object> entry : javaMap.entrySet()) {
            Message entryMsg = DynamicMessage.newBuilder(mapEntryDesc)
                    .setField(keyDesc, entry.getKey())
                    .setField(valueDesc, entry.getValue())
                    .build();
            mapEntriesList.add(entryMsg);
        }
        return mapEntriesList;
    }


    // --- Custom Exception ---
    public static class MappingException extends Exception {
        private final String failedRule;

        public MappingException(String message, Throwable cause, String failedRule) {
            super(message, cause);
            this.failedRule = failedRule;
        }

        public String getFailedRule() {
            return failedRule;
        }

        @Override
        public String getMessage() {
            String baseMessage = super.getMessage();
            if (failedRule != null && !failedRule.isEmpty()) {
                return baseMessage + " (Rule: '" + failedRule + "')";
            }
            return baseMessage;
        }
    }
}
