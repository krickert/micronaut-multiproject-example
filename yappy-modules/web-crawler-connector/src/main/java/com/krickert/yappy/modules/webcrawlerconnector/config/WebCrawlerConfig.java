package com.krickert.yappy.modules.webcrawlerconnector.config;

import lombok.Builder;

/**
 * Configuration for the Web Crawler connector.
 * This class defines the configuration options for connecting to web pages
 * and processing their contents.
 */
@Builder
public record WebCrawlerConfig(
    // Web crawling settings
    String userAgent,
    int maxDepth,
    int maxPages,
    boolean stayWithinDomain,
    boolean followRedirects,
    int timeoutSeconds,
    
    // Chrome/Selenium settings
    boolean headless,
    String chromeDriverPath,
    String uBlockExtensionPath,
    
    // Processing options
    boolean extractText,
    boolean extractHtml,
    boolean extractTitle,
    
    // Kafka settings
    String kafkaTopic,
    
    // Logging options
    String logPrefix
) {
    /**
     * Returns a builder with default values.
     * @return a builder with default values
     */
    public static WebCrawlerConfigBuilder defaults() {
        return WebCrawlerConfig.builder()
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .maxDepth(0)
                .maxPages(1)
                .stayWithinDomain(true)
                .followRedirects(true)
                .timeoutSeconds(30)
                .headless(true)
                .extractText(true)
                .extractHtml(true)
                .extractTitle(true)
                .logPrefix("");
    }
}