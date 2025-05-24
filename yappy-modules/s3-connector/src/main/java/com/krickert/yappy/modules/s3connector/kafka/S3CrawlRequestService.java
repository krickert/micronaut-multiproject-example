package com.krickert.yappy.modules.s3connector.kafka;

import com.krickert.search.engine.s3_crawl_request;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Service for sending S3 crawl requests to Kafka.
 * This service provides methods to request crawling of S3 objects.
 */
@Singleton
@Requires(property = "s3.connector.kafka.enabled", value = "true", defaultValue = "true")
public class S3CrawlRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(S3CrawlRequestService.class);

    private final S3CrawlRequestProducer producer;

    private final String inputTopic;

    /**
     * Constructor for S3CrawlRequestService.
     *
     * @param producer the Kafka producer for S3 crawl requests
     * @param inputTopic the Kafka input topic
     */
    public S3CrawlRequestService(S3CrawlRequestProducer producer, 
                                @io.micronaut.context.annotation.Value("${s3.connector.kafka.input-topic:s3-crawl-requests}") 
                                String inputTopic) {
        this.producer = producer;
        this.inputTopic = inputTopic;
    }

    /**
     * Requests crawling of an S3 object.
     *
     * @param bucket the S3 bucket name
     * @param key the S3 object key
     */
    public void requestCrawl(String bucket, String key) {
        LOG.info("Requesting crawl for S3 object - bucket: {}, key: {}", bucket, key);

        // Create the S3 crawl request
        s3_crawl_request request = s3_crawl_request.newBuilder()
                .setBucket(bucket)
                .setKey(key)
                .build();

        // Generate a UUID for the Kafka message key
        UUID messageKey = UUID.randomUUID();

        // Send the request to Kafka
        producer.sendRequest(inputTopic, messageKey, request);

        LOG.info("Crawl request sent for S3 object - bucket: {}, key: {}", bucket, key);
    }

    /**
     * Requests a partial crawl of S3 objects within a date range.
     * If a key is provided, it will be used as a prefix filter.
     *
     * @param bucket the S3 bucket name
     * @param key the S3 object key prefix (optional, can be null or empty)
     * @param startDate the start date in ISO-8601 format (e.g., "2023-01-01T00:00:00Z"), can be null
     * @param endDate the end date in ISO-8601 format (e.g., "2023-01-31T23:59:59Z"), can be null
     */
    public void requestPartialCrawl(String bucket, String key, String startDate, String endDate) {
        LOG.info("Requesting partial crawl for S3 bucket: {}, prefix: {}, startDate: {}, endDate: {}", 
                bucket, key, startDate != null ? startDate : "none", endDate != null ? endDate : "none");

        // Create the S3 crawl request builder
        s3_crawl_request.Builder requestBuilder = s3_crawl_request.newBuilder()
                .setBucket(bucket);

        // Add key as prefix if provided
        if (key != null && !key.isEmpty()) {
            requestBuilder.setKey(key);
        }

        // Add date range parameters if provided
        if (startDate != null && !startDate.isEmpty()) {
            requestBuilder.setStartDate(startDate);
        }

        if (endDate != null && !endDate.isEmpty()) {
            requestBuilder.setEndDate(endDate);
        }

        // Build the request
        s3_crawl_request request = requestBuilder.build();

        // Generate a UUID for the Kafka message key
        UUID messageKey = UUID.randomUUID();

        // Send the request to Kafka
        producer.sendRequest(inputTopic, messageKey, request);

        LOG.info("Partial crawl request sent for S3 bucket: {}", bucket);
    }

    /**
     * Kafka client for producing S3 crawl requests.
     */
    @KafkaClient(id = "s3-crawl-request-producer")
    @Requires(property = "s3.connector.kafka.enabled", value = "true", defaultValue = "true")
    public interface S3CrawlRequestProducer {

        /**
         * Sends an S3 crawl request to the input topic.
         *
         * @param topic the Kafka topic
         * @param key the Kafka message key
         * @param request the S3 crawl request
         */
        void sendRequest(@Topic String topic, @KafkaKey UUID key, s3_crawl_request request);
    }
}
