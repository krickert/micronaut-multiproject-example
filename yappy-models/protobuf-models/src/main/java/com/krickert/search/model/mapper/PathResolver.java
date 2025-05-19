// src/main/java/com/krickert/search/model/mapper/PathResolver.java (Corrected Again 2)
package com.krickert.search.model.mapper;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

/**
 * Resolves paths within Protobuf messages or builders.
 * Handles simple fields, nested messages, map keys, struct keys.
 * Logic extracted from the original ProtoMapper.
 */
public class PathResolver {

    private static final String PATH_SEPARATOR_REGEX = "\\.";

    /**
     * Resolves a path within a message or builder.
     */
    public PathResolverResult resolvePath(Object root, String path, boolean resolveForSet, String ruleForError) throws MappingException {
        String[] parts = path.split(PATH_SEPARATOR_REGEX);
        Object currentObj = root;
        Object parentObj = null;
        FieldDescriptor parentFd = null;
        Descriptor currentDesc; // Will be set if currentObj is MessageOrBuilder

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String mapKey = null;
            boolean isLastPart = (i == parts.length - 1);

            // --- Handle case where current object is already a Struct ---
            if (currentObj instanceof Struct currentStruct) {
                // Current part is the key
                // parentFd should be the FD that led to this struct, set in previous iteration

                Value valueProto = currentStruct.getFieldsOrDefault(part, null);

                if (isLastPart) {
                    // Reached the end, return info about the key within the struct
                    // Parent is the struct, grandparent is what held the struct field (parentObj)
                    return new PathResolverResult(currentStruct, parentObj, parentFd, null, part, true, false);
                } else {
                    // Path continues, value must be a struct to traverse further
                    //noinspection ConstantValue
                    if (valueProto == null || !valueProto.hasStructValue()) {
                        throw new MappingException("Path cannot continue after non-struct Struct key '" + part + "' in path: '" + path + "'", null, ruleForError);
                    }
                    // Update current object to the nested struct and continue loop
                    parentObj = currentStruct; // Parent becomes the struct we were just in
                    currentObj = valueProto.getStructValue(); // currentObj is the nested struct
                    // parentFd remains the same (field that led to the outer struct) - is this right? Or reset? Let's reset.
                    parentFd = null;
                    continue; // Continue to next part
                }
            } // --- End Struct Handling ---


            // If not already handled as a struct, currentObj should be MessageOrBuilder
            if (!(currentObj instanceof MessageOrBuilder)) {
                // This could happen if previous step resolved to a primitive via struct/map key access but path continued
                throw new MappingException("Cannot resolve path: intermediate object is not a MessageOrBuilder at part '" + part + "'", null, ruleForError);
            }
            // We have a MessageOrBuilder, get its descriptor
            currentDesc = ((MessageOrBuilder) currentObj).getDescriptorForType();


            // Check for map access syntax: field["key"]
            if (part.contains("[") && part.endsWith("]")) {
                int bracketStart = part.indexOf('[');
                if (bracketStart > 0) {
                    String keyPart = part.substring(bracketStart + 1, part.length() - 1);
                    if (keyPart.startsWith("\"") && keyPart.endsWith("\"") && keyPart.length() >= 2) {
                        mapKey = keyPart.substring(1, keyPart.length() - 1);
                    } else if (!keyPart.contains("\"")) {
                        mapKey = keyPart;
                    } else {
                        throw new MappingException("Invalid map key quoting in path: " + path, null, ruleForError);
                    }
                    part = part.substring(0, bracketStart);
                } else {
                    throw new MappingException("Invalid map access syntax in path: " + path, null, ruleForError);
                }
            }


            FieldDescriptor fd = currentDesc.findFieldByName(part);
            if (fd == null) {
                throw new MappingException("Field not found: '" + part + "' in path '" + path + "' for type " + currentDesc.getFullName(), null, ruleForError);
            }

            // --- Process based on field descriptor 'fd' ---

            if (mapKey != null) {
                if (!fd.isMapField()) {
                    throw new MappingException("Field '" + part + "' is not a map field, cannot access with key in path '" + path + "'", null, ruleForError);
                }
                if (!isLastPart) {
                    throw new MappingException("Map key access must be the last part of the path: '" + path + "'", null, ruleForError);
                }
                return new PathResolverResult(currentObj, parentObj, parentFd, fd, mapKey, false, true);
            }

            if (fd.getJavaType() == JavaType.MESSAGE && fd.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName())) {
                if (isLastPart) {
                    // Path ends on the struct field itself
                    return new PathResolverResult(currentObj, parentObj, parentFd, fd, null, false, false);
                } else {
                    // Path continues into the struct field (e.g., "custom_data.key")
                    // String structKey = parts[i + 1]; // Next part is the key

                    Object structParent = currentObj;

                    if (resolveForSet) {
                        // Setting: Delegate to ValueHandler later, just return needed info
                        String structKey = parts[i + 1]; // Assume next part is key
                        if (i + 1 != parts.length - 1) { // Check if key is the actual last part for set operation
                            throw new MappingException("Path cannot continue after Struct key when setting value: '" + path + "'", null, ruleForError);
                        }
                        return new PathResolverResult(structParent, parentObj, fd, null, structKey, true, false);
                    } else {
                        // Getting: Need to get the struct message to continue traversal in the next loop iteration
                        if (!((MessageOrBuilder) structParent).hasField(fd)) {
                            throw new MappingException("Cannot resolve path '" + path + "': intermediate struct field '" + part + "' is not set.", null, ruleForError);
                        }
                        Object structMsgObj = ((MessageOrBuilder) structParent).getField(fd);
                        if (!(structMsgObj instanceof Struct)) {
                            throw new MappingException("Field '" + part + "' did not return a Struct object (got " + (structMsgObj != null ? structMsgObj.getClass().getName() : "null") + ").", null, ruleForError);
                        }

                        // Update state to continue resolving *within* the struct in the next iteration
                        parentObj = currentObj;      // The object containing the struct field becomes the grandparent for the key access
                        currentObj = structMsgObj;   // Current object becomes the struct itself
                        // Clear descriptor, next iteration starts with struct handling block
                        parentFd = fd;             // Remember the field that led to this struct
                        continue; // Continue to next part (which should be the key)
                    }
                }
            } // End Struct Field Handling

