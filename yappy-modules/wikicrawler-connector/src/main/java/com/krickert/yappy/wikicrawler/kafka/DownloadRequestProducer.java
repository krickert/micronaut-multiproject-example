package com.krickert.yappy.wikicrawler.kafka;

import com.krickert.search.model.wiki.DownloadFileRequest;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

import java.util.UUID;

/**
 * Kafka producer for sending download requests to a Kafka topic.
 */
@KafkaClient(id = "wikicrawler-download-request-producer")
public interface DownloadRequestProducer {

    /**
     * Sends a download request to the specified topic.
     * A random UUID will be used as the Kafka message key.
     *
     * @param key A unique identifier for the request, used as the Kafka key.
     * @param downloadRequest The download request to send.
     */
    @Topic("${wikicrawler.download-request-topic:wiki-download-requests}")
    void sendDownloadRequest(@KafkaKey UUID key, DownloadFileRequest downloadRequest);
}