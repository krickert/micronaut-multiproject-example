package com.krickert.yappy.wikicrawler.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for the WikiCrawler connector.
 * Each step in the pipeline (download -> file processing -> article processing) can be configured
 * to use either Kafka or internal processing.
 */
@SuppressWarnings({"LombokGetterMayBeUsed", "LombokSetterMayBeUsed"})
@ConfigurationProperties("wikicrawler")
public class WikiCrawlerConfig {

    // Download step configuration
    private boolean kafkaProduceDownloadRequests = false; // Default to false
    private String downloadRequestTopic = "wiki-download-requests";

    // File processing step configuration
    private boolean kafkaProduceDownloadedFiles = false; // Default to false
    private String downloadedFileTopic = "wiki-downloaded-files";

    // Article processing step configuration
    private boolean kafkaProduceArticles = false; // Default to false
    private String articleOutputTopic = "wiki-articles";

    // Getters and setters for download step configuration
    public boolean isKafkaProduceDownloadRequests() {
        return kafkaProduceDownloadRequests;
    }

    public void setKafkaProduceDownloadRequests(boolean kafkaProduceDownloadRequests) {
        this.kafkaProduceDownloadRequests = kafkaProduceDownloadRequests;
    }

    public String getDownloadRequestTopic() {
        return downloadRequestTopic;
    }

    public void setDownloadRequestTopic(String downloadRequestTopic) {
        this.downloadRequestTopic = downloadRequestTopic;
    }

    // Getters and setters for file processing step configuration
    public boolean isKafkaProduceDownloadedFiles() {
        return kafkaProduceDownloadedFiles;
    }

    public void setKafkaProduceDownloadedFiles(boolean kafkaProduceDownloadedFiles) {
        this.kafkaProduceDownloadedFiles = kafkaProduceDownloadedFiles;
    }

    public String getDownloadedFileTopic() {
        return downloadedFileTopic;
    }

    public void setDownloadedFileTopic(String downloadedFileTopic) {
        this.downloadedFileTopic = downloadedFileTopic;
    }

    // Getters and setters for article processing step configuration
    public boolean isKafkaProduceArticles() {
        return kafkaProduceArticles;
    }

    public void setKafkaProduceArticles(boolean kafkaProduceArticles) {
        this.kafkaProduceArticles = kafkaProduceArticles;
    }

    public String getArticleOutputTopic() {
        return articleOutputTopic;
    }

    public void setArticleOutputTopic(String articleOutputTopic) {
        this.articleOutputTopic = articleOutputTopic;
    }
}