            // If it's the last part (and not map/struct field handled above), return current object and field
            if (isLastPart) {
                return new PathResolverResult(currentObj, parentObj, parentFd, fd, null, false, false);
            }

            // --- If not the last part, check field type for traversal ---

            if (fd.isMapField()) {
                throw new MappingException("Cannot traverse into map field '" + part + "' using dot notation in path '" + path + "'. Use map[\"key\"] syntax.", null, ruleForError);
            }

            if (fd.isRepeated()) {
                throw new MappingException("Cannot traverse into repeated field '" + part + "' using dot notation. Use index access (e.g., field[0].subfield) if supported, or map to the whole list.", null, ruleForError);
            } else if (fd.getJavaType() == JavaType.MESSAGE) { // Singular message
                parentObj = currentObj;
                parentFd = fd;
                if (resolveForSet) {
                    if (!(currentObj instanceof Message.Builder)) {
                        throw new MappingException("Cannot resolve path for setting: intermediate object is not a Builder at field '" + part + "'", null, ruleForError);
                    }
                    currentObj = ((Message.Builder) currentObj).getFieldBuilder(fd);
                } else {
                    if (!((MessageOrBuilder) currentObj).hasField(fd)) {
                        throw new MappingException("Cannot resolve path '" + path + "': intermediate message field '" + part + "' is not set.", null, ruleForError);
                    }
                    currentObj = ((MessageOrBuilder) currentObj).getField(fd);
                }
            } else { // Singular primitive/enum/bytes
                throw new MappingException("Cannot traverse into non-message field '" + part + "' in path '" + path + "'", null, ruleForError);
            }
        } // End for loop

        // Should not be reached if path is valid
        throw new MappingException("Path resolution failed unexpectedly for path: " + path, null, ruleForError);
    }
}