package com.krickert.search.pipeline.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for configuration update events from Kafka and triggers
 * a Micronaut RefreshEvent to synchronize dynamic consumers.
 */
@Singleton // Ensure this listener is created
@Requires(property = "kafka.config.update.listener.enabled", value = "true", defaultValue = "true") // Make it configurable
@KafkaListener(
    groupId = "${kafka.config.update.listener.group-id:config-update-listeners}", // Configurable group ID
    offsetReset = OffsetReset.LATEST // Process only new events after startup
)
public class ConfigUpdateKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigUpdateKafkaListener.class);

    private final ApplicationEventPublisher<RefreshEvent> eventPublisher;

    @Inject
    public ConfigUpdateKafkaListener(ApplicationEventPublisher<RefreshEvent> eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Receives configuration update events.
     *
     * @param event The received ConfigUpdateEvent.
     * @param key   The Kafka message key (optional, can be null).
     */
    @Topic("${kafka.config.update.listener.topic:config-updates}") // Configurable topic name
    public void receiveConfigUpdate(ConfigUpdateEvent event, @KafkaKey String key) {
        if (event == null) {
            log.warn("Received null ConfigUpdateEvent message (Key: {}). Skipping.", key);
            return;
        }

        log.info("Received configuration update event via Kafka:");
        log.info("  Sending Machine: {}", event.sendingMachine());
        log.info("  Timestamp: {}", event.timestamp());
        log.info("  Change Details: {}", event.changeDetails());
        log.info("  Kafka Key: {}", key);

        // Publish the Micronaut RefreshEvent to trigger synchronization
        // We publish a generic RefreshEvent, causing all @Refreshable beans
        // and listeners like DynamicKafkaConsumerManager to react.
        log.info("Publishing Micronaut RefreshEvent to trigger consumer synchronization...");
        eventPublisher.publishEvent(new RefreshEvent()); // [1]
        log.info("RefreshEvent published successfully.");
    }
}