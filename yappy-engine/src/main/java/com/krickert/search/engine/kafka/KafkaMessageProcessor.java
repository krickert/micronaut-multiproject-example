package com.krickert.search.engine.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Interface for processing Kafka messages received by the KafkaConsumerService.
 * Implementations should handle deserialization, routing to pipeline steps,
 * and error handling.
 */
public interface KafkaMessageProcessor {
    
    /**
     * Process a batch of Kafka records.
     * 
     * @param records List of consumer records to process
     * @param topic Topic the records came from
     * @param groupId Consumer group ID
     * @return Mono indicating completion or error
     */
    Mono<Void> processRecords(List<ConsumerRecord<String, byte[]>> records, 
                             String topic, 
                             String groupId);
    
    /**
     * Handle a processing error.
     * 
     * @param error The error that occurred
     * @param record The record that caused the error (if available)
     * @param topic Topic the record came from
     * @param groupId Consumer group ID
     * @return Whether to continue processing or pause the consumer
     */
    boolean handleError(Throwable error, 
                       ConsumerRecord<String, byte[]> record,
                       String topic,
                       String groupId);
    
    /**
     * Called when a consumer is starting for a topic/group.
     * 
     * @param topic Topic being consumed
     * @param groupId Consumer group ID
     * @param partitions Assigned partitions
     */
    void onConsumerStart(String topic, String groupId, List<Integer> partitions);
    
    /**
     * Called when a consumer is stopping for a topic/group.
     * 
     * @param topic Topic being consumed
     * @param groupId Consumer group ID
     */
    void onConsumerStop(String topic, String groupId);
}