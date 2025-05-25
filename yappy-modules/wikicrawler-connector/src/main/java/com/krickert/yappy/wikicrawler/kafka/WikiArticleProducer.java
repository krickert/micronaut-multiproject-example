package com.krickert.yappy.wikicrawler.kafka;

import com.krickert.search.model.wiki.WikiArticle;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

@KafkaClient(id = "wikicrawler-article-producer") // Unique Kafka client ID
public interface WikiArticleProducer {

    /**
     * Sends a WikiArticle to the specified topic.
     * The article's ID will be used as the Kafka message key.
     *
     * @param articleId The ID of the wiki article, used as the Kafka key.
     * @param wikiArticle The WikiArticle message to send.
     */
    @Topic("${kafka.topic.wiki.article:wiki-articles}") // Topic name from config, defaults to 'wiki-articles'
    void sendWikiArticle(@KafkaKey String articleId, WikiArticle wikiArticle);

}
