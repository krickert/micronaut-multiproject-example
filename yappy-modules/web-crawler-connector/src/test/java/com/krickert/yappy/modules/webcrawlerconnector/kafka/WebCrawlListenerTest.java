package com.krickert.yappy.modules.webcrawlerconnector.kafka;

import com.krickert.search.engine.web_crawl_request;
import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.webcrawlerconnector.WebCrawlReply;
import com.krickert.yappy.modules.webcrawlerconnector.WebCrawler;
import com.krickert.yappy.modules.webcrawlerconnector.config.WebCrawlerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for the WebCrawlListener class.
 */
class WebCrawlListenerTest {

    @Mock
    private WebCrawler webCrawler;

    @Mock
    private WebCrawlProducer producer;

    private WebCrawlerConfig config;
    private WebCrawlListener listener;

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
        
        listener = new WebCrawlListener(webCrawler, producer, config);
    }

    @Test
    void testReceive() {
        // Create a test web crawl request
        web_crawl_request request = web_crawl_request.newBuilder()
                .setUrl("https://example.com")
                .setMaxDepth(1)
                .setMaxPages(2)
                .setStayWithinDomain(true)
                .setFollowRedirects(true)
                .build();
        
        // Create a test WebCrawlReply
        WebCrawlReply reply = new WebCrawlReply.Builder()
                .url("https://example.com")
                .title("Example Domain")
                .body("This domain is for use in illustrative examples in documents.")
                .html("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>")
                .build();
        
        // Create a test PipeDoc
        PipeDoc pipeDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("Example Domain")
                .setBody("This domain is for use in illustrative examples in documents.")
                .setSourceUri("https://example.com")
                .setDocumentType("web_page")
                .build();
        
        // Set up the mock behavior
        Map<String, WebCrawlReply> results = new HashMap<>();
        results.put("https://example.com", reply);
        when(webCrawler.crawl("https://example.com")).thenReturn(results);
        when(webCrawler.createPipeDoc(reply)).thenReturn(pipeDoc);
        
        // Call the method under test
        listener.receive(request);
        
        // Verify the interactions
        verify(webCrawler).crawl("https://example.com");
        verify(webCrawler).createPipeDoc(reply);
        verify(producer).sendDocument(any(UUID.class), eq(pipeDoc));
    }

    @Test
    void testReceiveWithError() {
        // Create a test web crawl request
        web_crawl_request request = web_crawl_request.newBuilder()
                .setUrl("https://example.com")
                .setMaxDepth(1)
                .setMaxPages(2)
                .setStayWithinDomain(true)
                .setFollowRedirects(true)
                .build();
        
        // Set up the mock behavior to throw an exception
        when(webCrawler.crawl("https://example.com")).thenThrow(new RuntimeException("Test exception"));
        
        // Call the method under test
        listener.receive(request);
        
        // Verify the interactions
        verify(webCrawler).crawl("https://example.com");
        verify(webCrawler, never()).createPipeDoc(any());
        verify(producer, never()).sendDocument(any(), any());
    }
}