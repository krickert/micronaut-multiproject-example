package com.krickert.yappy.modules.s3connector.kafka;

import com.krickert.search.model.PipeDoc;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;

import java.util.UUID;

/**
 * Kafka producer for S3 crawl results.
 * This producer sends PipeDoc objects to a Kafka topic after processing S3 objects.
 */
@KafkaClient(id = "s3-crawl-producer")
@Requires(property = "s3.connector.kafka.enabled", value = "true", defaultValue = "true")
public interface S3CrawlProducer {

    /**
     * Sends a PipeDoc to the output topic.
     *
     * @param document the PipeDoc to send
     */
    void sendDocument(@Topic("${s3.connector.kafka.output-topic:s3-crawl-results}") @KafkaKey UUID key, PipeDoc document);
}
