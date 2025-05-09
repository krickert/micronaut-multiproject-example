package com.krickert.search.model.mapper;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.Struct;

/**
 * class to hold the result of path resolution.
 */
public class PathResolverResult {
    private final Object parent;
    private final Object grandparent;
    private final Descriptors.FieldDescriptor parentField;
    private final Descriptors.FieldDescriptor targetField;
    private final String finalPathPart; // The key if target is a Struct or Map key
    private final boolean isStructKey;
    private final boolean isMapKey;

    PathResolverResult(Object parent, Object grandparent, Descriptors.FieldDescriptor parentField, Descriptors.FieldDescriptor targetField, String finalPathPart, boolean isStructKey, boolean isMapKey) {
        this.parent = parent;
        this.grandparent = grandparent;
        this.parentField = parentField;
        this.targetField = targetField;
        this.finalPathPart = finalPathPart;
        this.isStructKey = isStructKey;
        this.isMapKey = isMapKey;
    }

    Object getParentBuilder() {
        //noinspection ConstantValue
        if (!(parent instanceof Message.Builder || parent instanceof Struct.Builder)) {
            if (parent instanceof MessageOrBuilder) return parent;
            throw new IllegalStateException("Parent is not a builder or message type: " + (parent != null ? parent.getClass().getName() : "null"));
        }
        return parent;
    }

    Object getParentMessageOrBuilder() {
        if (parent instanceof MessageOrBuilder) {
            return parent;
        }
        throw new IllegalStateException("Parent cannot be read as MessageOrBuilder: " + (parent != null ? parent.getClass().getName() : "null"));
    }

    Object getGrandparentBuilder() {
        if (grandparent != null && !(grandparent instanceof Message.Builder)) {
            //noinspection ConstantValue
            throw new IllegalStateException("Grandparent is not a Message.Builder: " + (grandparent != null ? grandparent.getClass().getName() : "null"));
        }
        return grandparent;
    }

    Object getGrandparentMessageOrBuilder() {
        if (grandparent != null && !(grandparent instanceof MessageOrBuilder)) {
            //noinspection ConstantValue
            throw new IllegalStateException("Grandparent is not readable: " + (grandparent != null ? grandparent.getClass().getName() : "null"));
        }
        return grandparent;
    }

    Descriptors.FieldDescriptor getParentField() {
        return parentField;
    }

    Descriptors.FieldDescriptor getTargetField() {
        return targetField;
    }

    String getFinalPathPart() {
        return finalPathPart;
    }

    boolean isStructKey() {
        return isStructKey;
    }

    boolean isMapKey() {
        return isMapKey;
    }
}
