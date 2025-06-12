package com.krickert.search.model.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for TestDataGenerationConfig.
 */
public class TestDataGenerationConfigTest {
    
    @BeforeEach
    void setUp() {
        // Clear all test-related system properties
        System.clearProperty(TestDataGenerationConfig.REGENERATE_PROPERTY);
        System.clearProperty(TestDataGenerationConfig.OUTPUT_DIR_PROPERTY);
        System.clearProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY);
    }
    
    @AfterEach
    void tearDown() {
        // Clean up after tests
        setUp();
    }
    
    @Test
    void testIsRegenerationEnabledDefault() {
        assertFalse(TestDataGenerationConfig.isRegenerationEnabled(), 
                "Regeneration should be disabled by default");
    }
    
    @Test
    void testIsRegenerationEnabledTrue() {
        System.setProperty(TestDataGenerationConfig.REGENERATE_PROPERTY, "true");
        assertTrue(TestDataGenerationConfig.isRegenerationEnabled(), 
                "Regeneration should be enabled when property is true");
    }
    
    @Test
    void testIsRegenerationEnabledFalse() {
        System.setProperty(TestDataGenerationConfig.REGENERATE_PROPERTY, "false");
        assertFalse(TestDataGenerationConfig.isRegenerationEnabled(), 
                "Regeneration should be disabled when property is false");
    }
    
    @Test
    void testGetOutputDirectoryDefault() {
        String outputDir = TestDataGenerationConfig.getOutputDirectory();
        assertEquals(System.getProperty("java.io.tmpdir"), outputDir, 
                "Default output directory should be system temp dir");
    }
    
    @Test
    void testGetOutputDirectoryCustom() {
        String customDir = "/custom/output/dir";
        System.setProperty(TestDataGenerationConfig.OUTPUT_DIR_PROPERTY, customDir);
        
        String outputDir = TestDataGenerationConfig.getOutputDirectory();
        assertEquals(customDir, outputDir, 
                "Should return custom output directory when set");
    }
    
    @Test
    void testIsDeterministicModeDefault() {
        assertTrue(TestDataGenerationConfig.isDeterministicMode(), 
                "Deterministic mode should be enabled by default");
    }
    
    @Test
    void testIsDeterministicModeTrue() {
        System.setProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY, "true");
        assertTrue(TestDataGenerationConfig.isDeterministicMode(), 
                "Deterministic mode should be enabled when property is true");
    }
    
    @Test
    void testIsDeterministicModeFalse() {
        System.setProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY, "false");
        assertFalse(TestDataGenerationConfig.isDeterministicMode(), 
                "Deterministic mode should be disabled when property is false");
    }
    
    @Test
    void testGetResourcesDirectory() {
        String resourcesDir = TestDataGenerationConfig.getResourcesDirectory();
        assertEquals("src/main/resources/test-data/sample-documents", resourcesDir,
                "Resources directory should be the standard location");
    }
    
    @Test
    void testPropertyNames() {
        // Verify property names are as expected
        assertEquals("yappy.test.data.regenerate", TestDataGenerationConfig.REGENERATE_PROPERTY);
        assertEquals("yappy.test.data.output.dir", TestDataGenerationConfig.OUTPUT_DIR_PROPERTY);
        assertEquals("yappy.test.data.deterministic", TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY);
    }
    
    @Test
    void testInvalidPropertyValues() {
        // Test that invalid boolean values default to false
        System.setProperty(TestDataGenerationConfig.REGENERATE_PROPERTY, "invalid");
        assertFalse(TestDataGenerationConfig.isRegenerationEnabled(), 
                "Invalid boolean should default to false");
        
        System.setProperty(TestDataGenerationConfig.DETERMINISTIC_IDS_PROPERTY, "invalid");
        assertFalse(TestDataGenerationConfig.isDeterministicMode(), 
                "Invalid boolean should default to false for deterministic mode");
    }
}