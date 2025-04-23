// src/main/java/com/krickert/search/model/PathResolver.java (Corrected)
package com.krickert.search.model.mapper;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;

import java.util.List;
import java.util.Collections; // Import Collections

/**
 * Resolves paths within Protobuf messages or builders.
 * Handles simple fields, nested messages, map keys, struct keys.
 * Logic extracted from the original ProtoMapper.
 */
public class PathResolver {

    private static final String PATH_SEPARATOR_REGEX = "\\.";

    /**
     * Internal class to hold the result of path resolution.
     * Copied directly from the original ProtoMapper.
     */
     // NOTE: This class was significantly complex in the original. Copying directly.
     // Consider simplifying this if possible in future refactorings, but not now.
     static class PathResolverResult {
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
                 // Allow reading from message (original behavior)
                if (parent instanceof MessageOrBuilder) return parent;
                throw new IllegalStateException("Parent is not a builder or message type: " + (parent != null ? parent.getClass().getName() : "null"));
            }
            return parent;
        }
        Object getParentMessageOrBuilder() { // Convenience for getting
             // Check if it's Message, Builder, or Struct (Struct is MessageOrBuilder)
            if (parent instanceof MessageOrBuilder) {
                return (MessageOrBuilder) parent;
            }
             throw new IllegalStateException("Parent cannot be read as MessageOrBuilder: " + (parent != null ? parent.getClass().getName() : "null"));
        }
        Object getGrandparentBuilder() { // Convenience for setting struct back
            if (grandparent != null && !(grandparent instanceof Message.Builder)) {
                throw new IllegalStateException("Grandparent is not a Message.Builder: " + (grandparent != null ? grandparent.getClass().getName() : "null"));
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
     * Copied directly from the original ProtoMapper.
     *
     * @param root          The starting Message or Message.Builder.
     * @param path          The dot-separated path string (e.g., "field.nested.key", "map[\"key\"]").
     * @param resolveForSet If true, creates/gets builders for nested messages; if false, only reads.
     * @param ruleForError The rule string for error context. // Added for consistency
     * @return PathResolverResult containing the resolution details.
     * @throws MappingException If the path is invalid or cannot be resolved.
     */
    public PathResolverResult resolvePath(Object root, String path, boolean resolveForSet, String ruleForError) throws MappingException {
         // --- Start of code copied directly from ProtoMapper.resolvePath ---
         String[] parts = path.split(PATH_SEPARATOR_REGEX);
        Object currentObj = root; // Can be Message or Message.Builder
        Object parentObj = null;
        FieldDescriptor parentFd = null;
        Descriptor currentDesc;

        // Get initial descriptor
        if (root instanceof MessageOrBuilder) {
            currentDesc = ((MessageOrBuilder) root).getDescriptorForType();
        } else {
            throw new MappingException("Invalid root object type for path resolution: " + root.getClass().getName(), null, ruleForError);
        }


        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String mapKey = null;

            // Check for map access syntax: field["key"]
             if (part.contains("[") && part.endsWith("]")) {
                int bracketStart = part.indexOf('[');
                if (bracketStart > 0) {
                    String keyPart = part.substring(bracketStart + 1, part.length() - 1);
                    if (keyPart.startsWith("\"") && keyPart.endsWith("\"") && keyPart.length() >= 2) {
                        mapKey = keyPart.substring(1, keyPart.length() - 1);
                    } else {
                         mapKey = keyPart; // Allow unquoted keys as per original regex logic
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

            boolean isLastPart = (i == parts.length - 1);

            // If accessing a map key
            if (mapKey != null) {
                if (!fd.isMapField()) {
                    throw new MappingException("Field '" + part + "' is not a map field, cannot access with key in path '" + path + "'", null, ruleForError);
                }
                if (!isLastPart) {
                    throw new MappingException("Map key access must be the last part of the path: '" + path + "'", null, ruleForError);
                }
                return new PathResolverResult(currentObj, parentObj, parentFd, fd, mapKey, false, true);
            }

            // If accessing a struct key
            if (fd.getJavaType() == JavaType.MESSAGE && fd.getMessageType().getFullName().equals(Struct.getDescriptor().getFullName())) {
                if (isLastPart) {
                    return new PathResolverResult(currentObj, parentObj, parentFd, fd, null, false, false);
                } else {
                    String structKey = parts[i + 1];
                    if (i + 1 != parts.length - 1) {
                        throw new MappingException("Path cannot continue after Struct key: '" + path + "'", null, ruleForError);
                    }
                    Object structParent = currentObj;
                    FieldDescriptor structFieldDesc = fd;

                    if (resolveForSet) {
                        if (!(structParent instanceof Message.Builder)) {
                            throw new MappingException("Cannot resolve path for setting: intermediate object is not a Builder at Struct field '" + part + "'", null, ruleForError);
                        }
                        return new PathResolverResult(structParent, parentObj, structFieldDesc, null, structKey, true, false);
                    } else {
                        if (!(structParent instanceof MessageOrBuilder)) {
                            throw new MappingException("Cannot resolve path for getting: intermediate object is not a MessageOrBuilder at Struct field '" + part + "'", null, ruleForError);
                        }
                        if (!((MessageOrBuilder) structParent).hasField(structFieldDesc)) {
                             throw new MappingException("Cannot resolve key '" + structKey + "' in unset Struct field '" + part + "' in path '" + path + "'", null, ruleForError);
                        }
                        Object structMsg = ((MessageOrBuilder) structParent).getField(structFieldDesc);
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
                throw new MappingException("Cannot traverse into map field '" + part + "' using dot notation in path '" + path + "'. Use map[\"key\"] syntax.", null, ruleForError);
            }

            if (fd.isRepeated()) { // Repeated field traversal logic
                Object listOrBuilder;
                if (resolveForSet) {
                    if (!(currentObj instanceof Message.Builder)) {
                        throw new MappingException("Cannot resolve path for setting: intermediate object is not a Builder at repeated field '" + part + "'", null, ruleForError);
                    }
                    listOrBuilder = ((Message.Builder) currentObj).getField(fd);
                } else {
                    if (!(currentObj instanceof MessageOrBuilder)) {
                        throw new MappingException("Cannot resolve path for getting: intermediate object is not a MessageOrBuilder at repeated field '" + part + "'", null, ruleForError);
                    }
                    if (!((MessageOrBuilder) currentObj).hasField(fd) && ((MessageOrBuilder) currentObj).getRepeatedFieldCount(fd) == 0) {
                         throw new MappingException("Cannot resolve path '" + path + "': intermediate repeated field '" + part + "' is not set or empty.", null, ruleForError);
                    }
                    listOrBuilder = ((MessageOrBuilder) currentObj).getField(fd);
                }

                // *** CORRECTED BLOCK START ***
                if (!(listOrBuilder instanceof List<?>)) { // Check type first
                    // Check if it's the default empty list explicitly
                    if (listOrBuilder != null && listOrBuilder.equals(Collections.emptyList())) {
                         throw new MappingException("Cannot resolve path '" + path + "': intermediate repeated field '" + part + "' is empty.", null, ruleForError);
                    }
                    // If it's not a List and not the default empty list, then it's an unexpected type
                    throw new MappingException("Field '" + part + "' did not return a List object (got " + (listOrBuilder != null ? listOrBuilder.getClass().getName() : "null") + ").", null, ruleForError);
                }

                // If we reach here, listOrBuilder is definitely a List<?>
                List<?> listValue = (List<?>) listOrBuilder; // Cast is safe now
                // *** CORRECTED BLOCK END ***


                if (listValue.isEmpty()) {
                    throw new MappingException("Cannot resolve path '" + path + "': intermediate repeated field '" + part + "' is empty.", null, ruleForError);
                }
                // Original implicitly took the first element for traversal
                currentObj = listValue.get(0);
                if (currentObj == null) {
                    throw new MappingException("Cannot resolve path '" + path + "': first element of intermediate repeated field '" + part + "' is null.", null, ruleForError);
                }
                // Update descriptor for the element type
                if (fd.getJavaType() == JavaType.MESSAGE) {
                    if (!(currentObj instanceof MessageOrBuilder)) {
                        throw new MappingException("Element in repeated field '" + part + "' is not a MessageOrBuilder", null, ruleForError);
                    }
                    currentDesc = ((MessageOrBuilder) currentObj).getDescriptorForType();
                } else {
                    throw new MappingException("Cannot traverse into primitive element of repeated field '" + part + "' in path '" + path + "'", null, ruleForError);
                }
                 parentObj = null;
                 parentFd = null;
                continue; // Continue to next part of the path relative to the list element

            } else if (fd.getJavaType() == JavaType.MESSAGE) { // Singular message
                parentObj = currentObj;
                parentFd = fd;
                if (resolveForSet) {
                    if (!(currentObj instanceof Message.Builder)) {
                        throw new MappingException("Cannot resolve path for setting: intermediate object is not a Builder at field '" + part + "'", null, ruleForError);
                    }
                    currentObj = ((Message.Builder) currentObj).getFieldBuilder(fd);
                } else {
                    if (!(currentObj instanceof MessageOrBuilder)) {
                        throw new MappingException("Cannot resolve path for getting: intermediate object is not a MessageOrBuilder at field '" + part + "'", null, ruleForError);
                    }
                     if (!((MessageOrBuilder) currentObj).hasField(fd)) {
                          throw new MappingException("Cannot resolve path '" + path + "': intermediate message field '" + part + "' is not set.", null, ruleForError);
                     }
                    currentObj = ((MessageOrBuilder) currentObj).getField(fd);
                }
                if (currentObj instanceof MessageOrBuilder) {
                    currentDesc = ((MessageOrBuilder) currentObj).getDescriptorForType();
                } else {
                    throw new MappingException("Unexpected intermediate object type " + (currentObj != null ? currentObj.getClass().getName() : "null") + " during path resolution at '" + part + "'", null, ruleForError);
                }
            } else { // Singular primitive/enum/bytes
                throw new MappingException("Cannot traverse into non-message field '" + part + "' in path '" + path + "'", null, ruleForError);
            }
        } // End for loop

        // Should not be reached if path is valid
        throw new MappingException("Path resolution failed unexpectedly for path: " + path, null, ruleForError);
         // --- End of code copied directly from ProtoMapper.resolvePath ---
    }
}