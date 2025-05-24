package com.krickert.yappy.modules.webcrawlerconnector.kafka;

import com.krickert.search.engine.web_crawl_request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Tests for the WebCrawlRequestService class.
 */
class WebCrawlRequestServiceTest {

    @Mock
    private WebCrawlRequestService.WebCrawlRequestProducer producer;

    @Captor
    private ArgumentCaptor<web_crawl_request> requestCaptor;

    private WebCrawlRequestService service;
    private final String inputTopic = "test-input-topic";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new WebCrawlRequestService(producer, inputTopic);
    }

    @Test
    void testRequestCrawl() {
        // Call the method under test
        service.requestCrawl("https://example.com");
        
        // Verify the producer was called with the correct topic
        verify(producer).sendRequest(eq(inputTopic), any(UUID.class), requestCaptor.capture());
        
        // Verify the request
        web_crawl_request request = requestCaptor.getValue();
        assertEquals("https://example.com", request.getUrl());
        assertEquals(0, request.getMaxDepth());
        assertEquals(1, request.getMaxPages());
        assertTrue(request.getStayWithinDomain());
        assertTrue(request.getFollowRedirects());
        assertTrue(request.hasDateCreated());
    }

    @Test
    void testRequestCustomCrawl() {
        // Call the method under test
        service.requestCustomCrawl(
                "https://example.com",
                2,
                10,
                false,
                true,
                "test-requestor"
        );
        
        // Verify the producer was called with the correct topic
        verify(producer).sendRequest(eq(inputTopic), any(UUID.class), requestCaptor.capture());
        
        // Verify the request
        web_crawl_request request = requestCaptor.getValue();
        assertEquals("https://example.com", request.getUrl());
        assertEquals(2, request.getMaxDepth());
        assertEquals(10, request.getMaxPages());
        assertFalse(request.getStayWithinDomain());
        assertTrue(request.getFollowRedirects());
        assertTrue(request.hasDateCreated());
        assertEquals("test-requestor", request.getRequestor());
    }
}