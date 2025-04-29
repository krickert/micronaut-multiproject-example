// <llm-snippet-file>pipeline-service-core/src/main/java/com/krickert/search/pipeline/kafka/DynamicKafkaConsumerManager.java</llm-snippet-file>
package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.Route;
import com.krickert.search.pipeline.config.InternalServiceConfig;
import com.krickert.search.pipeline.config.PipelineConfig;
import com.krickert.search.pipeline.config.PipelineConfigService;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.pipeline.grpc.PipelineServiceImpl;
import com.krickert.search.pipeline.kafka.serde.KafkaSerdeProvider;
import io.micronaut.configuration.kafka.config.AbstractKafkaConsumerConfiguration;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceReadyEvent; // Example trigger, replace if needed
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Singleton
@Requires(beans = {PipelineConfigService.class, PipelineServiceImpl.class, KafkaSerdeProvider.class},
          property = "kafka.consumer.dynamic.enabled", 
          notEquals = "false") // Make it optional in tests
public class DynamicKafkaConsumerManager implements ApplicationEventListener<ServiceReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaConsumerManager.class);

    private final PipelineConfigService configService;
    private final PipelineServiceImpl pipelineService; // Use the implementation
    private final ExecutorService executorService;
    private final KafkaSerdeProvider serdeProvider; // Inject the provider
    private final AbstractKafkaConsumerConfiguration<?, ?> defaultKafkaConfig; // Keep for base props like bootstrap servers
    private final InternalServiceConfig internalServiceConfig; // For service name
    private final KafkaForwarder kafkaForwarder; // For sending messages to DLQ

    // Map to hold running consumers: Key = pipelineName (groupId), Value = ManagedConsumer details
    private final Map<String, ManagedConsumer> runningConsumers = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown = false;

    @Inject
    public DynamicKafkaConsumerManager(
            PipelineConfigService configService,
            PipelineServiceImpl pipelineService,
            @Named("dynamic-kafka-consumer-executor") ExecutorService executorService, 
            KafkaSerdeProvider serdeProvider,
            // Inject the default config to get bootstrap servers, schema registry URL etc.
            AbstractKafkaConsumerConfiguration<?, ?> defaultKafkaConfig,
            InternalServiceConfig internalServiceConfig,
            KafkaForwarder kafkaForwarder
    ) {
        this.configService = configService;
        this.pipelineService = pipelineService;
        this.executorService = executorService;
        this.serdeProvider = serdeProvider;
        this.defaultKafkaConfig = defaultKafkaConfig;
        this.internalServiceConfig = internalServiceConfig;
        this.kafkaForwarder = kafkaForwarder;
    }

    // The executor service is now provided by ExecutorConfiguration

    @PostConstruct
    void initialize() {
        log.info("Initializing DynamicKafkaConsumerManager...");
        // Initial synchronization on startup
        synchronizeConsumers();
    }

    @Override
    public void onApplicationEvent(ServiceReadyEvent event) {
        // TODO: This is just an example trigger. Listen to actual configuration
        //       refresh events (e.g., RefreshEvent if using Spring Cloud Config / Consul config)
        //       or provide an explicit API endpoint to trigger synchronization.
        log.info("Application ready or configuration potentially refreshed, synchronizing Kafka consumers...");
        synchronizeConsumers();
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down DynamicKafkaConsumerManager...");
        shuttingDown = true;
        // Stop all managed consumers
        // Create a copy of keyset to avoid ConcurrentModificationException
        List<String> pipelineNames = List.copyOf(runningConsumers.keySet());
        pipelineNames.forEach(this::stopPipelineConsumer);

        log.info("Shutting down consumer executor service...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate gracefully after 10 seconds.");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted during executor shutdown.", e);
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("DynamicKafkaConsumerManager shut down complete.");
    }

    /**
     * Compares the current pipeline configurations with the running consumers
     * and starts/stops consumers as needed.
     */
    public synchronized void synchronizeConsumers() {
        if (shuttingDown) {
            log.warn("Manager is shutting down, skipping consumer synchronization.");
            return;
        }
        log.info("Synchronizing Kafka consumers with current configurations...");

        Map<String, PipelineConfig> allConfigs = configService.getPipelineConfigs(); // Get the underlying map
        if (allConfigs == null || allConfigs.isEmpty()) {
            log.warn("No pipeline configurations found. Stopping all consumers.");
            allConfigs = Map.of(); // Ensure it's not null for comparison
        }

        final Map<String, PipelineConfig> currentConfigs = allConfigs; // Final for lambda
        final Set<String> configuredPipelineNames = currentConfigs.keySet();
        final Set<String> activeConsumerPipelineNames = runningConsumers.keySet();

        // 1. Stop consumers for pipelines that are no longer configured
        Set<String> pipelinesToStop = activeConsumerPipelineNames.stream()
                .filter(name -> !configuredPipelineNames.contains(name))
                .collect(Collectors.toSet());

        if (!pipelinesToStop.isEmpty()) {
            log.info("Pipelines to stop (no longer configured): {}", pipelinesToStop);
            pipelinesToStop.forEach(this::stopPipelineConsumer);
        }

        // 2. Start consumers for newly added pipelines
        Set<String> pipelinesToStart = configuredPipelineNames.stream()
                .filter(name -> !activeConsumerPipelineNames.contains(name))
                .collect(Collectors.toSet());

        if (!pipelinesToStart.isEmpty()) {
            log.info("Pipelines to start (newly configured): {}", pipelinesToStart);
            pipelinesToStart.forEach(pipelineName -> {
                PipelineConfig config = currentConfigs.get(pipelineName);
                if (config != null) {
                    startPipelineConsumer(pipelineName, config);
                } else {
                    log.error("Configuration for pipeline '{}' was null during startup sync.", pipelineName);
                }
            });
        }

        // 3. TODO: Check for configuration changes (e.g., topics) in existing pipelines
        //    For now, we only handle add/remove. A simple approach for updates
        //    could be to stop and restart the consumer if its config hash changes.
        //    Example check:
        //    activeConsumerPipelineNames.stream()
        //        .filter(configuredPipelineNames::contains) // Only check existing ones
        //        .forEach(name -> {
        //            ManagedConsumer running = runningConsumers.get(name);
        //            PipelineConfig current = currentConfigs.get(name);
        //            if (running != null && current != null && !running.getConfigSnapshot().equals(current.getKafkaListenTopics())) { // Simplified check
        //                log.info("Configuration changed for pipeline '{}'. Restarting consumer.", name);
        //                stopPipelineConsumer(name);
        //                startPipelineConsumer(name, current);
        //            }
        //        });

        log.info("Consumer synchronization complete. Active consumers: {}", runningConsumers.keySet());
    }


    private void startPipelineConsumer(String pipelineName, PipelineConfig config) {
        if (shuttingDown) {
            log.warn("Manager is shutting down, refusing to start consumer for pipeline '{}'", pipelineName);
            return;
        }

        // Get the service configuration for the current service
        String serviceName = internalServiceConfig.getPipelineServiceName();
        ServiceConfiguration serviceConfig = config.getService().get(serviceName);
        List<String> topicsToSubscribe = serviceConfig != null ? serviceConfig.getKafkaListenTopics() : null;
        // Use pipeline name as group ID
        if (topicsToSubscribe == null || topicsToSubscribe.isEmpty()) {
            log.warn("No Kafka input topics defined for pipeline '{}'. Consumer will not start.", pipelineName);
            return;
        }
        if (runningConsumers.containsKey(pipelineName)) {
             log.warn("Consumer for pipeline '{}' is already running or starting.", pipelineName);
             return; // Avoid multiple starts
        }

        log.info("Starting Kafka consumer for pipeline '{}' (Group ID: {}) on topics: {}", pipelineName, pipelineName, topicsToSubscribe);

        Properties props = new Properties();
        // Copy essential properties from default Micronaut config (bootstrap servers etc.)
        props.putAll(defaultKafkaConfig.getConfig()); // Use base config for bootstrap etc.

        // --- Override with Dynamic/Required Values ---
        props.put(ConsumerConfig.GROUP_ID_CONFIG, pipelineName);
        // Deserializers will be provided directly to the KafkaConsumer constructor,
        // so remove potentially conflicting class names from properties.
        props.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        props.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        // Sensible defaults, can be overridden via defaultKafkaConfig if needed
        props.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.putIfAbsent(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // Use manual commits

        KafkaConsumer<String, PipeStream> consumer = null;
        try {
            // --- Get Deserializer Instances from Provider ---
            Deserializer<String> keyDeserializer = serdeProvider.getKeyDeserializer(pipelineName, config);
            // Note: We assume PipeStream here based on the context, but the provider could return
            // a deserializer for a different type if designed that way. The consumer type parameter
            // <String, PipeStream> dictates the expected types.
            Deserializer<PipeStream> valueDeserializer = serdeProvider.getValueDeserializer(pipelineName, config);

            // --- Create KafkaConsumer Instance ---
            consumer = new KafkaConsumer<>(props, keyDeserializer, valueDeserializer);

            consumer.subscribe(topicsToSubscribe);
            log.info("Subscribed Kafka consumer for pipeline '{}' (Group ID: {}) to topics: {}", pipelineName, pipelineName, topicsToSubscribe);

            AtomicBoolean running = new AtomicBoolean(true);
            // Submit the polling loop task
            KafkaConsumer<String, PipeStream> finalConsumer = consumer;
            Future<?> pollTask = executorService.submit(() -> pollLoop(pipelineName, finalConsumer, running));

            // Store the managed consumer details
            ManagedConsumer managedConsumer = new ManagedConsumer(consumer, pollTask, running, topicsToSubscribe); // Store topics for change detection
            runningConsumers.put(pipelineName, managedConsumer);

            log.info("Kafka consumer started successfully for pipeline '{}'.", pipelineName);

        } catch (Exception e) {
            log.error("Failed to create or subscribe Kafka consumer for pipeline '{}' (Group: {}): {}", pipelineName, pipelineName, e.getMessage(), e);
            if (consumer != null) {
                try {
                    // Close consumer if creation failed after instantiation
                    consumer.close(Duration.ofSeconds(5));
                } catch (Exception closeEx) {
                    log.warn("Error closing consumer after failed startup for pipeline '{}': {}", pipelineName, closeEx.getMessage());
                }
            }
            // Ensure it's not marked as running if start failed
            runningConsumers.remove(pipelineName);
        }
    }

    private void stopPipelineConsumer(String pipelineName) {
        ManagedConsumer managedConsumer = runningConsumers.remove(pipelineName);
        if (managedConsumer != null) {
            log.info("Stopping Kafka consumer for pipeline '{}' (Group ID: {})...", pipelineName, pipelineName);
            if (managedConsumer.running.compareAndSet(true, false)) {
                 KafkaConsumer<?, ?> consumer = managedConsumer.consumer;
                 Future<?> task = managedConsumer.pollTask;
                try {
                    // 1. Signal poll loop to stop
                    consumer.wakeup();
                    // 2. Wait briefly for the task to potentially finish cleanly after wakeup
                    task.get(2, TimeUnit.SECONDS); // Wait for poll loop to exit
                    log.debug("Poll loop task finished or timed out for pipeline '{}'", pipelineName);
                } catch (TimeoutException e) {
                    log.warn("Poll loop task for pipeline '{}' did not finish cleanly after wakeup, attempting cancellation.", pipelineName);
                    task.cancel(true); // Interrupt if it's stuck
                } catch (CancellationException e) {
                     log.debug("Poll loop task for pipeline '{}' was cancelled.", pipelineName);
                }
                 catch (Exception e) {
                    log.warn("Exception during consumer poll task shutdown for pipeline '{}': {}. Will proceed with close.", pipelineName, e.getMessage());
                    task.cancel(true); // Attempt cancellation if other error occurred
                     if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                } finally {
                    // 3. Close the Kafka consumer client
                    try {
                        log.debug("Closing Kafka client for pipeline '{}'", pipelineName);
                        consumer.close(Duration.ofSeconds(10)); // Close with timeout
                        log.info("Kafka consumer closed successfully for pipeline '{}'.", pipelineName);
                    } catch (Exception e) {
                        log.error("Error closing Kafka consumer for pipeline '{}': {}", pipelineName, e.getMessage(), e);
                    }
                }
            } else {
                 log.debug("Consumer for pipeline '{}' was already marked as stopped.", pipelineName);
            }
        } else {
            log.debug("No running consumer found to stop for pipeline '{}'.", pipelineName);
        }
    }

   private void pollLoop(String pipelineName, KafkaConsumer<String, PipeStream> consumer, AtomicBoolean runningFlag) {
        final String groupId = consumer.groupMetadata().groupId(); // Should match pipelineName
        log.info("Starting poll loop for pipeline '{}' (Group ID: {})", pipelineName, groupId);
        try {
            while (runningFlag.get()) {
                try {
                    // Poll for records
                    ConsumerRecords<String, PipeStream> records = consumer.poll(Duration.ofSeconds(1)); // Adjust poll timeout as needed

                    if (!records.isEmpty()) {
                        log.debug("[{}] Polled {} records", pipelineName, records.count());
                        for (var record : records) { // Use enhanced for loop
                             if (!runningFlag.get()) break; // Check flag again before processing
                            try {
                                log.trace("[{}] Processing record from topic {}: key={}", pipelineName, record.topic(), record.key());
                                // --- Process Message ---
                                pipelineService.processKafkaMessage(record.value()); // Assumes PipeStream carries enough context or service figures it out
                                // --- Commit Offset ---
                                consumer.commitAsync((offsets, exception) -> {
                                    if (exception != null) {
                                        log.error("[{}] Failed to commit offset {} for group {}: {}", pipelineName, offsets, groupId, exception.getMessage(), exception);

                                        // Send the message to the DLQ on commit failure
                                        String dlqTopic = record.topic() + "-dlq";
                                        try {
                                            log.info("[{}] Sending message with commit failure to DLQ topic: {}", pipelineName, dlqTopic);
                                            Route dlqRoute = Route.newBuilder().setDestination(dlqTopic).build();
                                            kafkaForwarder.forwardToKafka(record.value(), dlqRoute);
                                        } catch (Exception dlqEx) {
                                            log.error("[{}] Failed to send message to DLQ topic {} after commit failure: {}", 
                                                    pipelineName, dlqTopic, dlqEx.getMessage(), dlqEx);
                                        }
                                    } else {
                                        log.trace("[{}] Offset committed: {}", pipelineName, offsets);
                                    }
                                });
                            } catch (Exception processingEx) {
                                log.error("[{}] Error processing Kafka message from topic {} (key={}) for group {}: {}",
                                        pipelineName, record.topic(), record.key(), groupId, processingEx.getMessage(), processingEx);

                                // Send the failed message to the DLQ
                                String dlqTopic = record.topic() + "-dlq";
                                try {
                                    log.info("[{}] Sending failed message to DLQ topic: {}", pipelineName, dlqTopic);
                                    // Create a Route with the DLQ topic as destination
                                    Route dlqRoute = Route.newBuilder().setDestination(dlqTopic).build();
                                    // Forward the message to the DLQ
                                    kafkaForwarder.forwardToKafka(record.value(), dlqRoute);

                                    // Commit the offset after sending to DLQ
                                    consumer.commitAsync((offsets, exception) -> {
                                        if (exception != null) {
                                            log.error("[{}] Failed to commit offset {} after DLQ for group {}: {}", 
                                                    pipelineName, offsets, groupId, exception.getMessage(), exception);
                                        } else {
                                            log.trace("[{}] Offset committed after DLQ: {}", pipelineName, offsets);
                                        }
                                    });
                                } catch (Exception dlqEx) {
                                    log.error("[{}] Failed to send message to DLQ topic {}: {}", 
                                            pipelineName, dlqTopic, dlqEx.getMessage(), dlqEx);
                                    // If DLQ fails, we don't commit the offset to allow reprocessing
                                }
                            }
                        } // End record processing loop
                    }
                } catch (WakeupException e) {
                    // Ignore wakeup exception, it's used to cleanly exit the loop when stopping
                    log.info("Kafka consumer poll loop woken up for pipeline '{}' (Group ID: {}). Exiting loop.", pipelineName, groupId);
                    runningFlag.set(false); // Ensure flag is false
                } catch (Exception e) {
                    // Log other poll errors, but continue loop unless told to stop
                     if (!runningFlag.get()) {
                         log.info("Poll loop for pipeline '{}' interrupted or error during shutdown.", pipelineName);
                         break; // Exit if already stopping
                     }
                    log.error("Unexpected error in Kafka poll loop for pipeline '{}' (Group ID: {}): {}", pipelineName, groupId, e.getMessage(), e);
                    // Consider more robust error handling: backoff/retry, stop consumer?
                    try {
                        Thread.sleep(1000); // Prevent tight loop on continuous errors
                    } catch (InterruptedException ie) {
                        log.warn("Poll loop sleep interrupted for pipeline '{}'", pipelineName);
                        Thread.currentThread().interrupt();
                        runningFlag.set(false); // Exit loop if interrupted
                    }
                }
            } // End while(running)
        } finally {
            log.info("Exited poll loop for pipeline '{}' (Group ID: {}).", pipelineName, groupId);
            // Ensure state is correct even if loop exits unexpectedly
            runningFlag.set(false);
            // Closing the consumer is handled by the stopPipelineConsumer method
        }
    }


    /**
     * Helper class to hold details about a managed consumer.
     */
    @Getter
    @AllArgsConstructor
    private static class ManagedConsumer {
        private final KafkaConsumer<String, PipeStream> consumer;
        private final Future<?> pollTask;
        private final AtomicBoolean running;
        private final List<String> configSnapshot; // Store subscribed topics (or hash of config) for change detection
    }
}
