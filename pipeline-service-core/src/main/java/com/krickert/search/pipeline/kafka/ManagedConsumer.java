package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeStream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

// Refined ManagedConsumer
@Getter
@AllArgsConstructor
public class ManagedConsumer {
    private final KafkaConsumer<String, PipeStream> consumer;
    private final Future<?> pollTask;
    private final AtomicBoolean running;
    // Store a hash or serialized form of relevant config, not just topics
    private final int configSnapshotHash;
    private final String configSnapshotJson; // Alternative
}