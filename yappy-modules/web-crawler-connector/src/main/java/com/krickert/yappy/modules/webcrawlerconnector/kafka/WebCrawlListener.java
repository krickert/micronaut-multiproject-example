package com.krickert.yappy.modules.webcrawlerconnector.kafka;

import com.krickert.search.engine.web_crawl_request;
import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.webcrawlerconnector.WebCrawlReply;
import com.krickert.yappy.modules.webcrawlerconnector.WebCrawler;
import com.krickert.yappy.modules.webcrawlerconnector.config.WebCrawlerConfig;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka listener for web crawl requests.
 * This listener processes web crawl requests from Kafka and sends the results back to Kafka.
 */
@KafkaListener(
        groupId = "${web.crawler.kafka.group-id:web-crawler-group}",
        offsetReset = OffsetReset.EARLIEST,
        clientId = "${web.crawler.kafka.client-id:web-crawler-client}"
)
@Requires(property = "web.crawler.kafka.enabled", value = "true", defaultValue = "true")
public class WebCrawlListener {

    private static final Logger LOG = LoggerFactory.getLogger(WebCrawlListener.class);

    private final WebCrawler webCrawler;
    private final WebCrawlProducer producer;
    private final WebCrawlerConfig config;

    /**
     * Constructs a new WebCrawlListener.
     *
     * @param webCrawler the web crawler
     * @param producer the Kafka producer
     * @param config the web crawler configuration
     */
    @Inject
    public WebCrawlListener(WebCrawler webCrawler, WebCrawlProducer producer, WebCrawlerConfig config) {
        this.webCrawler = webCrawler;
        this.producer = producer;
        this.config = config;
    }

    /**
     * Processes a web crawl request.
     *
     * @param request the web crawl request
     */
    @Topic("${web.crawler.kafka.input-topic:web-crawl-requests}")
    public void receive(web_crawl_request request) {
        LOG.info("{}Received web crawl request for URL: {}", config.logPrefix(), request.getUrl());

        try {
            // Override configuration with request parameters if needed
            WebCrawlerConfig requestConfig = WebCrawlerConfig.builder()
                    .userAgent(request.hasUserAgent() ? request.getUserAgent() : config.userAgent())
                    .maxDepth(request.getMaxDepth())
                    .maxPages(request.getMaxPages())
                    .stayWithinDomain(request.getStayWithinDomain())
                    .followRedirects(request.getFollowRedirects())
                    .timeoutSeconds(request.hasTimeoutSeconds() ? request.getTimeoutSeconds() : config.timeoutSeconds())
                    .headless(config.headless())
                    .chromeDriverPath(config.chromeDriverPath())
                    .uBlockExtensionPath(config.uBlockExtensionPath())
                    .extractText(config.extractText())
                    .extractHtml(config.extractHtml())
                    .extractTitle(config.extractTitle())
                    .kafkaTopic(config.kafkaTopic())
                    .logPrefix(config.logPrefix())
                    .build();

            // Crawl the URL
            Map<String, WebCrawlReply> results = webCrawler.crawl(request.getUrl());

            // Process and send each result
            results.forEach((url, reply) -> {
                LOG.info("{}Processing crawl result for URL: {}", config.logPrefix(), url);
                
                // Create a PipeDoc from the reply
                PipeDoc pipeDoc = webCrawler.createPipeDoc(reply);
                
                // Send the PipeDoc to Kafka
                UUID messageKey = UUID.randomUUID();
                producer.sendDocument(messageKey, pipeDoc);
                
                LOG.info("{}Sent crawl result for URL: {}", config.logPrefix(), url);
            });

            LOG.info("{}Completed processing web crawl request for URL: {}", config.logPrefix(), request.getUrl());
        } catch (Exception e) {
            LOG.error("{}Error processing web crawl request for URL: {}", config.logPrefix(), request.getUrl(), e);
        }
    }
}