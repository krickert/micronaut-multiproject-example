package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.commons.events.KafkaVcrControlEvent;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for Kafka VCR control events and delegates to KafkaListenerManager.
 * This provides event-driven control of Kafka consumers without tight coupling.
 */
@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class KafkaVcrEventListener implements ApplicationEventListener<KafkaVcrControlEvent> {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaVcrEventListener.class);
    
    private final KafkaListenerManager listenerManager;
    
    @Inject
    public KafkaVcrEventListener(KafkaListenerManager listenerManager) {
        this.listenerManager = listenerManager;
    }
    
    @Override
    @Async
    public void onApplicationEvent(@NonNull KafkaVcrControlEvent event) {
        LOG.info("Received Kafka VCR event: operation={}, pipeline={}, step={}, topic={}, group={}",
                event.operation(), event.pipelineId(), event.stepId(), 
                event.topicName(), event.groupId());
        
        try {
            switch (event.operation()) {
                case PAUSE -> handlePause(event);
                case RESUME -> handleResume(event);
                case REWIND -> handleRewind(event);
                case FAST_FORWARD -> handleFastForward(event);
            }
        } catch (Exception e) {
            LOG.error("Failed to handle VCR event {}: {}", event.eventId(), e.getMessage(), e);
        }
    }
    
    private void handlePause(KafkaVcrControlEvent event) {
        LOG.info("Pausing consumer for topic {} group {}", event.topicName(), event.groupId());
        
        listenerManager.pauseConsumer(
                event.pipelineId(), 
                event.stepId(), 
                event.topicName(), 
                event.groupId()
        ).subscribe(
            unused -> LOG.info("Successfully paused consumer for topic {} group {}", 
                             event.topicName(), event.groupId()),
            error -> LOG.error("Failed to pause consumer for topic {} group {}: {}", 
                              event.topicName(), event.groupId(), error.getMessage(), error)
        );
    }
    
    private void handleResume(KafkaVcrControlEvent event) {
        LOG.info("Resuming consumer for topic {} group {}", event.topicName(), event.groupId());
        
        listenerManager.resumeConsumer(
                event.pipelineId(), 
                event.stepId(), 
                event.topicName(), 
                event.groupId()
        ).subscribe(
            unused -> LOG.info("Successfully resumed consumer for topic {} group {}", 
                             event.topicName(), event.groupId()),
            error -> LOG.error("Failed to resume consumer for topic {} group {}: {}", 
                              event.topicName(), event.groupId(), error.getMessage(), error)
        );
    }
    
    private void handleRewind(KafkaVcrControlEvent event) {
        LOG.info("Rewinding consumer for topic {} group {} to timestamp {}", 
                event.topicName(), event.groupId(), event.targetTimestamp());
        
        listenerManager.resetOffsetToDate(
                event.pipelineId(), 
                event.stepId(), 
                event.topicName(), 
                event.groupId(),
                event.targetTimestamp()
        ).subscribe(
            unused -> LOG.info("Successfully rewound consumer for topic {} group {} to {}", 
                             event.topicName(), event.groupId(), event.targetTimestamp()),
            error -> LOG.error("Failed to rewind consumer for topic {} group {}: {}", 
                              event.topicName(), event.groupId(), error.getMessage(), error)
        );
    }
    
    private void handleFastForward(KafkaVcrControlEvent event) {
        LOG.info("Fast-forwarding consumer for topic {} group {} to latest", 
                event.topicName(), event.groupId());
        
        listenerManager.resetOffsetToLatest(
                event.pipelineId(), 
                event.stepId(), 
                event.topicName(), 
                event.groupId()
        ).subscribe(
            unused -> LOG.info("Successfully fast-forwarded consumer for topic {} group {}", 
                             event.topicName(), event.groupId()),
            error -> LOG.error("Failed to fast-forward consumer for topic {} group {}: {}", 
                              event.topicName(), event.groupId(), error.getMessage(), error)
        );
    }
}