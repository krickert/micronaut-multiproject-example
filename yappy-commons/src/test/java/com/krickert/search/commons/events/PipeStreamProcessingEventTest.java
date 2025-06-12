package com.krickert.search.commons.events;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipeStreamProcessingEventTest {
    
    @Test
    void testCreateFromKafka() {
        // Given
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("test-stream-id")
            .setCurrentPipelineName("test-pipeline")
            .setTargetStepName("test-step")
            .setDocument(PipeDoc.newBuilder().setId("test-doc-id").build())
            .build();
            
        // When
        PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromKafka(
            pipeStream,
            "test-topic",
            0,
            100L,
            "test-consumer-group"
        );
        
        // Then
        assertThat(event).isNotNull();
        assertThat(event.getPipeStream()).isEqualTo(pipeStream);
        assertThat(event.getSourceModule()).isEqualTo("kafka-listener");
        assertThat(event.getProcessingContext())
            .containsEntry("kafka.topic", "test-topic")
            .containsEntry("kafka.partition", 0)
            .containsEntry("kafka.offset", 100L)
            .containsEntry("kafka.consumer.group", "test-consumer-group");
    }
    
    @Test
    void testEventHierarchy() {
        // Given
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("test-stream-id")
            .setCurrentPipelineName("test-pipeline")
            .setTargetStepName("test-step")
            .setDocument(PipeDoc.newBuilder().setId("test-doc-id").build())
            .build();
            
        // When
        PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromGrpc(
            pipeStream,
            "TestService",
            "processStream"
        );
        
        // Then
        assertThat(event).isInstanceOf(BaseEvent.class);
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getTimestamp()).isNotNull();
        assertThat(event.getMetadata()).isNotNull();
        assertThat(event.getPriority()).isEqualTo(EventPriority.NORMAL);
    }
}