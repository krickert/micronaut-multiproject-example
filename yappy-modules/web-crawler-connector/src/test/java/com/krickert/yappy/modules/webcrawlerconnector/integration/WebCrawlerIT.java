package com.krickert.yappy.modules.webcrawlerconnector.integration;

import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.webcrawlerconnector.WebCrawlReply;
import com.krickert.yappy.modules.webcrawlerconnector.WebCrawler;
import com.krickert.yappy.modules.webcrawlerconnector.config.WebCrawlerConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the WebCrawler class.
 * This test ensures that the connector launches the Chrome browser in the background,
 * gets the data from http://rokkon.com, and then kills the browser.
 */
@MicronautTest(environments = "test")
public class WebCrawlerIT {

    private static final Logger LOG = LoggerFactory.getLogger(WebCrawlerIT.class);
    private static final String TEST_URL = "http://rokkon.com";

    @Inject
    private ApplicationContext applicationContext;

    private WebCrawler webCrawler;

    @BeforeEach
    void setUp() {
        // Create a test configuration with headless mode enabled
        WebCrawlerConfig config = WebCrawlerConfig.defaults()
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .maxDepth(0)
                .maxPages(1)
                .stayWithinDomain(true)
                .followRedirects(true)
                .timeoutSeconds(30)
                .headless(true) // Ensure headless mode is enabled for CI environments
                .extractText(true)
                .extractHtml(true)
                .extractTitle(true)
                .kafkaTopic("test-topic")
                .logPrefix("[TEST] ")
                .build();

        // Get the resource resolver from the application context
        webCrawler = new WebCrawler(config, applicationContext.getBean(io.micronaut.core.io.ResourceResolver.class));

        LOG.info("Setting up WebCrawler integration test for URL: {}", TEST_URL);
    }

    @AfterEach
    void tearDown() {
        LOG.info("Tearing down WebCrawler integration test");
        // The WebCrawler.crawl method should automatically close the browser
        // No additional cleanup needed here
    }

    @Test
    void testCrawlRokkonWebsite() {
        LOG.info("Starting crawl of {}", TEST_URL);

        // Crawl the Rokkon website
        Map<String, WebCrawlReply> results = webCrawler.crawl(TEST_URL);

        // Verify that we got results
        assertNotNull(results, "Crawl results should not be null");
        assertFalse(results.isEmpty(), "Crawl results should not be empty");

        // Verify that we got a result for the Rokkon website
        WebCrawlReply reply = results.get(TEST_URL);
        assertNotNull(reply, "Should have a result for " + TEST_URL);

        // Verify that the result has content
        assertNotNull(reply.title(), "Title should not be null");
        assertFalse(reply.title().isEmpty(), "Title should not be empty");

        assertNotNull(reply.body(), "Body should not be null");
        assertFalse(reply.body().isEmpty(), "Body should not be empty");

        assertNotNull(reply.html(), "HTML should not be null");
        assertFalse(reply.html().isEmpty(), "HTML should not be empty");

        LOG.info("Successfully crawled {} with title: {}", TEST_URL, reply.title());

        // Create a PipeDoc from the reply
        PipeDoc pipeDoc = webCrawler.createPipeDoc(reply);

        // Verify the PipeDoc
        assertNotNull(pipeDoc, "PipeDoc should not be null");
        assertEquals(TEST_URL, pipeDoc.getSourceUri(), "Source URI should match the test URL");
        assertEquals(reply.title(), pipeDoc.getTitle(), "Title should match");
        assertEquals(reply.body(), pipeDoc.getBody(), "Body should match");

        // Verify the blob
        assertNotNull(pipeDoc.getBlob(), "Blob should not be null");
        assertEquals("text/html", pipeDoc.getBlob().getMimeType(), "MIME type should be text/html");
        assertEquals(TEST_URL, pipeDoc.getBlob().getMetadataMap().get("url"), "URL in metadata should match");

        LOG.info("Successfully created PipeDoc from crawl result");
    }
}
