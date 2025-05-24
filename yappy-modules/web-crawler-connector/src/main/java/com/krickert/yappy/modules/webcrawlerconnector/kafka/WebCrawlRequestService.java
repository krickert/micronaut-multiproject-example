package com.krickert.yappy.modules.webcrawlerconnector.kafka;

import com.krickert.search.engine.web_crawl_request;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Service for sending web crawl requests to Kafka.
 * This service provides methods to request crawling of web pages.
 */
@Singleton
@Requires(property = "web.crawler.kafka.enabled", value = "true", defaultValue = "true")
public class WebCrawlRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(WebCrawlRequestService.class);

    private final WebCrawlRequestProducer producer;
    private final String inputTopic;

    /**
     * Constructor for WebCrawlRequestService.
     *
     * @param producer the Kafka producer for web crawl requests
     * @param inputTopic the Kafka input topic
     */
    public WebCrawlRequestService(WebCrawlRequestProducer producer, 
                                @Value("${web.crawler.kafka.input-topic:web-crawl-requests}") 
                                String inputTopic) {
        this.producer = producer;
        this.inputTopic = inputTopic;
    }

    /**
     * Requests crawling of a web page.
     *
     * @param url the URL to crawl
     */
    public void requestCrawl(String url) {
        LOG.info("Requesting crawl for URL: {}", url);

        // Create the web crawl request
        web_crawl_request request = web_crawl_request.newBuilder()
                .setUrl(url)
                .setMaxDepth(0)
                .setMaxPages(1)
                .setStayWithinDomain(true)
                .setFollowRedirects(true)
                .setDateCreated(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .build();

        // Generate a UUID for the Kafka message key
        UUID messageKey = UUID.randomUUID();

        // Send the request to Kafka
        producer.sendRequest(inputTopic, messageKey, request);

        LOG.info("Crawl request sent for URL: {}", url);
    }

    /**
     * Requests crawling of a web page with custom options.
     *
     * @param url the URL to crawl
     * @param maxDepth the maximum crawl depth
     * @param maxPages the maximum number of pages to crawl
     * @param stayWithinDomain whether to stay within the same domain
     * @param followRedirects whether to follow redirects
     * @param requestor optional requestor information
     */
    public void requestCustomCrawl(String url, int maxDepth, int maxPages, 
                                 boolean stayWithinDomain, boolean followRedirects,
                                 String requestor) {
        LOG.info("Requesting custom crawl for URL: {}, maxDepth: {}, maxPages: {}, stayWithinDomain: {}, followRedirects: {}, requestor: {}", 
                url, maxDepth, maxPages, stayWithinDomain, followRedirects, requestor != null ? requestor : "none");

        // Create the web crawl request builder
        web_crawl_request.Builder requestBuilder = web_crawl_request.newBuilder()
                .setUrl(url)
                .setMaxDepth(maxDepth)
                .setMaxPages(maxPages)
                .setStayWithinDomain(stayWithinDomain)
                .setFollowRedirects(followRedirects)
                .setDateCreated(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        // Add requestor if provided
        if (requestor != null && !requestor.isEmpty()) {
            requestBuilder.setRequestor(requestor);
        }

        // Build the request
        web_crawl_request request = requestBuilder.build();

        // Generate a UUID for the Kafka message key
        UUID messageKey = UUID.randomUUID();

        // Send the request to Kafka
        producer.sendRequest(inputTopic, messageKey, request);

        LOG.info("Custom crawl request sent for URL: {}", url);
    }

    /**
     * Kafka client for producing web crawl requests.
     */
    @KafkaClient(id = "web-crawl-request-producer")
    @Requires(property = "web.crawler.kafka.enabled", value = "true", defaultValue = "true")
    public interface WebCrawlRequestProducer {

        /**
         * Sends a web crawl request to the input topic.
         *
         * @param topic the Kafka topic
         * @param key the Kafka message key
         * @param request the web crawl request
         */
        void sendRequest(@Topic String topic, @KafkaKey UUID key, web_crawl_request request);
    }
}