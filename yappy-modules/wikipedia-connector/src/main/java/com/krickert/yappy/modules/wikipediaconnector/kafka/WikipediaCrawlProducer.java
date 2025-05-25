package com.krickert.yappy.modules.wikipediaconnector.kafka;

import com.krickert.search.model.PipeDoc;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;

import java.util.UUID;

/**
 * Kafka producer for Wikipedia crawl results.
 * This producer sends PipeDoc objects to a Kafka topic after processing Wikipedia articles.
 */
@KafkaClient(id = "wikipedia-crawl-producer")
@Requires(property = "wikipedia.connector.kafka.enabled", value = "true", defaultValue = "true")
public interface WikipediaCrawlProducer {

    /**
     * Sends a PipeDoc to the output topic.
     *
     * @param key the message key
     * @param document the PipeDoc to send
     */
    void sendDocument(@Topic("${wikipedia.connector.kafka.output-topic:wikipedia-crawl-results}") @KafkaKey UUID key, PipeDoc document);
}