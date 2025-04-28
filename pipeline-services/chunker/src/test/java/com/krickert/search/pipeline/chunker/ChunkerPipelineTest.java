package com.krickert.search.pipeline.chunker;

import com.krickert.search.chunker.OverlapChunker;
import com.krickert.search.model.*;
import com.krickert.search.model.mapper.ProtoMapper;
import com.krickert.search.pipeline.config.PipelineConfig;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import com.krickert.search.test.platform.AbstractPipelineTest;
import com.krickert.search.test.platform.proto.PipeDocExample;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the ChunkerPipelineServiceProcessor.
 * Tests both gRPC and Kafka input methods.
 */
@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ChunkerPipelineTest extends AbstractPipelineTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ChunkerPipelineTest.class);

    /**
     * Factory for creating test beans.
     */
    @Factory
    public static class TestBeanFactory {

        /**
         * Create a ProtoMapper bean for testing.
         * 
         * @return the ProtoMapper
         */
        @Bean
        @Singleton
        public ProtoMapper protoMapper() {
            return new ProtoMapper();
        }

        /**
         * Create a ServiceConfiguration bean for testing.
         * 
         * @return the ServiceConfiguration
         */
        @Bean
        @Singleton
        public ServiceConfiguration serviceConfiguration() {
            ServiceConfiguration config = new ServiceConfiguration("chunker-service");
            config.setKafkaListenTopics(List.of(TOPIC_IN));
            config.setKafkaPublishTopics(List.of(TOPIC_OUT));
            config.setGrpcForwardTo(List.of("null"));
            config.setServiceImplementation("chunker-service");

            // Set config parameters
            Map<String, String> configParams = new HashMap<>();
            configParams.put("chunkSize", "100");
            configParams.put("overlapSize", "20");
            config.setConfigParams(configParams);

            return config;
        }

        /**
         * Create a test PipelineServiceProcessor bean for testing.
         * 
         * @param chunker the OverlapChunker
         * @param protoMapper the ProtoMapper
         * @param serviceConfiguration the ServiceConfiguration
         * @return the PipelineServiceProcessor
         */
        @Bean
        @Singleton
        @Named("testPipelineServiceProcessor")
        @io.micronaut.context.annotation.Replaces(bean = ChunkerPipelineServiceProcessor.class)
        public PipelineServiceProcessor testPipelineServiceProcessor(
                OverlapChunker chunker,
                ProtoMapper protoMapper,
                ServiceConfiguration serviceConfiguration) {
            return new ChunkerPipelineServiceProcessor(chunker, protoMapper, serviceConfiguration);
        }

        @Bean
        @Singleton
        @Named("chunker-pipeline")
        @io.micronaut.context.annotation.Replaces(bean = PipelineConfig.class, named = "chunker-pipeline")
        public PipelineConfig pipelineConfig() {
            PipelineConfig config = new PipelineConfig("chunker-pipeline");
            Map<String, ServiceConfiguration> serviceMap = new HashMap<>();

            ServiceConfiguration serviceConfig = new ServiceConfiguration("chunker-service");
            serviceConfig.setKafkaListenTopics(List.of(TOPIC_IN));
            serviceConfig.setKafkaPublishTopics(List.of(TOPIC_OUT));
            serviceConfig.setGrpcForwardTo(List.of("null"));
            serviceConfig.setServiceImplementation("chunker-service");

            // Set config parameters
            Map<String, String> configParams = new HashMap<>();
            configParams.put("chunkSize", "100");
            configParams.put("overlapSize", "20");
            serviceConfig.setConfigParams(configParams);

            serviceMap.put("chunker-service", serviceConfig);
            config.setService(serviceMap);

            return config;
        }
    }

    /**
     * Get the input PipeStream for testing.
     * 
     * @return the input PipeStream
     */
    @Override
    protected PipeStream getInput() {
        // Use the example PipeStream from PipeDocExample
        PipeStream originalStream = PipeDocExample.createFullPipeStream();

        // Modify the PipeDoc to have a longer body for chunking
        PipeDoc originalDoc = originalStream.getRequest().getDoc();
        PipeDoc modifiedDoc = originalDoc.toBuilder()
                .setBody("This is a longer text that will be chunked into multiple parts. " +
                        "The chunker service will split this text into smaller chunks with overlapping sections. " +
                        "Each chunk will be stored in the SemanticDoc structure with a unique ID. " +
                        "The chunk size and overlap size can be configured in the application.properties file. " +
                        "By default, the chunk size is 500 characters and the overlap size is 50 characters. " +
                        "The chunker service can also be configured to use different fields from the PipeDoc using mappings. " +
                        "If no mapping is defined, it will use the PipeDoc body by default. " +
                        "This text should be long enough to be split into at least 2-3 chunks for testing purposes.")
                .build();

        // Create a new PipeStream with the modified PipeDoc
        return originalStream.toBuilder()
                .setRequest(originalStream.getRequest().toBuilder().setDoc(modifiedDoc).build())
                .build();
    }

    /**
     * Get the expected output PipeResponse after processing.
     * 
     * @return the expected PipeResponse
     */
    @Override
    protected PipeResponse getExpectedOutput() {
        // We expect a success response
        return PipeResponse.newBuilder()
                .setSuccess(true)
                .build();
    }

    /**
     * Get the expected output PipeDoc after processing.
     * 
     * @return the expected PipeDoc
     */
    @Override
    protected PipeDoc getExpectedPipeDoc() {
        // Get the original PipeDoc from the input
        PipeDoc originalDoc = getInput().getRequest().getDoc();

        // We expect the original doc with a SemanticDoc containing chunks
        // The exact chunks will depend on the chunking algorithm and configuration
        // So we'll just check that the SemanticDoc exists and has chunks in the test methods
        return originalDoc;
    }

    /**
     * Override testGrpcInput to add specific assertions for this test case.
     */
    @Override
    @Test
    public void testGrpcInput() throws Exception {
        // Call the parent test method
        super.testGrpcInput();

        // Get the input and process it again for additional assertions
        PipeStream input = getInput();
        PipeDoc processedDoc = pipelineServiceProcessor.process(input).getPipeDoc();

        // Verify that the document has a SemanticDoc with chunks
        assertTrue(processedDoc.hasChunkEmbeddings(), "Processed document should have chunk embeddings");
        SemanticDoc semanticDoc = processedDoc.getChunkEmbeddings();

        // Verify that the SemanticDoc has chunks
        assertFalse(semanticDoc.getChunksList().isEmpty(), "SemanticDoc should have chunks");

        // Verify that each chunk has text
        for (SemanticChunk chunk : semanticDoc.getChunksList()) {
            assertTrue(chunk.hasEmbedding(), "Chunk should have embedding");
            assertFalse(chunk.getEmbedding().getEmbeddingText().isEmpty(), "Chunk should have text");
            LOG.info("[DEBUG_LOG] Chunk {}: {}", chunk.getChunkNumber(), chunk.getEmbedding().getEmbeddingText());
        }
    }

    /**
     * Override testKafkaInput to add specific assertions for this test case.
     */
    @Override
    @Test
    public void testKafkaInput() throws Exception {
        // Call the parent test method
        super.testKafkaInput();

        // Get the processed message for additional assertions
        PipeStream processedMessage = consumer.getReceivedMessages().get(0);
        PipeDoc processedDoc = processedMessage.getRequest().getDoc();

        // Verify that the document has a SemanticDoc with chunks
        assertTrue(processedDoc.hasChunkEmbeddings(), "Processed document should have chunk embeddings");
        SemanticDoc semanticDoc = processedDoc.getChunkEmbeddings();

        // Verify that the SemanticDoc has chunks
        assertFalse(semanticDoc.getChunksList().isEmpty(), "SemanticDoc should have chunks");

        // Verify that each chunk has text
        for (SemanticChunk chunk : semanticDoc.getChunksList()) {
            assertTrue(chunk.hasEmbedding(), "Chunk should have embedding");
            assertFalse(chunk.getEmbedding().getEmbeddingText().isEmpty(), "Chunk should have text");
            LOG.info("[DEBUG_LOG] Chunk {}: {}", chunk.getChunkNumber(), chunk.getEmbedding().getEmbeddingText());
        }
    }

    /**
     * Provide test-specific properties.
     */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties());

        // Configure the chunker service for testing
        properties.put("pipeline.configs.chunker-pipeline.service.chunker-service.config-params.chunkSize", "100");
        properties.put("pipeline.configs.chunker-pipeline.service.chunker-service.config-params.overlapSize", "20");

        // Configure Kafka topics
        properties.put("pipeline.configs.chunker-pipeline.service.chunker-service.kafka-listen-topics", TOPIC_IN);
        properties.put("pipeline.configs.chunker-pipeline.service.chunker-service.kafka-publish-topics", TOPIC_OUT);

        // Add Kafka consumer group for the test

        // Add Apicurio Registry properties
        properties.put("kafka.bootstrap.servers", "localhost:9092");

        return properties;
    }
}
