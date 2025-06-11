package com.krickert.yappy.modules.testmodule;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
@GrpcService
@Requires(property = "grpc.services.test-module.enabled", value = "true", defaultValue = "true")
public class TestModuleService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(TestModuleService.class);
    private final AtomicLong outputCounter = new AtomicLong(0);
    
    @Inject
    @Nullable
    private TestModuleKafkaProducer kafkaProducer;
    
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
        if (kafkaProducer == null) {
            throw new RuntimeException("Kafka producer not available. Ensure Kafka is configured.");
        }
        
        String effectiveTopic = (topic != null && !topic.isEmpty()) ? topic : "test-module-output";
        kafkaProducer.send(effectiveTopic, document.getId(), document);
        LOG.info("Sent document {} to Kafka topic: {}", document.getId(), effectiveTopic);
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
}

@KafkaClient
@Requires(property = "kafka.enabled", value = "true")
interface TestModuleKafkaProducer {
    void send(@Topic String topic, @KafkaKey String key, PipeDoc document);
}