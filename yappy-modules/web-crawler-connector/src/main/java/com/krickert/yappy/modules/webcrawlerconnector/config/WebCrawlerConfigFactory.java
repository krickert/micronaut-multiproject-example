package com.krickert.yappy.modules.webcrawlerconnector.config;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating WebCrawlerConfig instances.
 * This factory creates a WebCrawlerConfig instance from the application configuration.
 */
@Factory
public class WebCrawlerConfigFactory {

    private static final Logger LOG = LoggerFactory.getLogger(WebCrawlerConfigFactory.class);

    /**
     * Creates a WebCrawlerConfig instance from the application configuration.
     *
     * @param userAgent the user agent string
     * @param maxDepth the maximum crawl depth
     * @param maxPages the maximum number of pages to crawl
     * @param stayWithinDomain whether to stay within the same domain
     * @param followRedirects whether to follow redirects
     * @param timeoutSeconds the timeout in seconds
     * @param headless whether to run Chrome in headless mode
     * @param chromeDriverPath the path to the Chrome driver executable
     * @param uBlockExtensionPath the path to the uBlock extension file
     * @param extractText whether to extract text from the page
     * @param extractHtml whether to extract HTML from the page
     * @param extractTitle whether to extract the title from the page
     * @param kafkaTopic the Kafka topic to send crawl results to
     * @param logPrefix the prefix for log messages
     * @return a WebCrawlerConfig instance
     */
    @Singleton
    public WebCrawlerConfig webCrawlerConfig(
            @Property(name = "web.crawler.config.user-agent") String userAgent,
            @Property(name = "web.crawler.config.max-depth", defaultValue = "0") int maxDepth,
            @Property(name = "web.crawler.config.max-pages", defaultValue = "1") int maxPages,
            @Property(name = "web.crawler.config.stay-within-domain", defaultValue = "true") boolean stayWithinDomain,
            @Property(name = "web.crawler.config.follow-redirects", defaultValue = "true") boolean followRedirects,
            @Property(name = "web.crawler.config.timeout-seconds", defaultValue = "30") int timeoutSeconds,
            @Property(name = "web.crawler.config.headless", defaultValue = "true") boolean headless,
            @Property(name = "web.crawler.config.chrome-driver-path") String chromeDriverPath,
            @Property(name = "web.crawler.config.ublock-extension-path") String uBlockExtensionPath,
            @Property(name = "web.crawler.config.extract-text", defaultValue = "true") boolean extractText,
            @Property(name = "web.crawler.config.extract-html", defaultValue = "true") boolean extractHtml,
            @Property(name = "web.crawler.config.extract-title", defaultValue = "true") boolean extractTitle,
            @Property(name = "web.crawler.config.kafka-topic") String kafkaTopic,
            @Property(name = "web.crawler.config.log-prefix", defaultValue = "") String logPrefix) {
        
        WebCrawlerConfig config = WebCrawlerConfig.builder()
                .userAgent(userAgent)
                .maxDepth(maxDepth)
                .maxPages(maxPages)
                .stayWithinDomain(stayWithinDomain)
                .followRedirects(followRedirects)
                .timeoutSeconds(timeoutSeconds)
                .headless(headless)
                .chromeDriverPath(chromeDriverPath)
                .uBlockExtensionPath(uBlockExtensionPath)
                .extractText(extractText)
                .extractHtml(extractHtml)
                .extractTitle(extractTitle)
                .kafkaTopic(kafkaTopic)
                .logPrefix(logPrefix)
                .build();
        
        LOG.info("Created WebCrawlerConfig: {}", config);
        
        return config;
    }
}