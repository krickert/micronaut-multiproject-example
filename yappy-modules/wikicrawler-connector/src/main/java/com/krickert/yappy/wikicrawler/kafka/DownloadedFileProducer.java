package com.krickert.yappy.wikicrawler.kafka;

import com.krickert.search.model.wiki.DownloadedFile;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

/**
 * Kafka producer for sending downloaded files to a Kafka topic.
 */
@KafkaClient(id = "wikicrawler-downloaded-file-producer")
public interface DownloadedFileProducer {

    /**
     * Sends a downloaded file to the specified topic.
     * The file name will be used as the Kafka message key.
     *
     * @param fileName The name of the downloaded file, used as the Kafka key.
     * @param downloadedFile The downloaded file to send.
     */
    @Topic("${wikicrawler.downloaded-file-topic:wiki-downloaded-files}")
    void sendDownloadedFile(@KafkaKey String fileName, DownloadedFile downloadedFile);
}