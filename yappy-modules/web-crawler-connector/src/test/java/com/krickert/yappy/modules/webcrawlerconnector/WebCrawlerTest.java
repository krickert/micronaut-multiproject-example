package com.krickert.yappy.modules.webcrawlerconnector;

import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.webcrawlerconnector.config.WebCrawlerConfig;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the WebCrawler class.
 */
class WebCrawlerTest {

    @Mock
    private ResourceResolver resourceResolver;

    private WebCrawlerConfig config;
    private WebCrawler webCrawler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Create a test configuration
        config = WebCrawlerConfig.defaults()
                .userAgent("Test User Agent")
                .maxDepth(0)
                .maxPages(1)
                .stayWithinDomain(true)
                .followRedirects(true)
                .timeoutSeconds(10)
                .headless(true)
                .extractText(true)
                .extractHtml(true)
                .extractTitle(true)
                .kafkaTopic("test-topic")
                .logPrefix("[TEST] ")
                .build();
        
        webCrawler = new WebCrawler(config, resourceResolver);
    }

    @Test
    void testCreatePipeDoc() {
        // Create a test WebCrawlReply
        WebCrawlReply reply = new WebCrawlReply.Builder()
                .url("https://example.com")
                .title("Example Domain")
                .body("This domain is for use in illustrative examples in documents.")
                .html("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>")
                .build();
        
        // Create a PipeDoc from the reply
        PipeDoc pipeDoc = webCrawler.createPipeDoc(reply);
        
        // Verify the PipeDoc
        assertNotNull(pipeDoc);
        assertEquals("Example Domain", pipeDoc.getTitle());
        assertEquals("This domain is for use in illustrative examples in documents.", pipeDoc.getBody());
        assertEquals("https://example.com", pipeDoc.getSourceUri());
        assertEquals("web_page", pipeDoc.getDocumentType());
        assertNotNull(pipeDoc.getProcessedDate());
        
        // Verify the blob
        assertNotNull(pipeDoc.getBlob());
        assertEquals("text/html", pipeDoc.getBlob().getMimeType());
        assertEquals("https://example.com", pipeDoc.getBlob().getMetadataMap().get("url"));
    }

    /**
     * This test is disabled by default because it requires a real browser.
     * To run this test, set the system property "enable.browser.tests" to "true".
     */
    @Test
    @EnabledIfSystemProperty(named = "enable.browser.tests", matches = "true")
    void testCrawl() {
        // Crawl a simple URL
        Map<String, WebCrawlReply> results = webCrawler.crawl("https://example.com");
        
        // Verify the results
        assertNotNull(results);
        assertFalse(results.isEmpty());
        
        WebCrawlReply reply = results.get("https://example.com");
        assertNotNull(reply);
        assertEquals("Example Domain", reply.title());
        assertTrue(reply.body().contains("This domain is for use in illustrative examples"));
        assertNotNull(reply.html());
    }
}