package com.krickert.yappy.modules.chunker;

import java.util.List;
import java.util.Map;
import java.util.Collections; // Import Collections

// Record to hold the result of the chunking process
public record ChunkingResult(
        List<Chunk> chunks,
        Map<String, String> placeholderToUrlMap // Map of placeholder -> original URL
) {
    // Provide a default for the map if no URLs were preserved
    public ChunkingResult(List<Chunk> chunks, Map<String, String> placeholderToUrlMap) {
        this.chunks = chunks;
        this.placeholderToUrlMap = placeholderToUrlMap != null ? placeholderToUrlMap : Collections.emptyMap();
    }
}