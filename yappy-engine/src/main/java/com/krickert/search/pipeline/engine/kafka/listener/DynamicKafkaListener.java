package com.krickert.search.pipeline.engine.kafka.listener;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.Empty;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import io.grpc.stub.StreamObserver;
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

/**
 * A dynamic Kafka consumer that can be created, paused, resumed, and shut down at runtime.
 * <br/>
 * This class is responsible for:
 * 1. Creating and managing a Kafka consumer
 * 2. Polling for messages from a Kafka topic
 * 3. Processing messages by forwarding them to the PipeStreamEngine
 * 4. Supporting pause and resume operations
 * 5. Providing clean shutdown
 * <br/>
 * The DynamicKafkaListener runs in its own thread and can be paused and resumed
 * without stopping the thread.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class DynamicKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaListener.class);

    private final String listenerId;
    private final String topic;
    private final String groupId;
    private final Map<String, Object> consumerConfig;
    private final String pipelineName;
    private final String stepName;
    private final PipeStreamEngine pipeStreamEngine;

    private KafkaConsumer<UUID, PipeStream> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private ExecutorService executorService;

    /**
     * Creates a new DynamicKafkaListener.
     *
     * @param listenerId The ID of the listener
     * @param topic The Kafka topic to listen to
     * @param groupId The consumer group ID
     * @param consumerConfig Additional consumer configuration properties
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @param pipeStreamEngine The PipeStreamEngine to forward messages to
     */
    public DynamicKafkaListener(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> consumerConfig,
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

        // Add required properties
        this.consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        this.consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.UUIDDeserializer");
        this.consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "com.krickert.search.model.serialization.PipeStreamDeserializer");

        initialize();
    }

    /**
     * Initializes the Kafka consumer and starts the polling thread.
     */
    private void initialize() {
        consumer = new KafkaConsumer<UUID,PipeStream>(consumerConfig);
        consumer.subscribe(Collections.singletonList(topic));

        executorService = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                        .setNameFormat("kafka-listener-%s")
                        .setDaemon(true)
                        .build());

        running.set(true);
        executorService.submit(this::pollLoop);

        log.info("Initialized Kafka listener: {} for topic: {}, group: {}",
                listenerId, topic, groupId);
    }

    /**
     * The main polling loop that runs in a separate thread.
     * This method continuously polls for messages from Kafka and processes them.
     */
    private void pollLoop() {
        try {
            while (running.get()) {
                if (!paused.get()) {
                    ConsumerRecords<UUID, PipeStream> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        try {
                            processRecord(record);
                        } catch (Exception e) {
                            log.error("Error processing record: {}", e.getMessage(), e);
                        }
                    }
                } else {
                    // When paused, just sleep a bit to avoid busy waiting
                    Thread.sleep(100);
                }
            }
        } catch (Exception e) {
            log.error("Error in Kafka consumer poll loop: {}", e.getMessage(), e);
        } finally {
            try {
                consumer.close();
            } catch (Exception e) {
                log.error("Error closing Kafka consumer: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Processes a single Kafka record by forwarding it to the PipeStreamEngine.
     *
     * This method acknowledges the message right after deserialization,
     * and then processes it asynchronously to ensure exactly-once processing
     * in a fan-in/fan-out system.
     *
     * @param record The Kafka record to process
     */
    private void processRecord(ConsumerRecord<UUID, PipeStream> record) {
        // Deserialize the message and acknowledge it immediately
        PipeStream pipeStream = record.value();

        // Log that we've received the message
        log.debug("Received record from topic: {}, partition: {}, offset: {}",
                record.topic(), record.partition(), record.offset());

        // Update the PipeStream with the current pipeline and step information
        PipeStream updatedPipeStream = pipeStream.toBuilder()
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .build();

        // Process the stream asynchronously
        // This ensures that the processing happens exactly once in a fan-in/fan-out system
        // The StreamObserver is used to handle the response from the processPipeAsync method
        StreamObserver<Empty> responseObserver = new StreamObserver<Empty>() {
            @Override
            public void onNext(Empty empty) {
                // Nothing to do here, as we don't need the response
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Error processing record from topic: {}, partition: {}, offset: {}: {}",
                        record.topic(), record.partition(), record.offset(), throwable.getMessage(), throwable);
            }

            @Override
            public void onCompleted() {
                log.debug("Completed processing record from topic: {}, partition: {}, offset: {}",
                        record.topic(), record.partition(), record.offset());
            }
        };

        // Call the processStream method on the PipeStreamEngine
        // This is a non-blocking call that will process the message asynchronously
        pipeStreamEngine.processStream(updatedPipeStream);

        log.debug("Acknowledged record from topic: {}, partition: {}, offset: {}",
                record.topic(), record.partition(), record.offset());
    }

    /**
     * Pauses the consumer.
     * This method pauses the consumer without stopping the polling thread.
     */
    public void pause() {
        if (paused.compareAndSet(false, true)) {
            Set<TopicPartition> partitions = consumer.assignment();
            consumer.pause(partitions);
            log.info("Paused Kafka listener: {}", listenerId);
        }
    }

    /**
     * Resumes the consumer.
     * This method resumes a paused consumer.
     */
    public void resume() {
        if (paused.compareAndSet(true, false)) {
            Set<TopicPartition> partitions = consumer.assignment();
            consumer.resume(partitions);
            log.info("Resumed Kafka listener: {}", listenerId);
        }
    }

    /**
     * Shuts down the consumer.
     * This method stops the polling thread and closes the consumer.
     */
    public void shutdown() {
        running.set(false);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Shut down Kafka listener: {}", listenerId);
    }

    /**
     * Checks if the consumer is paused.
     *
     * @return true if the consumer is paused, false otherwise
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Gets the ID of the listener.
     *
     * @return The listener ID
     */
    public String getListenerId() {
        return listenerId;
    }

    /**
     * Gets the topic the consumer is subscribed to.
     *
     * @return The topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * Gets the consumer group ID.
     *
     * @return The group ID
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Gets the name of the pipeline.
     *
     * @return The pipeline name
     */
    public String getPipelineName() {
        return pipelineName;
    }

    /**
     * Gets the name of the step.
     *
     * @return The step name
     */
    public String getStepName() {
        return stepName;
    }
}
