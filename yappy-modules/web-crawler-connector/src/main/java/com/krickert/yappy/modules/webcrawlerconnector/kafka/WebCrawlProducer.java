package com.krickert.yappy.modules.webcrawlerconnector.kafka;

import com.krickert.search.model.PipeDoc;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;

import java.util.UUID;

/**
 * Kafka producer for web crawl results.
 * This producer sends PipeDoc objects to a Kafka topic after processing web pages.
 */
@KafkaClient(id = "web-crawl-producer")
@Requires(property = "web.crawler.kafka.enabled", value = "true", defaultValue = "true")
public interface WebCrawlProducer {

    /**
     * Sends a PipeDoc to the output topic.
     *
     * @param key the Kafka message key
     * @param document the PipeDoc to send
     */
    void sendDocument(@Topic("${web.crawler.kafka.output-topic:web-crawl-results}") @KafkaKey UUID key, PipeDoc document);
}