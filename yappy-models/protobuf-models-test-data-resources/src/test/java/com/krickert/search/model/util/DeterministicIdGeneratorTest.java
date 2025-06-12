package com.krickert.search.model.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for DeterministicIdGenerator.
 */
public class DeterministicIdGeneratorTest {
    
    @BeforeEach
    void setUp() {
        // Reset to default configuration
        System.clearProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY);
    }
    
    @Test
    void testGenerateFromContent() {
        // Test with same content produces same ID
        String content = "This is test content";
        String id1 = DeterministicIdGenerator.generateFromContent(content);
        String id2 = DeterministicIdGenerator.generateFromContent(content);
        
        assertEquals(id1, id2, "Same content should produce same ID");
        assertEquals(16, id1.length(), "ID should be 16 characters (8 bytes in hex)");
        
        // Test with different content produces different ID
        String differentContent = "Different content";
        String id3 = DeterministicIdGenerator.generateFromContent(differentContent);
        
        assertNotEquals(id1, id3, "Different content should produce different ID");
    }
    
    @Test
    void testGenerateFromContentWithNull() {
        String id = DeterministicIdGenerator.generateFromContent(null);
        assertNotNull(id, "Should handle null content");
        assertEquals("00000000", id, "Null content should produce index 0 ID");
    }
    
    @Test
    void testGenerateFromContentWithEmpty() {
        String id = DeterministicIdGenerator.generateFromContent("");
        assertNotNull(id, "Should handle empty content");
        assertEquals("00000000", id, "Empty content should produce index 0 ID");
    }
    
    @Test
    void testGenerateFromIndex() {
        // Test various indices
        assertEquals("00000000", DeterministicIdGenerator.generateFromIndex(0));
        assertEquals("00000001", DeterministicIdGenerator.generateFromIndex(1));
        assertEquals("000000ff", DeterministicIdGenerator.generateFromIndex(255));
        assertEquals("00001000", DeterministicIdGenerator.generateFromIndex(4096));
        
        // Test consistency
        String id1 = DeterministicIdGenerator.generateFromIndex(42);
        String id2 = DeterministicIdGenerator.generateFromIndex(42);
        assertEquals(id1, id2, "Same index should produce same ID");
    }
    
    @Test
    void testGenerateComposite() {
        // Test with content
        String id1 = DeterministicIdGenerator.generateComposite("doc", 1, "content");
        String id2 = DeterministicIdGenerator.generateComposite("doc", 1, "content");
        assertEquals(id1, id2, "Same inputs should produce same ID");
        
        // Test without content
        String id3 = DeterministicIdGenerator.generateComposite("doc", 1, null);
        assertEquals("00000001", id3, "Without content should use index-based ID");
        
        // Test that different prefixes produce different IDs
        String id4 = DeterministicIdGenerator.generateComposite("stream", 1, "content");
        assertNotEquals(id1, id4, "Different prefixes should produce different IDs");
    }
    
    @Test
    void testGenerateIdInDeterministicMode() {
        // Enable deterministic mode
        System.setProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY, "true");
        
        String id1 = DeterministicIdGenerator.generateId("test", 1, "content");
        String id2 = DeterministicIdGenerator.generateId("test", 1, "content");
        
        assertEquals(id1, id2, "Deterministic mode should produce consistent IDs");
    }
    
    @Test
    void testGenerateIdInRandomMode() {
        // Disable deterministic mode
        System.setProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY, "false");
        
        String id1 = DeterministicIdGenerator.generateId("test", 1, "content");
        String id2 = DeterministicIdGenerator.generateId("test", 1, "content");
        
        // In random mode, IDs should be different (with very high probability)
        assertNotEquals(id1, id2, "Random mode should produce different IDs");
        
        // But they should still be 8 characters
        assertEquals(8, id1.length(), "Random ID should be 8 characters");
        assertEquals(8, id2.length(), "Random ID should be 8 characters");
    }
    
    @Test
    void testIdLengthConsistency() {
        // Test that all generation methods produce IDs of consistent length
        assertEquals(8, DeterministicIdGenerator.generateFromIndex(0).length());
        assertEquals(16, DeterministicIdGenerator.generateFromContent("test").length());
        assertEquals(8, DeterministicIdGenerator.generateComposite("prefix", 0, null).length());
        assertEquals(8, DeterministicIdGenerator.generateComposite("prefix", 0, "content").length());
        
        // Test with deterministic mode
        System.setProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY, "true");
        assertEquals(8, DeterministicIdGenerator.generateId("test", 0, "content").length());
        
        // Test with random mode
        System.setProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY, "false");
        assertEquals(8, DeterministicIdGenerator.generateId("test", 0, "content").length());
    }
}