package com.krickert.yappy.modules.webcrawlerconnector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WebCrawlReply class.
 */
class WebCrawlReplyTest {

    @Test
    void testBuilder() {
        // Create a WebCrawlReply using the builder
        WebCrawlReply reply = new WebCrawlReply.Builder()
                .url("https://example.com")
                .title("Example Domain")
                .body("This domain is for use in illustrative examples in documents.")
                .html("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>")
                .build();
        
        // Verify the values
        assertEquals("https://example.com", reply.url());
        assertEquals("Example Domain", reply.title());
        assertEquals("This domain is for use in illustrative examples in documents.", reply.body());
        assertEquals("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>", reply.html());
    }

    @Test
    void testRecord() {
        // Create a WebCrawlReply directly
        WebCrawlReply reply = new WebCrawlReply(
                "https://example.com",
                "Example Domain",
                "This domain is for use in illustrative examples in documents.",
                "<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>"
        );
        
        // Verify the values
        assertEquals("https://example.com", reply.url());
        assertEquals("Example Domain", reply.title());
        assertEquals("This domain is for use in illustrative examples in documents.", reply.body());
        assertEquals("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>", reply.html());
    }

    @Test
    void testEquality() {
        // Create two identical WebCrawlReply objects
        WebCrawlReply reply1 = new WebCrawlReply.Builder()
                .url("https://example.com")
                .title("Example Domain")
                .body("This domain is for use in illustrative examples in documents.")
                .html("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>")
                .build();
        
        WebCrawlReply reply2 = new WebCrawlReply.Builder()
                .url("https://example.com")
                .title("Example Domain")
                .body("This domain is for use in illustrative examples in documents.")
                .html("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>")
                .build();
        
        // Verify equality
        assertEquals(reply1, reply2);
        assertEquals(reply1.hashCode(), reply2.hashCode());
    }

    @Test
    void testInequality() {
        // Create two different WebCrawlReply objects
        WebCrawlReply reply1 = new WebCrawlReply.Builder()
                .url("https://example.com")
                .title("Example Domain")
                .body("This domain is for use in illustrative examples in documents.")
                .html("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>")
                .build();
        
        WebCrawlReply reply2 = new WebCrawlReply.Builder()
                .url("https://example.org")
                .title("Example Domain")
                .body("This domain is for use in illustrative examples in documents.")
                .html("<html><head><title>Example Domain</title></head><body><p>This domain is for use in illustrative examples in documents.</p></body></html>")
                .build();
        
        // Verify inequality
        assertNotEquals(reply1, reply2);
        assertNotEquals(reply1.hashCode(), reply2.hashCode());
    }
}