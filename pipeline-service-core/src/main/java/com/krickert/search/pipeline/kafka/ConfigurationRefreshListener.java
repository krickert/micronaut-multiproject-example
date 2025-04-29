package com.krickert.search.pipeline.kafka;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Singleton
public class ConfigurationRefreshListener implements ApplicationEventListener<RefreshEvent> {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationRefreshListener.class);
    private final DynamicKafkaConsumerManager consumerManager;
    // Inject publisher if you need to publish custom events or trigger manually
    private final ApplicationEventPublisher<RefreshEvent> eventPublisher;

    public ConfigurationRefreshListener(DynamicKafkaConsumerManager consumerManager, ApplicationEventPublisher<RefreshEvent> eventPublisher) {
        this.consumerManager = consumerManager;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onApplicationEvent(RefreshEvent event) {
        // The event source contains the keys that changed and their previous values,
        // or is null/empty for a full refresh request.
        log.info("Configuration refresh detected via RefreshEvent. Keys potentially changed: {}",
                 event.getSource()!= null? event.getSource().keySet() : "All");

        // Optional: Filter synchronization based on changed keys if RefreshEvent provides them
         Map<String, Object> changes = event.getSource();
         boolean relevantChange = (changes == null) || changes.keySet().stream()
                .anyMatch(key -> key.startsWith("pipeline.")); // Adjust prefix as needed

        if (relevantChange) {
             log.info("Triggering Kafka consumer synchronization due to RefreshEvent.");
             consumerManager.synchronizeConsumers();
         } else {
             log.debug("RefreshEvent detected, but no relevant pipeline configuration keys changed. Skipping synchronization.");
         }
    }

    // Example method if using a custom watcher to publish the event
    public void triggerRefresh() {
        log.info("Manually triggering RefreshEvent for consumer synchronization.");
        eventPublisher.publishEvent(new RefreshEvent());
    }
}