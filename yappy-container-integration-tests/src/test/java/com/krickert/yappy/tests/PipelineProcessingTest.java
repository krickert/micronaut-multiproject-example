package com.krickert.yappy.tests;

import com.google.protobuf.Empty;
import com.krickert.search.grpc.PipeStreamEngineGrpc;
import com.krickert.search.grpc.PipeStepProcessorGrpc;
import com.krickert.search.model.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests actual document processing through the pipeline.
 */
public class PipelineProcessingTest extends BaseContainerIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(PipelineProcessingTest.class);
    
    private ManagedChannel engineChannel;
    private ManagedChannel tikaChannel;
    private PipeStreamEngineGrpc.PipeStreamEngineBlockingStub engineStub;
    private PipeStepProcessorGrpc.PipeStepProcessorBlockingStub tikaStub;
    private KafkaConsumer<String, byte[]> kafkaConsumer;
    
    @BeforeEach
    void setup() {
        // Create gRPC channels
        engineChannel = ManagedChannelBuilder
                .forAddress(getEngineTikaHost(), getEngineTikaGrpcPort())
                .usePlaintext()
                .build();
        
        tikaChannel = ManagedChannelBuilder
                .forAddress(getEngineTikaHost(), getTikaGrpcPort())
                .usePlaintext()
                .build();
        
        engineStub = PipeStreamEngineGrpc.newBlockingStub(engineChannel);
        tikaStub = PipeStepProcessorGrpc.newBlockingStub(tikaChannel);
        
        // Setup Kafka consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getKafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        kafkaConsumer = new KafkaConsumer<>(props);
    }
    
    @AfterEach
    void tearDown() {
        if (engineChannel != null) {
            engineChannel.shutdown();
        }
        if (tikaChannel != null) {
            tikaChannel.shutdown();
        }
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
    
    @Test
    @DisplayName("Should process document through engine gRPC endpoint")
    void testEngineGrpcProcessing() {
        // Create a test document
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-1")
                .setTitle("Test Document")
                .setBody("This is a test document for processing.")
                .putMetadata("source", "integration-test")
                .build();
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId("test-stream-" + UUID.randomUUID())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("tika-parser")
                .setCurrentHopNumber(0)
                .setDocument(document)
                .build();
        
        // Process through engine
        Empty response = engineStub.processPipeAsync(pipeStream);
        assertThat(response).isNotNull();
        
        LOG.info("Document successfully submitted to engine");
    }
    
    @Test
    @DisplayName("Should process document directly through Tika module")
    void testDirectTikaProcessing() {
        // Create a test document with binary content
        byte[] pdfContent = Base64.getDecoder().decode(
                "JVBERi0xLjQKJeLjz9MKCjEgMCBvYmoKPDwKL1R5cGUgL0NhdGFsb2cKL1BhZ2VzIDIgMCBSCj4+"
        ); // Minimal PDF header
        
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-pdf-1")
                .setTitle("Test PDF")
                .setBinaryData(com.google.protobuf.ByteString.copyFrom(pdfContent))
                .putMetadata("contentType", "application/pdf")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setStreamId("test-stream-" + UUID.randomUUID())
                .setPipelineName("test-pipeline")
                .setStepName("tika-parser")
                .setDocument(document)
                .build();
        
        // Process through Tika
        ProcessResponse response = tikaStub.processData(request);
        
        assertThat(response).isNotNull();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.hasDocument()).isTrue();
        
        // Tika should extract text from the document
        PipeDoc processedDoc = response.getDocument();
        assertThat(processedDoc.getMetadataMap()).containsKey("tika_parsed");
        
        LOG.info("Document successfully processed by Tika module");
    }
    
    @Test
    @DisplayName("Should route document to Kafka topic")
    void testKafkaRouting() throws InterruptedException {
        // Subscribe to the output topic
        String outputTopic = "pipeline.test-pipeline.step.tika-parser.output";
        kafkaConsumer.subscribe(Collections.singletonList(outputTopic));
        
        // Create a latch to wait for message
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<PipeStream> receivedStream = new AtomicReference<>();
        
        // Start consumer in background
        Thread consumerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, byte[]> records = kafkaConsumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, byte[]> record : records) {
                        try {
                            PipeStream stream = PipeStream.parseFrom(record.value());
                            receivedStream.set(stream);
                            messageLatch.countDown();
                            LOG.info("Received message on Kafka topic: {}", stream.getStreamId());
                        } catch (Exception e) {
                            LOG.error("Failed to parse PipeStream", e);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Consumer error", e);
            }
        });
        consumerThread.start();
        
        // Wait for consumer to be ready
        Thread.sleep(2000);
        
        // Send document through pipeline
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-kafka-doc")
                .setTitle("Kafka Routing Test")
                .setBody("This document should be routed to Kafka.")
                .build();
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId("kafka-test-" + UUID.randomUUID())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("tika-parser")
                .setCurrentHopNumber(0)
                .setDocument(document)
                .build();
        
        engineStub.processPipeAsync(pipeStream);
        
        // Wait for message
        boolean received = messageLatch.await(30, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        
        // Verify received message
        PipeStream received = receivedStream.get();
        assertThat(received).isNotNull();
        assertThat(received.getDocument().getId()).isEqualTo("test-kafka-doc");
        
        // Cleanup
        consumerThread.interrupt();
        consumerThread.join(5000);
    }
    
    @Test
    @DisplayName("Should handle custom Kafka topic names")
    void testCustomKafkaTopicRouting() {
        // This test would verify that custom topic names work
        // when configured in the pipeline configuration
        LOG.info("Custom Kafka topic test - implementation pending");
    }
    
    @Test
    @DisplayName("Should test gRPC service health")
    void testGrpcHealth() {
        // Test health check for both engine and module
        io.grpc.health.v1.HealthGrpc.HealthBlockingStub engineHealthStub = 
                io.grpc.health.v1.HealthGrpc.newBlockingStub(engineChannel);
        
        io.grpc.health.v1.HealthGrpc.HealthBlockingStub tikaHealthStub = 
                io.grpc.health.v1.HealthGrpc.newBlockingStub(tikaChannel);
        
        // Check engine health
        io.grpc.health.v1.HealthCheckRequest engineRequest = 
                io.grpc.health.v1.HealthCheckRequest.newBuilder().build();
        io.grpc.health.v1.HealthCheckResponse engineResponse = engineHealthStub.check(engineRequest);
        assertThat(engineResponse.getStatus()).isEqualTo(
                io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
        
        // Check Tika health
        io.grpc.health.v1.HealthCheckRequest tikaRequest = 
                io.grpc.health.v1.HealthCheckRequest.newBuilder().build();
        io.grpc.health.v1.HealthCheckResponse tikaResponse = tikaHealthStub.check(tikaRequest);
        assertThat(tikaResponse.getStatus()).isEqualTo(
                io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
        
        LOG.info("Both gRPC services are healthy");
    }
}