package com.krickert.yappy.integration;

import com.google.protobuf.Empty;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.grpc.PipeStreamEngineGrpc;
import com.krickert.search.model.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for multiple containerized engines working together.
 * Tests a full pipeline: Tika -> Chunker with proper port separation.
 */
@MicronautTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MultiEngineIntegrationTest implements TestPropertyProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(MultiEngineIntegrationTest.class);
    
    private static final Network NETWORK = Network.newNetwork();
    
    // Engine + Tika container (ports: 8080, 50051, 50052)
    @Container
    private static final GenericContainer<?> ENGINE_TIKA = new GenericContainer<>("localhost:5000/yappy/engine-tika-parser:latest")
            .withNetwork(NETWORK)
            .withNetworkAliases("engine-tika")
            .withExposedPorts(8080, 50051, 50052)
            .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
            .withEnv("CONSUL_HOST", "consul-testresources")
            .withEnv("CONSUL_PORT", "8500")
            .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka-testresources:9092")
            .withEnv("APICURIO_REGISTRY_URL", "http://apicurio-testresources:8080")
            .withEnv("YAPPY_ENGINE_NAME", "engine-tika")
            .waitingFor(Wait.forHttp("/health").forPort(8080))
            .withStartupTimeout(Duration.ofMinutes(2));
    
    // Engine + Chunker container (ports: 8080, 50051, 50053)
    @Container
    private static final GenericContainer<?> ENGINE_CHUNKER = new GenericContainer<>("localhost:5000/yappy/engine-chunker:latest")
            .withNetwork(NETWORK)
            .withNetworkAliases("engine-chunker")
            .withExposedPorts(8080, 50051, 50053)  // Note: 50053 for chunker
            .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
            .withEnv("CONSUL_HOST", "consul-testresources")
            .withEnv("CONSUL_PORT", "8500")
            .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka-testresources:9092")
            .withEnv("APICURIO_REGISTRY_URL", "http://apicurio-testresources:8080")
            .withEnv("YAPPY_ENGINE_NAME", "engine-chunker")
            .waitingFor(Wait.forHttp("/health").forPort(8080))
            .withStartupTimeout(Duration.ofMinutes(2));
    
    @Inject
    ConsulBusinessOperationsService consulService;
    
    private ManagedChannel tikaEngineChannel;
    private ManagedChannel chunkerEngineChannel;
    private PipeStreamEngineGrpc.PipeStreamEngineBlockingStub tikaEngineStub;
    private PipeStreamEngineGrpc.PipeStreamEngineBlockingStub chunkerEngineStub;
    private KafkaConsumer<String, byte[]> kafkaConsumer;
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "consul.client.host", "localhost",
                "consul.client.port", String.valueOf(getConsulPort()),
                "kafka.bootstrap.servers", "localhost:" + getKafkaPort()
        );
    }
    
    @BeforeAll
    void setup() {
        // Create gRPC channels
        tikaEngineChannel = ManagedChannelBuilder
                .forAddress("localhost", ENGINE_TIKA.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        chunkerEngineChannel = ManagedChannelBuilder
                .forAddress("localhost", ENGINE_CHUNKER.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        tikaEngineStub = PipeStreamEngineGrpc.newBlockingStub(tikaEngineChannel);
        chunkerEngineStub = PipeStreamEngineGrpc.newBlockingStub(chunkerEngineChannel);
        
        // Setup Kafka consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + getKafkaPort());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        kafkaConsumer = new KafkaConsumer<>(props);
    }
    
    @AfterAll
    void tearDown() {
        if (tikaEngineChannel != null) {
            tikaEngineChannel.shutdown();
        }
        if (chunkerEngineChannel != null) {
            chunkerEngineChannel.shutdown();
        }
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
    
    @Test
    @DisplayName("Both engines should register their modules with correct port separation")
    void testModuleRegistration() throws Exception {
        // Wait for all services to register
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var services = consulService.getAllServicesFromCatalog();
                    assertThat(services).containsKeys(
                            "engine-tika",
                            "engine-chunker",
                            "tika-parser",
                            "chunker"
                    );
                });
        
        // Verify Tika module on port 50052
        var tikaServices = consulService.getHealthyServiceInstances("tika-parser");
        assertThat(tikaServices).isNotEmpty();
        assertThat(tikaServices.get(0).getPort()).isEqualTo(50052);
        
        // Verify Chunker module on port 50053
        var chunkerServices = consulService.getHealthyServiceInstances("chunker");
        assertThat(chunkerServices).isNotEmpty();
        assertThat(chunkerServices.get(0).getPort()).isEqualTo(50053);
        
        LOG.info("All modules registered with proper port separation");
    }
    
    @Test
    @DisplayName("Should process document through multi-engine pipeline: Tika -> Chunker")
    void testMultiEnginePipeline() throws Exception {
        // Create pipeline configuration
        PipelineStepConfig tikaStep = PipelineStepConfig.builder()
                .stepName("tika-parser")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("tika-parser")
                        .build())
                .outputs(Map.of("parsed", PipelineStepConfig.OutputTarget.builder()
                        .targetStepName("chunker")
                        .transportType(TransportType.KAFKA)
                        .kafkaTransport(KafkaTransportConfig.builder()
                                .topic("pipeline.multi.tika.output")
                                .build())
                        .build()))
                .build();
        
        PipelineStepConfig chunkerStep = PipelineStepConfig.builder()
                .stepName("chunker")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("chunker")
                        .build())
                .outputs(Map.of("chunked", PipelineStepConfig.OutputTarget.builder()
                        .targetStepName("output")
                        .transportType(TransportType.KAFKA)
                        .kafkaTransport(KafkaTransportConfig.builder()
                                .topic("pipeline.multi.chunker.output")
                                .build())
                        .build()))
                .build();
        
        PipelineStepConfig outputStep = PipelineStepConfig.builder()
                .stepName("output")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        PipelineConfig pipelineConfig = new PipelineConfig(
                "multi-engine-pipeline",
                Map.of(
                        "tika-parser", tikaStep,
                        "chunker", chunkerStep,
                        "output", outputStep
                )
        );
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("multi-engine-pipeline", pipelineConfig))
                .build();
        
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(
                "tika-parser", new PipelineModuleConfiguration(
                        "Tika Parser Module",
                        "tika-parser",
                        null,
                        null
                ),
                "chunker", new PipelineModuleConfiguration(
                        "Chunker Module",
                        "chunker",
                        null,
                        null
                ),
                "dummy-sink", new PipelineModuleConfiguration(
                        "Dummy Sink",
                        "dummy-sink",
                        null,
                        null
                )
        ));
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of(
                        "pipeline.multi.tika.output",
                        "pipeline.multi.chunker.output"
                ))
                .allowedGrpcServices(Set.of("tika-parser", "chunker", "dummy-sink"))
                .build();
        
        // Store configuration
        consulService.seedClusterConfiguration("test-cluster", clusterConfig);
        
        // Wait for engines to load configuration
        Thread.sleep(3000);
        
        // Subscribe to output topics
        kafkaConsumer.subscribe(Arrays.asList(
                "pipeline.multi.tika.output",
                "pipeline.multi.chunker.output"
        ));
        
        // Create test document with substantial content for chunking
        String longContent = String.join(" ", Collections.nCopies(100,
                "This is a test sentence that will be processed by Tika and then chunked."));
        
        PipeDoc document = PipeDoc.newBuilder()
                .setId("multi-test-" + UUID.randomUUID())
                .setTitle("Multi-Engine Test Document")
                .setBody(longContent)
                .putMetadata("source", "multi-engine-test")
                .build();
        
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId("multi-stream-" + UUID.randomUUID())
                .setCurrentPipelineName("multi-engine-pipeline")
                .setTargetStepName("tika-parser")
                .setCurrentHopNumber(0)
                .setDocument(document)
                .build();
        
        // Process through first engine
        Empty response = tikaEngineStub.processPipeAsync(pipeStream);
        assertThat(response).isNotNull();
        
        // Collect messages from both topics
        CountDownLatch messagesReceived = new CountDownLatch(2);
        List<PipeStream> receivedStreams = new ArrayList<>();
        
        Thread consumerThread = new Thread(() -> {
            try {
                while (messagesReceived.getCount() > 0) {
                    ConsumerRecords<String, byte[]> records = kafkaConsumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, byte[]> record : records) {
                        try {
                            PipeStream stream = PipeStream.parseFrom(record.value());
                            receivedStreams.add(stream);
                            LOG.info("Received message on topic {} with stream ID {}", 
                                    record.topic(), stream.getStreamId());
                            messagesReceived.countDown();
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
        
        // Wait for messages
        boolean received = messagesReceived.await(30, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        
        // Verify we got messages from both stages
        assertThat(receivedStreams).hasSize(2);
        
        // Verify Tika processing
        PipeStream tikaOutput = receivedStreams.stream()
                .filter(s -> s.getDocument().getMetadataMap().containsKey("tika_parsed"))
                .findFirst()
                .orElseThrow();
        assertThat(tikaOutput.getDocument().getMetadataMap()).containsKey("tika_parsed");
        
        // Verify Chunker processing
        PipeStream chunkerOutput = receivedStreams.stream()
                .filter(s -> s.getDocument().getMetadataMap().containsKey("chunk_count"))
                .findFirst()
                .orElseThrow();
        assertThat(chunkerOutput.getDocument().getMetadataMap()).containsKey("chunk_count");
        
        consumerThread.interrupt();
        consumerThread.join(5000);
        
        LOG.info("Successfully processed document through multi-engine pipeline");
    }
    
    @Test
    @DisplayName("Engines should handle cross-container gRPC communication")
    void testCrossContainerCommunication() throws Exception {
        // This would test direct gRPC calls between modules in different containers
        // For now, we're using Kafka for inter-engine communication
        LOG.info("Cross-container gRPC communication test - reserved for future implementation");
    }
    
    private Integer getConsulPort() {
        return Integer.parseInt(System.getProperty("consul.port", "8500"));
    }
    
    private Integer getKafkaPort() {
        return Integer.parseInt(System.getProperty("kafka.port", "9092"));
    }
}