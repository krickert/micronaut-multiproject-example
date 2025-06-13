package com.krickert.yappy.modules.testmodule;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.UUIDSerializer;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
@GrpcService
@Requires(property = "grpc.services.test-module.enabled", value = "true", defaultValue = "true")
public class TestModuleService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(TestModuleService.class);
    private final AtomicLong outputCounter = new AtomicLong(0);
    
    @Property(name = "kafka.enabled", defaultValue = "false")
    private boolean kafkaEnabled;
    
    //@Property(name = "kafka.bootstrap.servers", defaultValue = "kafka:9092")
    private String kafkaBootstrapServers = "kafka:9092";
    
    @Property(name = "apicurio.registry.url", defaultValue = "http://localhost:8080/apis/registry/v3")
    private String apicurioRegistryUrl;
    
    private Producer<UUID, PipeStream> kafkaProducer;
    
    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        ServiceMetadata metadata = request.getMetadata();
        ProcessConfiguration config = request.getConfig();
        PipeDoc document = request.getDocument();

        LOG.info("TestModuleService received request for pipeline: {}, step: {}, doc: {}",
                metadata.getPipelineName(), metadata.getPipeStepName(), document.getId());

        String streamId = metadata.getStreamId();
        String docId = document.getId();
        
        // Parse configuration
        OutputConfig outputConfig = parseOutputConfig(config.getCustomJsonConfig());
        
        LOG.debug("Output configuration: type={}, target={}", 
                outputConfig.outputType, outputConfig.target);

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        responseBuilder.setSuccess(true);

        try {
            // Output based on configuration
            switch (outputConfig.outputType) {
                case KAFKA:
                    outputToKafka(document, outputConfig.target, streamId);
                    responseBuilder.addProcessorLogs(
                        String.format("TestModule output document %s to Kafka topic: %s", 
                                    docId, outputConfig.target));
                    break;
                    
                case FILE:
                    String filename = outputToFile(document, outputConfig.target, streamId);
                    responseBuilder.addProcessorLogs(
                        String.format("TestModule output document %s to file: %s", 
                                    docId, filename));
                    break;
                    
                case CONSOLE:
                default:
                    outputToConsole(document, streamId);
                    responseBuilder.addProcessorLogs(
                        String.format("TestModule output document %s to console", docId));
                    break;
            }
            
            // Echo the document back (like echo service)
            responseBuilder.setOutputDoc(document);
            
        } catch (Exception e) {
            LOG.error("Error processing document {}: {}", docId, e.getMessage(), e);
            responseBuilder.setSuccess(false);
            responseBuilder.setErrorDetails(
                Struct.newBuilder()
                    .putFields("error", Value.newBuilder()
                        .setStringValue(e.getMessage())
                        .build())
                    .build()
            );
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void getServiceRegistration(Empty request, StreamObserver<ServiceRegistrationData> responseObserver) {
        String jsonSchema = """
            {
              "type": "object",
              "properties": {
                "output_type": {
                  "type": "string",
                  "enum": ["CONSOLE", "KAFKA", "FILE"],
                  "default": "CONSOLE",
                  "description": "Where to output the processed documents"
                },
                "kafka_topic": {
                  "type": "string",
                  "description": "Kafka topic name (required when output_type is KAFKA)"
                },
                "file_path": {
                  "type": "string",
                  "description": "Directory path for output files (required when output_type is FILE)"
                },
                "file_prefix": {
                  "type": "string",
                  "default": "pipedoc",
                  "description": "Prefix for output filenames"
                }
              },
              "additionalProperties": false
            }
            """;

        ServiceRegistrationData response = ServiceRegistrationData.newBuilder()
                .setModuleName("test-module")
                .setJsonConfigSchema(jsonSchema)
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    private OutputConfig parseOutputConfig(Struct customConfig) {
        OutputConfig config = new OutputConfig();
        
        if (customConfig != null && customConfig.getFieldsCount() > 0) {
            Value outputTypeValue = customConfig.getFieldsOrDefault("output_type", null);
            if (outputTypeValue != null && outputTypeValue.hasStringValue()) {
                try {
                    config.outputType = OutputType.valueOf(outputTypeValue.getStringValue());
                } catch (IllegalArgumentException e) {
                    LOG.warn("Invalid output_type: {}, defaulting to CONSOLE", 
                            outputTypeValue.getStringValue());
                }
            }
            
            Value kafkaTopicValue = customConfig.getFieldsOrDefault("kafka_topic", null);
            if (kafkaTopicValue != null && kafkaTopicValue.hasStringValue()) {
                config.target = kafkaTopicValue.getStringValue();
            }
            
            Value filePathValue = customConfig.getFieldsOrDefault("file_path", null);
            if (filePathValue != null && filePathValue.hasStringValue()) {
                config.target = filePathValue.getStringValue();
            }
            
            Value filePrefixValue = customConfig.getFieldsOrDefault("file_prefix", null);
            if (filePrefixValue != null && filePrefixValue.hasStringValue()) {
                config.filePrefix = filePrefixValue.getStringValue();
            }
        }
        
        return config;
    }
    
    private void outputToConsole(PipeDoc document, String streamId) {
        System.out.println("=== TestModule Console Output ===");
        System.out.println("Stream ID: " + streamId);
        System.out.println("Document ID: " + document.getId());
        System.out.println("Title: " + document.getTitle());
        System.out.println("Body: " + document.getBody());
        if (document.hasBlob()) {
            System.out.println("Blob ID: " + document.getBlob().getBlobId());
            System.out.println("Blob Filename: " + document.getBlob().getFilename());
            System.out.println("Blob Size: " + document.getBlob().getData().size() + " bytes");
        }
        System.out.println("Custom Data: " + document.getCustomData());
        System.out.println("================================");
    }
    
    private void outputToKafka(PipeDoc document, String topic, String streamId) {
        if (!kafkaEnabled) {
            throw new RuntimeException("Kafka is not enabled. Set KAFKA_ENABLED=true to use Kafka output.");
        }
        
        if (kafkaProducer == null) {
            initializeKafkaProducer();
        }
        
        String effectiveTopic = (topic != null && !topic.isEmpty()) ? topic : "test-module-output";
        
        // Create PipeStream from the document
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(document)
                .setCurrentPipelineName("test-module-output")
                .setTargetStepName("output")
                .setCurrentHopNumber(1)
                .build();
        
        // Generate UUID for key
        UUID key = UUID.randomUUID();
        
        ProducerRecord<UUID, PipeStream> record = new ProducerRecord<>(effectiveTopic, key, pipeStream);
        
        try {
            kafkaProducer.send(record).get(); // Synchronous send for testing
            LOG.info("Sent document {} as PipeStream with key {} to Kafka topic: {}", document.getId(), key, effectiveTopic);
        } catch (Exception e) {
            LOG.error("Failed to send message to Kafka", e);
            throw new RuntimeException("Failed to send message to Kafka", e);
        }
    }
    
    private String outputToFile(PipeDoc document, String outputPath, String streamId) throws IOException {
        String effectivePath = (outputPath != null && !outputPath.isEmpty()) 
            ? outputPath 
            : System.getProperty("java.io.tmpdir");
            
        Path directory = Paths.get(effectivePath);
        Files.createDirectories(directory);
        
        long counter = outputCounter.incrementAndGet();
        String filename = String.format("pipedoc-%s-%03d.bin", streamId, counter);
        Path filePath = directory.resolve(filename);
        
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            document.writeTo(fos);
        }
        
        LOG.info("Wrote document {} to file: {}", document.getId(), filePath);
        return filePath.toString();
    }
    
    private enum OutputType {
        CONSOLE,
        KAFKA,
        FILE
    }
    
    private static class OutputConfig {
        OutputType outputType = OutputType.CONSOLE;
        String target = "";
        String filePrefix = "pipedoc";
    }
    
    private synchronized void initializeKafkaProducer() {
        if (kafkaProducer != null) {
            return;
        }
        
        Properties props = new Properties();
        
        // Configure kafka settings
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "test-module-producer");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ProtobufKafkaSerializer.class.getName());
        
        // Configure Apicurio Registry
        props.put(SerdeConfig.REGISTRY_URL, apicurioRegistryUrl);
        props.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, Boolean.TRUE);
        props.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        props.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, "test-capture");
        
        LOG.info("Initializing Kafka producer with bootstrap servers: {} and Apicurio Registry: {}", 
                kafkaBootstrapServers, apicurioRegistryUrl);
        
        kafkaProducer = new KafkaProducer<>(props);
    }
    
    @PreDestroy
    public void cleanup() {
        if (kafkaProducer != null) {
            LOG.info("Closing Kafka producer");
            kafkaProducer.close();
        }
    }
}