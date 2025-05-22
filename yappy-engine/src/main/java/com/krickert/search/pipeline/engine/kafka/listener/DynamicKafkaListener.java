// File: yappy-engine/src/main/java/com/krickert/search/pipeline/engine/kafka/listener/DynamicKafkaListener.java
package com.krickert.search.pipeline.engine.kafka.listener;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import io.apicurio.registry.serde.config.SerdeConfig; // Keep for logging
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("LombokGetterMayBeUsed")
public class DynamicKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaListener.class);

    private final String listenerId;
    private final String topic;
    private final String groupId;
    private final Map<String, Object> consumerConfig; // This is now fully prepared by KafkaListenerManager
    private final String pipelineName;
    private final String stepName;
    private final PipeStreamEngine pipeStreamEngine;

    private KafkaConsumer<UUID, PipeStream> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private ExecutorService executorService;

    public DynamicKafkaListener(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> consumerConfig, // Expect this to be fully configured
            String pipelineName,
            String stepName,
            PipeStreamEngine pipeStreamEngine) {

        this.listenerId = Objects.requireNonNull(listenerId, "Listener ID cannot be null");
        this.topic = Objects.requireNonNull(topic, "Topic cannot be null");
        this.groupId = Objects.requireNonNull(groupId, "Group ID cannot be null");
        this.consumerConfig = new HashMap<>(Objects.requireNonNull(consumerConfig, "Consumer config cannot be null"));
        this.pipelineName = Objects.requireNonNull(pipelineName, "Pipeline name cannot be null");
        this.stepName = Objects.requireNonNull(stepName, "Step name cannot be null");
        this.pipeStreamEngine = Objects.requireNonNull(pipeStreamEngine, "PipeStreamEngine cannot be null");

        // Essential Kafka properties that are not schema-registry specific
        this.consumerConfig.putIfAbsent(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        this.consumerConfig.putIfAbsent(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.UUIDDeserializer");

        // VALUE_DESERIALIZER_CLASS_CONFIG and schema registry properties (like apicurio.registry.url)
        // are now expected to be pre-populated in the 'consumerConfig' map by KafkaListenerManager.

        // Optional: Add a check/warning if essential properties for the *expected* deserializer are missing
        String valueDeserializerClass = (String) this.consumerConfig.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        if (valueDeserializerClass == null || valueDeserializerClass.isBlank()) {
            log.error("Listener {}: CRITICAL - Consumer property '{}' is missing or blank in the provided config. Deserialization will fail.",
                    listenerId, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        } else {
            log.info("Listener {}: Using value deserializer: {}", listenerId, valueDeserializerClass);
            if ("io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer".equals(valueDeserializerClass)) {
                if (!this.consumerConfig.containsKey(SerdeConfig.REGISTRY_URL)) {
                    log.warn("Listener {}: Apicurio deserializer is configured, but registry URL ('{}') not found in consumerConfig. Deserialization might fail.",
                            listenerId, SerdeConfig.REGISTRY_URL);
                }
                if (!this.consumerConfig.containsKey(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS)) {
                    log.warn("Listener {}: Apicurio deserializer is configured, but specific value return class ('{}') not found in consumerConfig. Defaulting might occur or deserialization might fail.",
                            listenerId, SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS);
                }
            }
            // TODO: Add similar checks if valueDeserializerClass is for Glue
        }
        if (!this.consumerConfig.containsKey(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            log.error("Listener {}: CRITICAL - Consumer property '{}' is missing or blank. Consumer will fail to connect.",
                    listenerId, ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
        }


        log.debug("Listener {}: Final consumer config being passed to KafkaConsumer: {}", listenerId, this.consumerConfig);
        initialize();
    }

    // ... (rest of DynamicKafkaListener: initialize, pollLoop, processRecord, pause, resume, shutdown, getters - no changes needed here)
    private void initialize() {
        consumer = new KafkaConsumer<>(this.consumerConfig);
        consumer.subscribe(Collections.singletonList(topic));

        executorService = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                        .setNameFormat("kafka-listener-" + listenerId + "-%d") // More specific name
                        .setDaemon(true)
                        .build());

        running.set(true);
        executorService.submit(this::pollLoop);

        log.info("Initialized Kafka listener: {} for topic: {}, group: {}",
                listenerId, topic, groupId);
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                if (!paused.get()) {
                    ConsumerRecords<UUID, PipeStream> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        try {
                            processRecord(record);
                        } catch (Exception e) {
                            log.error("Error processing record from topic {}, partition {}, offset {}: {}",
                                    record.topic(), record.partition(), record.offset(), e.getMessage(), e);
                            // Consider how to handle individual record processing errors (e.g., DLQ)
                        }
                    }
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            log.warn("Kafka listener {} poll loop interrupted.", listenerId);
            Thread.currentThread().interrupt();
        } catch (Exception e) { // Catch other potential exceptions from poll() or consumer operations
            log.error("Error in Kafka consumer {} poll loop: {}", listenerId, e.getMessage(), e);
            // Consider more robust error handling, e.g., attempting to re-initialize the consumer
        } finally {
            try {
                if (consumer != null) {
                    consumer.close(Duration.ofSeconds(5)); // Close with a timeout
                }
            } catch (Exception e) {
                log.error("Error closing Kafka consumer for listener {}: {}", listenerId, e.getMessage(), e);
            }
            log.info("Kafka listener {} poll loop finished.", listenerId);
        }
    }

    private void processRecord(ConsumerRecord<UUID, PipeStream> record) {
        PipeStream pipeStream = record.value();
        if (pipeStream == null) {
            log.warn("Received null message from Kafka. Topic: {}, Partition: {}, Offset: {}. Skipping.",
                    record.topic(), record.partition(), record.offset());
            return;
        }

        log.debug("Listener {}: Received record from topic: {}, partition: {}, offset: {}",
                listenerId, record.topic(), record.partition(), record.offset());

        PipeStream updatedPipeStream = pipeStream.toBuilder()
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .build();

        pipeStreamEngine.processStream(updatedPipeStream);

        log.debug("Listener {}: Forwarded record to PipeStreamEngine. StreamId: {}", listenerId, updatedPipeStream.getStreamId());
    }

    public void pause() {
        if (paused.compareAndSet(false, true)) {
            if (consumer != null) {
                try {
                    Set<TopicPartition> partitions = consumer.assignment();
                    if (!partitions.isEmpty()) {
                        consumer.pause(partitions);
                        log.info("Paused Kafka listener: {}", listenerId);
                    } else {
                        log.warn("Kafka listener {} has no assigned partitions to pause.", listenerId);
                    }
                } catch (IllegalStateException e) {
                    log.warn("Kafka listener {} could not be paused, possibly not subscribed or consumer closed: {}", listenerId, e.getMessage());
                }
            }
        }
    }

    public void resume() {
        if (paused.compareAndSet(true, false)) {
            if (consumer != null) {
                try {
                    Set<TopicPartition> partitions = consumer.assignment();
                    if (!partitions.isEmpty()) {
                        consumer.resume(partitions);
                        log.info("Resumed Kafka listener: {}", listenerId);
                    } else {
                        log.warn("Kafka listener {} has no assigned partitions to resume.", listenerId);
                    }
                } catch (IllegalStateException e) {
                    log.warn("Kafka listener {} could not be resumed, possibly not subscribed or consumer closed: {}", listenerId, e.getMessage());
                }
            }
        }
    }

    public void shutdown() {
        if (running.compareAndSet(true, false)) { // Ensure shutdown logic runs only once
            log.info("Shutting down Kafka listener: {}", listenerId);
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                        log.warn("Executor service for listener {} did not terminate gracefully, forced shutdown.", listenerId);
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for executor service of listener {} to terminate.", listenerId);
                }
            }
            log.info("Kafka listener {} shutdown process initiated.", listenerId);
        }
    }

    public boolean isPaused() {
        return paused.get();
    }

    public String getListenerId() {
        return listenerId;
    }

    public String getTopic() {
        return topic;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getPipelineName() {
        return pipelineName;
    }

    public String getStepName() {
        return stepName;
    }
}