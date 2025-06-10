package com.krickert.yappy.modules.chunker;

import java.util.List;
import java.util.Map;

/**
 * Result of the chunking operation containing chunks and URL mappings.
 */
public record ChunkingResult(
    List<Chunk> chunks,
    Map<String, String> placeholderToUrlMap
) {}