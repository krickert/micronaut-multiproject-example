package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class DefaultKafkaListenerPool implements KafkaListenerPool {
    private static final Logger log = LoggerFactory.getLogger(DefaultKafkaListenerPool.class);

    private final Map<String, DynamicKafkaListener> listeners = new ConcurrentHashMap<>();
    private final DynamicKafkaListenerFactory listenerFactory; // Use the factory

    @Inject // Inject the factory
    public DefaultKafkaListenerPool(DynamicKafkaListenerFactory listenerFactory) {
        this.listenerFactory = listenerFactory;
    }

    @Override
    public DynamicKafkaListener createListener(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> finalConsumerConfig,
            Map<String, String> originalConsumerPropertiesFromStep,
            String pipelineName,
            String stepName,
            ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher) {

        if (listeners.containsKey(listenerId)) {
            log.warn("Listener with ID {} already exists. Shutting down existing before creating new.", listenerId);
            removeListener(listenerId);
        }

        // Use the factory to create the listener instance
        DynamicKafkaListener listener = listenerFactory.create(
                listenerId,
                topic,
                groupId,
                finalConsumerConfig,
                originalConsumerPropertiesFromStep,
                pipelineName,
                stepName,
                eventPublisher
        );

        listeners.put(listenerId, listener);
        log.info("Created and registered new Kafka listener: ID={}, Topic={}, GroupId={}", listenerId, topic, groupId);
        return listener;
    }

    // ... rest of the methods (removeListener, getListener, etc.) remain the same
    public DynamicKafkaListener removeListener(String listenerId) {
        DynamicKafkaListener listener = listeners.remove(listenerId);
        if (listener != null) {
            listener.shutdown();
            log.info("Removed Kafka listener: {}", listenerId);
        } else {
            log.warn("Attempted to remove non-existent listener: {}", listenerId);
        }
        return listener;
    }

    public DynamicKafkaListener getListener(String listenerId) {
        return listeners.get(listenerId);
    }

    public Collection<DynamicKafkaListener> getAllListeners() {
        return Collections.unmodifiableCollection(listeners.values());
    }

    public int getListenerCount() {
        return listeners.size();
    }

    public boolean hasListener(String listenerId) {
        return listeners.containsKey(listenerId);
    }

    public void shutdownAllListeners() {
        // Create a copy of the values to avoid ConcurrentModificationException
        // if shutdown() somehow modifies the listeners map (though it shouldn't directly)
        for (DynamicKafkaListener listener : List.copyOf(listeners.values())) {
            try {
                listener.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down listener: {}", listener.getListenerId(), e);
            }
        }
        listeners.clear();
        log.info("Shut down all Kafka listeners");
    }
}