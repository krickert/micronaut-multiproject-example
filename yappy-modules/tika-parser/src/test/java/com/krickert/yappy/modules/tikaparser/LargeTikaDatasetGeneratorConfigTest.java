package com.krickert.yappy.modules.tikaparser;

import com.krickert.search.model.util.TestDataGenerationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the configuration behavior of LargeTikaDatasetGenerator.
 */
public class LargeTikaDatasetGeneratorConfigTest {

    @Test
    void testRegenerationDisabledByDefault() {
        // By default, regeneration should be disabled
        assertFalse(TestDataGenerationConfig.isRegenerationEnabled(),
            "Test data regeneration should be disabled by default");
    }

    @Test
    void testDeterministicModeEnabledByDefault() {
        // By default, deterministic mode should be enabled
        assertTrue(TestDataGenerationConfig.isDeterministicMode(),
            "Deterministic ID generation should be enabled by default");
    }

    @Test
    void testDefaultOutputDirectory() {
        // By default, output should go to temp directory
        assertEquals(System.getProperty("java.io.tmpdir"), 
            TestDataGenerationConfig.getOutputDirectory(),
            "Default output directory should be system temp directory");
    }
}