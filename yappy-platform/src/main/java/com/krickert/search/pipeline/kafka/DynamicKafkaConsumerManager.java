package com.krickert.search.pipeline.kafka; // Assuming same package

//// <llm-snippet-file>pipeline-service-core/src/main/java/com/krickert/search/pipeline/kafka/DynamicKafkaConsumerManager.java</llm-snippet-file>
//
//import com.fasterxml.jackson.core.JsonProcessingException;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.krickert.search.config.consul.model.PipelineConfigDto;
//import com.krickert.search.model.PipeStream;
//import com.krickert.search.model.Route;
//import com.krickert.search.pipeline.config.InternalServiceConfig;
//import com.krickert.search.pipeline.config.PipelineConfig;
//import com.krickert.search.pipeline.config.PipelineConfigService;
//import com.krickert.search.pipeline.config.ServiceConfiguration;
//import com.krickert.search.pipeline.grpc.PipelineService;
//import com.krickert.search.pipeline.grpc.PipelineServiceImpl;
//import com.krickert.search.pipeline.kafka.serde.KafkaSerdeProvider;
//import io.micronaut.configuration.kafka.config.AbstractKafkaConsumerConfiguration;
//import io.micronaut.context.annotation.Requires;
//import io.micronaut.context.event.ApplicationEventListener;
//import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import jakarta.inject.Inject;
//import jakarta.inject.Named;
//import jakarta.inject.Singleton;
//import org.apache.kafka.clients.consumer.ConsumerConfig;
//import org.apache.kafka.clients.consumer.ConsumerRecords;
//import org.apache.kafka.clients.consumer.KafkaConsumer;
//import org.apache.kafka.common.errors.WakeupException;
//import org.apache.kafka.common.serialization.Deserializer;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.time.Duration;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.stream.Collectors;
//
//@Singleton
//@Requires(beans = {com.krickert.search.config.consul.service.PipelineService.class, PipelineServiceImpl.class, KafkaSerdeProvider.class, ObjectMapper.class},
//        property = "kafka.consumer.dynamic.enabled",
//        notEquals = "false")
//public class DynamicKafkaConsumerManager implements ApplicationEventListener<RefreshEvent> {
//
//    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaConsumerManager.class);
//
//    private final com.krickert.search.config.consul.service.PipelineService configService;
//    private final PipelineService pipelineService;
//    private final ExecutorService executorService;
//    private final KafkaSerdeProvider serdeProvider;
//    private final AbstractKafkaConsumerConfiguration<?,?> defaultKafkaConfig;
//    private final KafkaForwarder kafkaForwarder;
//    private final ObjectMapper objectMapper;
//
//    private final Map<String, ManagedConsumer> runningConsumers = new ConcurrentHashMap<>();
//    private volatile boolean shuttingDown = false;
//
//    @Inject
//    public DynamicKafkaConsumerManager(
//            PipelineConfigDto configService,
//            PipelineService pipelineService,
//            @Named("dynamic-kafka-consumer-executor") ExecutorService executorService,
//            KafkaSerdeProvider serdeProvider,
//            AbstractKafkaConsumerConfiguration<?,?> defaultKafkaConfig,
//            InternalServiceConfig internalServiceConfig,
//            KafkaForwarder kafkaForwarder,
//            ObjectMapper objectMapper
//    ) {
//        this.configService = configService;
//        this.pipelineService = pipelineService;
//        this.executorService = executorService;
//        this.serdeProvider = serdeProvider;
//        this.defaultKafkaConfig = defaultKafkaConfig;
//        this.internalServiceConfig = internalServiceConfig;
//        this.kafkaForwarder = kafkaForwarder;
//        this.objectMapper = objectMapper;
//    }
//
//    @PostConstruct
//    void initialize() {
//        log.info("Initializing DynamicKafkaConsumerManager...");
//        synchronizeConsumers();
//    }
//
//    @Override
//    public void onApplicationEvent(RefreshEvent event) {
//        log.info("Configuration refresh detected via RefreshEvent. Keys potentially changed: {}",
//                event.getSource()!= null? event.getSource().keySet() : "All");
//        synchronizeConsumers();
//    }
//
//    @PreDestroy
//    void shutdown() {
//        log.info("Shutting down DynamicKafkaConsumerManager...");
//        shuttingDown = true;
//        List<String> pipelineNames = List.copyOf(runningConsumers.keySet());
//        pipelineNames.forEach(this::stopPipelineConsumer);
//
//        log.info("Shutting down consumer executor service...");
//        executorService.shutdown();
//        try {
//            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
//                log.warn("Executor did not terminate gracefully after 10 seconds.");
//                executorService.shutdownNow();
//            }
//        } catch (InterruptedException e) {
//            log.warn("Interrupted during executor shutdown.", e);
//            executorService.shutdownNow();
//            Thread.currentThread().interrupt();
//        }
//        log.info("DynamicKafkaConsumerManager shut down complete.");
//    }
//
//    public synchronized void synchronizeConsumers() {
//        if (shuttingDown) {
//            log.warn("Manager is shutting down, skipping consumer synchronization.");
//            return;
//        }
//        log.info("Synchronizing Kafka consumers with current configurations...");
//
//        Map<String, PipelineConfigDto> allConfigs = configService.getPipelineConfigs();
//        if (allConfigs == null || allConfigs.isEmpty()) {
//            log.warn("No pipeline configurations found. Stopping all consumers.");
//            allConfigs = Map.of();
//        }
//
//        final Map<String, PipelineConfigDto> currentConfigs = allConfigs;
//        final Set<String> configuredPipelineNames = currentConfigs.keySet();
//        final Set<String> activeConsumerPipelineNames = runningConsumers.keySet();
//
//        // 1. Stop consumers for pipelines no longer configured
//        Set<String> pipelinesToStop = activeConsumerPipelineNames.stream()
//                .filter(name ->!configuredPipelineNames.contains(name))
//                .collect(Collectors.toSet());
//
//        if (!pipelinesToStop.isEmpty()) {
//            log.info("Pipelines to stop (no longer configured): {}", pipelinesToStop);
//            pipelinesToStop.forEach(this::stopPipelineConsumer);
//        }
//
//        // 2. Start consumers for newly added pipelines
//        Set<String> pipelinesToStart = configuredPipelineNames.stream()
//                .filter(name ->!activeConsumerPipelineNames.contains(name))
//                .collect(Collectors.toSet());
//
//        if (!pipelinesToStart.isEmpty()) {
//            log.info("Pipelines to start (newly configured): {}", pipelinesToStart);
//            pipelinesToStart.forEach(pipelineName -> {
//                PipelineConfig config = currentConfigs.get(pipelineName);
//                if (config!= null) {
//                    startPipelineConsumer(pipelineName, config);
//                } else {
//                    log.error("Configuration for pipeline '{}' was null during startup sync.", pipelineName);
//                }
//            });
//        }
//
//        // 3. Check for configuration changes in existing, running pipelines
//        activeConsumerPipelineNames.stream()
//                .filter(configuredPipelineNames::contains)
//                .forEach(pipelineName -> {
//                    ManagedConsumer running = runningConsumers.get(pipelineName);
//                    PipelineConfig currentConfig = currentConfigs.get(pipelineName);
//
//                    if (running!= null && currentConfig!= null) {
//                        // Calculate hash of current config snapshot
//                        String currentConfigJson = calculateConfigSnapshotJson(currentConfig);
//                        int currentConfigHash = (currentConfigJson!= null)? currentConfigJson.hashCode() : 0;
//                        int runningConfigHash = running.getConfigSnapshotHash(); // Assumes ManagedConsumer has this getter
//
//                        // Compare hashes
//                        if (runningConfigHash!= currentConfigHash) {
//                            log.info("Configuration change detected for pipeline '{}' (Hash mismatch: {} vs {}). Restarting consumer.",
//                                    pipelineName, runningConfigHash, currentConfigHash);
//                            // Stop-Restart Strategy
//                            stopPipelineConsumer(pipelineName);
//                            startPipelineConsumer(pipelineName, currentConfig);
//                        } else {
//                            log.trace("Configuration for pipeline '{}' hasn't changed (Hash: {}).", pipelineName, runningConfigHash);
//                        }
//                    }
//                });
//
//        log.info("Consumer synchronization complete. Active consumers: {}", runningConsumers.keySet());
//    }
//
//    private void startPipelineConsumer(String pipelineName) {
//        if (shuttingDown) {
//            log.warn("Manager is shutting down, refusing to start consumer for pipeline '{}'", pipelineName);
//            return;
//        }
//
//        String serviceName = internalServiceConfig.getPipelineServiceName();
//        ServiceConfiguration serviceConfig = config.getService().get(serviceName);
//        List<String> topicsToSubscribe = serviceConfig!= null? serviceConfig.getKafkaListenTopics() : null;
//
//        if (topicsToSubscribe == null || topicsToSubscribe.isEmpty()) {
//            log.warn("No Kafka input topics defined for pipeline '{}'. Consumer will not start.", pipelineName);
//            return;
//        }
//        if (runningConsumers.containsKey(pipelineName)) {
//            log.warn("Consumer for pipeline '{}' is already running or starting.", pipelineName);
//            return;
//        }
//
//        log.info("Starting Kafka consumer for pipeline '{}' (Group ID: {}) on topics: {}", pipelineName, pipelineName, topicsToSubscribe);
//
//        Properties props = new Properties();
//        props.putAll(defaultKafkaConfig.getConfig());
//
//        props.put(ConsumerConfig.GROUP_ID_CONFIG, pipelineName);
//        props.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
//        props.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
//        props.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
//        props.putIfAbsent(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
//
//        KafkaConsumer<String, PipeStream> consumer = null;
//        try {
//            Deserializer<String> keyDeserializer = serdeProvider.getKeyDeserializer(pipelineName);
//            Deserializer<PipeStream> valueDeserializer = serdeProvider.getValueDeserializer(pipelineName);
//
//            consumer = new KafkaConsumer<>(props, keyDeserializer, valueDeserializer);
//            consumer.subscribe(topicsToSubscribe);
//            log.info("Subscribed Kafka consumer for pipeline '{}' (Group ID: {}) to topics: {}", pipelineName, pipelineName, topicsToSubscribe);
//
//            AtomicBoolean running = new AtomicBoolean(true);
//            KafkaConsumer<String, PipeStream> finalConsumer = consumer;
//            Future<?> pollTask = executorService.submit(() -> pollLoop(pipelineName, finalConsumer, running));
//
//            // Calculate and store the config snapshot as JSON and its hash
//            String configSnapshotJson = calculateConfigSnapshotJson(config);
//            int configSnapshotHash = (configSnapshotJson!= null)? configSnapshotJson.hashCode() : 0;
//
//            // Assuming ManagedConsumer is now a public class with appropriate constructor
//            // Corrected constructor call with 5 arguments
//            ManagedConsumer managedConsumer = new ManagedConsumer(finalConsumer, pollTask, running, configSnapshotHash, configSnapshotJson);
//            runningConsumers.put(pipelineName, managedConsumer);
//
//            log.info("Kafka consumer started successfully for pipeline '{}'.", pipelineName);
//
//        } catch (Exception e) {
//            log.error("Failed to create or subscribe Kafka consumer for pipeline '{}' (Group: {}): {}", pipelineName, pipelineName, e.getMessage(), e);
//            if (consumer!= null) {
//                try {
//                    consumer.close(Duration.ofSeconds(5));
//                } catch (Exception closeEx) {
//                    log.warn("Error closing consumer after failed startup for pipeline '{}': {}", pipelineName, closeEx.getMessage());
//                }
//            }
//            runningConsumers.remove(pipelineName);
//        }
//    }
//
//    private void stopPipelineConsumer(String pipelineName) {
//        ManagedConsumer managedConsumer = runningConsumers.remove(pipelineName);
//        if (managedConsumer!= null) {
//            log.info("Stopping Kafka consumer for pipeline '{}' (Group ID: {})...", pipelineName, pipelineName);
//            if (managedConsumer.getRunning().compareAndSet(true, false)) {
//                KafkaConsumer<?,?> consumer = managedConsumer.getConsumer();
//                Future<?> task = managedConsumer.getPollTask();
//                try {
//                    consumer.wakeup();
//                    task.get(5, TimeUnit.SECONDS);
//                    log.debug("Poll loop task finished or timed out for pipeline '{}'", pipelineName);
//                } catch (TimeoutException e) {
//                    log.warn("Poll loop task for pipeline '{}' did not finish cleanly after wakeup, attempting cancellation.", pipelineName);
//                    task.cancel(true);
//                } catch (CancellationException e) {
//                    log.debug("Poll loop task for pipeline '{}' was cancelled.", pipelineName);
//                }
//                catch (Exception e) {
//                    log.warn("Exception during consumer poll task shutdown for pipeline '{}': {}. Will proceed with close.", pipelineName, e.getMessage());
//                    task.cancel(true);
//                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
//                } finally {
//                    try {
//                        log.debug("Closing Kafka client for pipeline '{}'", pipelineName);
//                        consumer.close(Duration.ofSeconds(10));
//                        log.info("Kafka consumer closed successfully for pipeline '{}'.", pipelineName);
//                    } catch (Exception e) {
//                        log.error("Error closing Kafka consumer for pipeline '{}': {}", pipelineName, e.getMessage(), e);
//                    }
//                }
//            } else {
//                log.debug("Consumer for pipeline '{}' was already marked as stopped.", pipelineName);
//            }
//        } else {
//            log.debug("No running consumer found to stop for pipeline '{}'.", pipelineName);
//        }
//    }
//
//    private void pollLoop(String pipelineName, KafkaConsumer<String, PipeStream> consumer, AtomicBoolean runningFlag) {
//        final String groupId = consumer.groupMetadata().groupId();
//        log.info("Starting poll loop for pipeline '{}' (Group ID: {})", pipelineName, groupId);
//        try {
//            while (runningFlag.get()) {
//                try {
//                    ConsumerRecords<String, PipeStream> records = consumer.poll(Duration.ofSeconds(1));
//
//                    if (!records.isEmpty()) {
//                        log.debug("[{}] Polled {} records", pipelineName, records.count());
//                        for (var record : records) {
//                            if (!runningFlag.get()) break;
//                            try {
//                                log.info("[{}] Processing record from topic {}: key={}", pipelineName, record.topic(), record.key());
//                                pipelineService.processKafkaMessage(record.value());
//                                consumer.commitAsync((offsets, exception) -> {
//                                    if (exception!= null) {
//                                        log.error("[{}] Failed to commit offset {} for group {}: {}", pipelineName, offsets, groupId, exception.getMessage(), exception);
//                                        String dlqTopic = record.topic() + "-dlq"; // Consider making configurable
//                                        try {
//                                            log.info("[{}] Sending message with commit failure to DLQ topic: {}", pipelineName, dlqTopic);
//                                            Route dlqRoute = Route.newBuilder().setDestination(dlqTopic).build();
//                                            kafkaForwarder.forwardToKafka(record.value(), dlqRoute);
//                                        } catch (Exception dlqEx) {
//                                            log.error("[{}] Failed to send message to DLQ topic {} after commit failure: {}",
//                                                    pipelineName, dlqTopic, dlqEx.getMessage(), dlqEx);
//                                        }
//                                    } else {
//                                        log.trace("[{}] Offset committed: {}", pipelineName, offsets);
//                                    }
//                                });
//                            } catch (Exception processingEx) {
//                                log.error("[{}] Error processing Kafka message from topic {} (key={}) for group {}: {}",
//                                        pipelineName, record.topic(), record.key(), groupId, processingEx.getMessage(), processingEx);
//
//                                String dlqTopic = record.topic() + "-dlq"; // Consider making configurable
//                                try {
//                                    log.info("[{}] Sending failed message to DLQ topic: {}", pipelineName, dlqTopic);
//                                    Route dlqRoute = Route.newBuilder().setDestination(dlqTopic).build();
//                                    kafkaForwarder.forwardToKafka(record.value(), dlqRoute);
//
//                                    consumer.commitAsync((offsets, exception) -> {
//                                        if (exception!= null) {
//                                            log.error("[{}] Failed to commit offset {} after DLQ for group {}: {}",
//                                                    pipelineName, offsets, groupId, exception.getMessage(), exception);
//                                        } else {
//                                            log.trace("[{}] Offset committed after DLQ: {}", pipelineName, offsets);
//                                        }
//                                    });
//                                } catch (Exception dlqEx) {
//                                    log.error("[{}] Failed to send message to DLQ topic {}: {}",
//                                            pipelineName, dlqTopic, dlqEx.getMessage(), dlqEx);
//                                }
//                            }
//                        }
//                    }
//                } catch (WakeupException e) {
//                    log.info("Kafka consumer poll loop woken up for pipeline '{}' (Group ID: {}). Exiting loop.", pipelineName, groupId);
//                    runningFlag.set(false);
//                } catch (Exception e) {
//                    if (!runningFlag.get()) {
//                        log.info("Poll loop for pipeline '{}' interrupted or error during shutdown.", pipelineName);
//                        break;
//                    }
//                    log.error("Unexpected error in Kafka poll loop for pipeline '{}' (Group ID: {}): {}", pipelineName, groupId, e.getMessage(), e);
//                    try {
//                        Thread.sleep(1000);
//                    } catch (InterruptedException ie) {
//                        log.warn("Poll loop sleep interrupted for pipeline '{}'", pipelineName);
//                        Thread.currentThread().interrupt();
//                        runningFlag.set(false);
//                    }
//                }
//            }
//        } finally {
//            log.info("Exited poll loop for pipeline '{}' (Group ID: {}).", pipelineName, groupId);
//            runningFlag.set(false);
//        }
//    }
//
//    /**
//     * Calculates a JSON string representation of the relevant configuration parts
//     * for a given pipeline to detect changes.
//     *
//     * @param config The PipelineConfig.
//     * @return A JSON string representing the config snapshot, or null if serialization fails.
//     */
//    private String calculateConfigSnapshotJson(PipelineConfig config) {
//        try {
//            String serviceName = internalServiceConfig.getPipelineServiceName();
//            ServiceConfiguration serviceConfig = config.getService().get(serviceName);
//            List<String> topics = (serviceConfig!= null)? serviceConfig.getKafkaListenTopics() : List.of();
//
//            Map<String, Object> relevantConfig = new HashMap<>();
//            relevantConfig.put("topics", topics);
//            // Add other relevant parts from PipelineConfig or ServiceConfiguration here
//            // relevantConfig.put("someOtherSetting", serviceConfig.getSomeOtherSetting());
//
//            return objectMapper.writeValueAsString(relevantConfig);
//        } catch (JsonProcessingException e) {
//            log.error("Failed to serialize configuration snapshot to JSON for pipeline '{}': {}", config.getName(), e.getMessage(), e);
//            return null;
//        } catch (Exception e) {
//            log.error("Unexpected error calculating configuration snapshot for pipeline '{}': {}", config.getName(), e.getMessage(), e);
//            return null;
//        }
//    }
//
//    // ManagedConsumer class is now assumed to be a public class defined elsewhere
//}