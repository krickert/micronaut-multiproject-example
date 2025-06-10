package com.krickert.yappy.modules.chunker;

/**
 * Represents a single chunk of text with metadata.
 * This is the internal representation before converting to protobuf SemanticChunk.
 */
public record Chunk(
    String id,
    String text,
    int originalIndexStart,
    int originalIndexEnd
) {}