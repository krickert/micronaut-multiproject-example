package com.krickert.search.engine.service.impl;

import com.krickert.search.engine.service.MessageRoutingService;
import com.krickert.search.engine.service.ModuleRegistrationService;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.model.PipeStream;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.Topic;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of MessageRoutingService that handles message routing
 * through gRPC calls to modules and Kafka for async communication.
 */
@Singleton
public class MessageRoutingServiceImpl implements MessageRoutingService {
    
    private static final Logger LOG = LoggerFactory.getLogger(MessageRoutingServiceImpl.class);
    
    private final ModuleRegistrationService registrationService;
    private final KafkaMessageProducer kafkaProducer;
    private final Map<String, ManagedChannel> grpcChannels = new ConcurrentHashMap<>();
    
    @Inject
    public MessageRoutingServiceImpl(
            ModuleRegistrationService registrationService,
            KafkaMessageProducer kafkaProducer) {
        this.registrationService = registrationService;
        this.kafkaProducer = kafkaProducer;
    }
    
    @Override
    public Mono<PipeStream> routeMessage(PipeStream pipeStream, String pipelineName) {
        // TODO: Implement pipeline routing logic
        // For now, this is a placeholder that would:
        // 1. Look up the pipeline configuration
        // 2. Determine the next step based on current processing state
        // 3. Route to the appropriate module
        
        return Mono.just(pipeStream);
    }
    
    @Override
    public Mono<PipeStream> sendToModule(PipeStream pipeStream, String moduleId) {
        return registrationService.listRegisteredModules()
            .filter(module -> module.getServiceId().equals(moduleId))
            .next()
            .flatMap(moduleInfo -> {
                String channelKey = moduleInfo.getHost() + ":" + moduleInfo.getPort();
                ManagedChannel channel = grpcChannels.computeIfAbsent(channelKey, key -> 
                    ManagedChannelBuilder
                        .forAddress(moduleInfo.getHost(), moduleInfo.getPort())
                        .usePlaintext()
                        .build()
                );
                
                PipeStepProcessorGrpc.PipeStepProcessorStub stub = 
                    PipeStepProcessorGrpc.newStub(channel);
                
                // TODO: Create ProcessRequest from PipeStream
                // For now, return the original PipeStream as we need to implement the conversion
                LOG.warn("Message routing to modules not fully implemented yet");
                return Mono.just(pipeStream);
            })
            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                "Module not found: " + moduleId)));
    }
    
    @Override
    public Mono<Boolean> sendToKafkaTopic(PipeStream pipeStream, String topicName) {
        return Mono.fromCallable(() -> {
            try {
                kafkaProducer.sendMessage(topicName, 
                    pipeStream.getDocument().getId(), 
                    pipeStream.toByteArray());
                LOG.debug("Sent message to Kafka topic: {}", topicName);
                return true;
            } catch (Exception e) {
                LOG.error("Error sending message to Kafka topic: {}", topicName, e);
                return false;
            }
        });
    }
    
    /**
     * Cleanup gRPC channels on shutdown.
     */
    public void shutdown() {
        grpcChannels.forEach((key, channel) -> {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }
        });
        grpcChannels.clear();
    }
    
    /**
     * Kafka producer interface for sending messages.
     */
    @KafkaClient
    public interface KafkaMessageProducer {
        void sendMessage(@Topic String topic, String key, byte[] value);
    }
}