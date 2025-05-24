package com.krickert.yappy.modules.webcrawlerconnector.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WebCrawlerConfig class.
 */
class WebCrawlerConfigTest {

    @Test
    void testDefaults() {
        // Get the default configuration
        WebCrawlerConfig config = WebCrawlerConfig.defaults().build();
        
        // Verify the default values
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36", config.userAgent());
        assertEquals(0, config.maxDepth());
        assertEquals(1, config.maxPages());
        assertTrue(config.stayWithinDomain());
        assertTrue(config.followRedirects());
        assertEquals(30, config.timeoutSeconds());
        assertTrue(config.headless());
        assertTrue(config.extractText());
        assertTrue(config.extractHtml());
        assertTrue(config.extractTitle());
        assertEquals("", config.logPrefix());
    }

    @Test
    void testCustomValues() {
        // Create a custom configuration
        WebCrawlerConfig config = WebCrawlerConfig.builder()
                .userAgent("Custom User Agent")
                .maxDepth(2)
                .maxPages(10)
                .stayWithinDomain(false)
                .followRedirects(false)
                .timeoutSeconds(60)
                .headless(false)
                .chromeDriverPath("/path/to/chromedriver")
                .uBlockExtensionPath("/path/to/ublock.crx")
                .extractText(false)
                .extractHtml(false)
                .extractTitle(false)
                .kafkaTopic("custom-topic")
                .logPrefix("[CUSTOM] ")
                .build();
        
        // Verify the custom values
        assertEquals("Custom User Agent", config.userAgent());
        assertEquals(2, config.maxDepth());
        assertEquals(10, config.maxPages());
        assertFalse(config.stayWithinDomain());
        assertFalse(config.followRedirects());
        assertEquals(60, config.timeoutSeconds());
        assertFalse(config.headless());
        assertEquals("/path/to/chromedriver", config.chromeDriverPath());
        assertEquals("/path/to/ublock.crx", config.uBlockExtensionPath());
        assertFalse(config.extractText());
        assertFalse(config.extractHtml());
        assertFalse(config.extractTitle());
        assertEquals("custom-topic", config.kafkaTopic());
        assertEquals("[CUSTOM] ", config.logPrefix());
    }
}