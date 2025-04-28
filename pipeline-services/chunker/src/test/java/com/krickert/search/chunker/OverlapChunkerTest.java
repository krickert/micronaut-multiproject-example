package com.krickert.search.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OverlapChunkerTest {

    @Test
    void testChunkTextSplitNewline() {
        OverlapChunker chunker = new OverlapChunker();
        String text = "This is a test.\nThis is another test.\nAnd this is a third test.";
        List<String> chunks = chunker.chunkTextSplitNewline(text, 20, 5);
        
        // Print the chunks for debugging
        System.out.println("[DEBUG_LOG] Chunks: " + chunks);
        
        // Verify that we have chunks
        assertFalse(chunks.isEmpty(), "Chunks should not be empty");
        
        // Verify that each chunk is not longer than the chunk size plus some margin
        // (margin is needed because the chunker might extend beyond the chunk size to avoid breaking words)
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 30, "Chunk length should not exceed chunk size plus margin: " + chunk);
        }
    }

    @Test
    void testSquishText() {
        OverlapChunker chunker = new OverlapChunker();
        String text = "  This is a test.  \n\n  This is another test.  \n  And this is a third test.  ";
        List<String> lines = chunker.squishText(text);
        
        // Print the lines for debugging
        System.out.println("[DEBUG_LOG] Lines: " + lines);
        
        // Verify that we have the expected number of lines
        assertEquals(3, lines.size(), "Should have 3 lines");
        
        // Verify that each line is trimmed
        assertEquals("This is a test.", lines.get(0));
        assertEquals("This is another test.", lines.get(1));
        assertEquals("And this is a third test.", lines.get(2));
    }

    @Test
    void testTransformURLsToWords() {
        OverlapChunker chunker = new OverlapChunker();
        String text = "Visit https://www.example.com/path/to/page?param=value for more information.";
        String transformed = chunker.transformURLsToWords(text);
        
        // Print the transformed text for debugging
        System.out.println("[DEBUG_LOG] Transformed: " + transformed);
        
        // Verify that the URL has been transformed
        assertFalse(transformed.contains("https://www.example.com"), "URL should be transformed");
        assertTrue(transformed.contains("example"), "Domain name should be preserved");
        assertTrue(transformed.contains("path to page param value"), "Path and query parameters should be transformed to words");
    }
}