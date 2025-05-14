package com.krickert.yappy.modules.chunker;

// This could be in its own file or nested if appropriate
public record Chunk(String id, String text, int originalIndexStart, int originalIndexEnd) {
}