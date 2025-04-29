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
         * @return the PipelineServiceProcessor
         */
        @Bean
        @Singleton
        @Named("testPipelineServiceProcessor")
        @io.micronaut.context.annotation.Replaces(bean = ChunkerPipelineServiceProcessor.class)
        public PipelineServiceProcessor testPipelineServiceProcessor(
                OverlapChunker chunker) {
            return new ChunkerPipelineServiceProcessor(chunker);
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
                        "The chunker service can also be configured to use different fields from the PipeDoc. " +
                        "If no field is specified, it will use the PipeDoc body by default. " +
                        "This text should be long enough to be split into at least 2-3 chunks for testing purposes.")
                .build();

        // Add configuration to the PipeRequest
        PipeRequest modifiedRequest = originalStream.getRequest().toBuilder()
                .setDoc(modifiedDoc)
                .putConfig("chunkSize", "100")
                .putConfig("overlapSize", "20")
                .build();

        // Create a new PipeStream with the modified PipeRequest
        return originalStream.toBuilder()
                .setRequest(modifiedRequest)
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
     * Test chunk-field parameter from PipeRequest config.
     * This tests the new functionality to use a specific field for chunking.
     */
    @Test
    public void testChunkFieldFromPipeRequest() {
        // Get the input PipeStream
        PipeStream originalStream = getInput();

        // Create a PipeDoc with custom data field to chunk
        PipeDoc originalDoc = originalStream.getRequest().getDoc();
        String descriptionText = "This is a description in custom_data that will be chunked instead of the body";

        // Add a description field to custom_data
        com.google.protobuf.Struct.Builder customDataBuilder = com.google.protobuf.Struct.newBuilder();
        if (originalDoc.hasCustomData()) {
            customDataBuilder.mergeFrom(originalDoc.getCustomData());
        }
        customDataBuilder.putFields("description", 
            com.google.protobuf.Value.newBuilder().setStringValue(descriptionText).build());

        PipeDoc modifiedDoc = originalDoc.toBuilder()
                .setCustomData(customDataBuilder.build())
                .build();

        // Add chunk-field parameter and other config to the PipeRequest config
        PipeRequest modifiedRequest = originalStream.getRequest().toBuilder()
                .setDoc(modifiedDoc)
                .putConfig("chunk-field", "description")
                .putConfig("chunkSize", "100")
                .putConfig("overlapSize", "20")
                .build();

        // Create a new PipeStream with the modified PipeRequest
        PipeStream modifiedStream = originalStream.toBuilder()
                .setRequest(modifiedRequest)
                .build();

        // Process the modified PipeStream
        PipeDoc processedDoc = pipelineServiceProcessor.process(modifiedStream).getPipeDoc();

        // Verify that the document has a SemanticDoc with chunks
        assertTrue(processedDoc.hasChunkEmbeddings(), "Processed document should have chunk embeddings");
        SemanticDoc semanticDoc = processedDoc.getChunkEmbeddings();

        // Verify that the SemanticDoc has chunks
        assertFalse(semanticDoc.getChunksList().isEmpty(), "SemanticDoc should have chunks");

        // Verify that the parent field is set to "description"
        assertEquals("description", semanticDoc.getParentField(), "Parent field should be 'description'");

        // Verify that the chunk config ID includes the field name
        assertTrue(semanticDoc.getChunkConfigId().startsWith("description-"), 
                "Chunk config ID should start with 'description-'");

        // Verify that each chunk has text from the description
        for (SemanticChunk chunk : semanticDoc.getChunksList()) {
            assertTrue(chunk.hasEmbedding(), "Chunk should have embedding");
            String chunkText = chunk.getEmbedding().getEmbeddingText();
            assertFalse(chunkText.isEmpty(), "Chunk should have text");

            // Print debug information
            System.out.println("[DEBUG_LOG] Description text: " + descriptionText);
            System.out.println("[DEBUG_LOG] Chunk text: " + chunkText);

            // Check if the chunk text contains the essential parts of the description text
            // This is more flexible than an exact match
            boolean containsEssentialParts = chunkText.contains("description") && 
                                            chunkText.contains("chunked") && 
                                            chunkText.contains("instead of the body");

            System.out.println("[DEBUG_LOG] Contains essential parts check: " + containsEssentialParts);

            assertTrue(containsEssentialParts, 
                    "Chunk text should contain essential parts of the description field");
            LOG.info("[DEBUG_LOG] Chunk {}: {}", chunk.getChunkNumber(), chunkText);
        }
    }

    /**
     * Test chunk-field parameter with a first-class field.
     * This tests using a standard PipeDoc field for chunking.
     */
    @Test
    public void testChunkFieldWithFirstClassField() {
        // Get the input PipeStream
        PipeStream originalStream = getInput();

        // Create a PipeDoc with a title field to chunk
        PipeDoc originalDoc = originalStream.getRequest().getDoc();
        String titleText = "This is a title that will be chunked directly using chunk-field";
        PipeDoc modifiedDoc = originalDoc.toBuilder()
                .setTitle(titleText)
                .build();

        // Add chunk-field parameter and other config to the PipeRequest config
        PipeRequest modifiedRequest = originalStream.getRequest().toBuilder()
                .setDoc(modifiedDoc)
                .putConfig("chunk-field", "title")
                .putConfig("chunkSize", "100")
                .putConfig("overlapSize", "20")
                .build();

        // Create a new PipeStream with the modified PipeRequest
        PipeStream modifiedStream = originalStream.toBuilder()
                .setRequest(modifiedRequest)
                .build();

        // Process the modified PipeStream
        PipeDoc processedDoc = pipelineServiceProcessor.process(modifiedStream).getPipeDoc();

        // Verify that the document has a SemanticDoc with chunks
        assertTrue(processedDoc.hasChunkEmbeddings(), "Processed document should have chunk embeddings");
        SemanticDoc semanticDoc = processedDoc.getChunkEmbeddings();

        // Verify that the SemanticDoc has chunks
        assertFalse(semanticDoc.getChunksList().isEmpty(), "SemanticDoc should have chunks");

        // Verify that the parent field is set to "title"
        assertEquals("title", semanticDoc.getParentField(), "Parent field should be 'title'");

        // Verify that the chunk config ID includes the field name
        assertTrue(semanticDoc.getChunkConfigId().startsWith("title-"), 
                "Chunk config ID should start with 'title-'");

        // Verify that each chunk has text from the title
        for (SemanticChunk chunk : semanticDoc.getChunksList()) {
            assertTrue(chunk.hasEmbedding(), "Chunk should have embedding");
            String chunkText = chunk.getEmbedding().getEmbeddingText();
            assertFalse(chunkText.isEmpty(), "Chunk should have text");

            // Print debug information
            System.out.println("[DEBUG_LOG] Title text: " + titleText);
            System.out.println("[DEBUG_LOG] Chunk text: " + chunkText);

            // Check if the chunk text contains the essential parts of the title text
            // This is more flexible than an exact match
            boolean containsEssentialParts = chunkText.contains("title") && 
                                            chunkText.contains("chunked") && 
                                            chunkText.contains("directly");

            System.out.println("[DEBUG_LOG] Contains essential parts check: " + containsEssentialParts);

            assertTrue(containsEssentialParts, 
                    "Chunk text should contain essential parts of the title field");
            LOG.info("[DEBUG_LOG] Chunk {}: {}", chunk.getChunkNumber(), chunkText);
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
