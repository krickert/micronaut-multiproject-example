package com.krickert.yappy.wikicrawler.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("wikicrawler")
public class WikiCrawlerConfig {

    private boolean kafkaProduceArticles = false; // Default to false
    private String articleOutputTopic = "wiki-articles";

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
