package com.krickert.search.pipeline.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultPipelineConfigTest {

    private DefaultPipelineConfig defaultPipelineConfig;

    @BeforeEach
    void setUp() {
        defaultPipelineConfig = new DefaultPipelineConfig();
    }

    @Test
    void testInitWithNoPropertiesFile() {
        // Test init when no properties file exists
        defaultPipelineConfig.init();

        // Verify that pipelines map is empty
        assertTrue(defaultPipelineConfig.getPipelines().isEmpty());
    }

    @Test
    void testSetAndGetPipelines() {
        // Setup
        Map<String, PipelineConfig> pipelines = Map.of(
            "pipeline1", new PipelineConfig("pipeline1"),
            "pipeline2", new PipelineConfig("pipeline2")
        );

        // Test
        defaultPipelineConfig.setPipelines(pipelines);

        // Verify
        assertEquals(pipelines, defaultPipelineConfig.getPipelines());
        assertEquals(2, defaultPipelineConfig.getPipelines().size());
        assertTrue(defaultPipelineConfig.getPipelines().containsKey("pipeline1"));
        assertTrue(defaultPipelineConfig.getPipelines().containsKey("pipeline2"));
    }

    @Test
    void testParsePipelineProperties(@TempDir Path tempDir) throws IOException {
        // Create a test properties file
        File propsFile = tempDir.resolve("pipeline.default.properties").toFile();
        try (FileWriter writer = new FileWriter(propsFile)) {
            writer.write("# ========== IMPORTER (ENTRY POINT) ==========\n");
            writer.write("pipeline.configs.pipeline1.service.tika-parser.kafka-publish-topics=tika-documents\n");
            writer.write("\n");
            writer.write("# ========== IMPORTER (ENTRY POINT) ==========\n");
            writer.write("pipeline.configs.pipeline1.service.importer.kafka-publish-topics=input-documents\n");
            writer.write("\n");
            writer.write("# ========== CHUNKER ==========\n");
            writer.write("pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[0]=solr-documents\n");
            writer.write("pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[1]=input-documents\n");
            writer.write("pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[2]=tika-documents\n");
            writer.write("pipeline.configs.pipeline1.service.chunker.kafka-publish-topics=chunker-results\n");
            writer.write("\n");
            writer.write("# ========== EMBEDDER ==========\n");
            writer.write("pipeline.configs.pipeline1.service.embedder.kafka-listen-topics=chunker-results\n");
            writer.write("pipeline.configs.pipeline1.service.embedder.kafka-publish-topics=enhanced-documents\n");
            writer.write("\n");
            writer.write("# ========== SOLR-INDEXER (FINAL STAGE) ==========\n");
            writer.write("pipeline.configs.pipeline1.service.solr-indexer.kafka-listen-topics=enhanced-documents\n");
            writer.write("pipeline.configs.pipeline1.service.solr-indexer.grpc-forward-to=null\n");
            writer.write("\n");
            writer.write("# ========== PIPELINE 2 ==========\n");
            writer.write("pipeline.configs.pipeline2.service.service1.kafka-listen-topics=topic1\n");
            writer.write("pipeline.configs.pipeline2.service.service1.kafka-publish-topics=topic2\n");
            writer.write("pipeline.configs.pipeline2.service.service2.kafka-listen-topics=topic2\n");
            writer.write("pipeline.configs.pipeline2.service.service2.grpc-forward-to=service3\n");
        }

        // Create a DefaultPipelineConfig and manually set the properties file path
        DefaultPipelineConfig config = new DefaultPipelineConfig() {
            @Override
            public void init() {
                try {
                    java.util.Properties properties = new java.util.Properties();
                    properties.load(new java.io.FileInputStream(propsFile));

                    // Use reflection to access the private method
                    java.lang.reflect.Method method = DefaultPipelineConfig.class.getDeclaredMethod("parsePipelineProperties", java.util.Properties.class);
                    method.setAccessible(true);
                    method.invoke(this, properties);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        // Initialize the config
        config.init();

        // Verify that pipelines are parsed correctly
        assertEquals(2, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline1"));
        assertTrue(config.getPipelines().containsKey("pipeline2"));

        // Verify pipeline1 services
        PipelineConfig pipeline1 = config.getPipelines().get("pipeline1");
        assertEquals(5, pipeline1.getService().size());
        assertTrue(pipeline1.containsService("tika-parser"));
        assertTrue(pipeline1.containsService("importer"));
        assertTrue(pipeline1.containsService("chunker"));
        assertTrue(pipeline1.containsService("embedder"));
        assertTrue(pipeline1.containsService("solr-indexer"));

        // Verify chunker service in pipeline1
        ServiceConfiguration chunker = pipeline1.getService().get("chunker");
        assertEquals(3, chunker.getKafkaListenTopics().size());
        assertTrue(chunker.getKafkaListenTopics().contains("solr-documents"));
        assertTrue(chunker.getKafkaListenTopics().contains("input-documents"));
        assertTrue(chunker.getKafkaListenTopics().contains("tika-documents"));
        assertEquals(List.of("chunker-results"), chunker.getKafkaPublishTopics());

        // Verify pipeline2 services
        PipelineConfig pipeline2 = config.getPipelines().get("pipeline2");
        assertEquals(2, pipeline2.getService().size());
        assertTrue(pipeline2.containsService("service1"));
        assertTrue(pipeline2.containsService("service2"));

        // Verify service2 in pipeline2
        ServiceConfiguration service2 = pipeline2.getService().get("service2");
        assertEquals(List.of("topic2"), service2.getKafkaListenTopics());
        assertEquals(List.of("service3"), service2.getGrpcForwardTo());
    }
    @Test
    void testLoadPropertiesFromFile_Pipeline1() {
        // Create a DefaultPipelineConfig and load the test-pipeline1.properties file
        DefaultPipelineConfig config = new DefaultPipelineConfig();
        boolean result = config.loadPropertiesFromFile("test-pipeline1.properties");

        // Verify that the file was loaded successfully
        assertTrue(result);

        // Verify that the pipeline was parsed correctly
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline1"));

        // Verify pipeline1 services
        PipelineConfig pipeline1 = config.getPipelines().get("pipeline1");
        assertEquals(4, pipeline1.getService().size());
        assertTrue(pipeline1.containsService("importer"));
        assertTrue(pipeline1.containsService("chunker"));
        assertTrue(pipeline1.containsService("embedder"));
        assertTrue(pipeline1.containsService("solr-indexer"));

        // Verify importer service
        ServiceConfiguration importer = pipeline1.getService().get("importer");
        assertEquals(List.of("test-input-documents"), importer.getKafkaPublishTopics());

        // Verify chunker service
        ServiceConfiguration chunker = pipeline1.getService().get("chunker");
        assertEquals(List.of("test-input-documents"), chunker.getKafkaListenTopics());
        assertEquals(List.of("test-chunker-results"), chunker.getKafkaPublishTopics());

        // Verify embedder service
        ServiceConfiguration embedder = pipeline1.getService().get("embedder");
        assertEquals(List.of("test-chunker-results"), embedder.getKafkaListenTopics());
        assertEquals(List.of("test-enhanced-documents"), embedder.getKafkaPublishTopics());
        assertEquals(List.of("solr-indexer"), embedder.getGrpcForwardTo());

        // Verify solr-indexer service
        ServiceConfiguration solrIndexer = pipeline1.getService().get("solr-indexer");
        assertEquals(List.of("test-enhanced-documents"), solrIndexer.getKafkaListenTopics());
    }

    @Test
    void testLoadPropertiesFromFile_Pipeline2() {
        // Create a DefaultPipelineConfig and load the test-pipeline2.properties file
        DefaultPipelineConfig config = new DefaultPipelineConfig();
        boolean result = config.loadPropertiesFromFile("test-pipeline2.properties");

        // Verify that the file was loaded successfully
        assertTrue(result);

        // Verify that the pipeline was parsed correctly
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline2"));

        // Verify pipeline2 services
        PipelineConfig pipeline2 = config.getPipelines().get("pipeline2");
        assertEquals(5, pipeline2.getService().size());
        assertTrue(pipeline2.containsService("service1"));
        assertTrue(pipeline2.containsService("service2"));
        assertTrue(pipeline2.containsService("service3"));
        assertTrue(pipeline2.containsService("service4"));
        assertTrue(pipeline2.containsService("service5"));

        // Verify service1
        ServiceConfiguration service1 = pipeline2.getService().get("service1");
        assertEquals(List.of("test-topic1"), service1.getKafkaPublishTopics());

        // Verify service2 with array indices
        ServiceConfiguration service2 = pipeline2.getService().get("service2");
        assertEquals(2, service2.getKafkaListenTopics().size());
        assertTrue(service2.getKafkaListenTopics().contains("test-topic1"));
        assertTrue(service2.getKafkaListenTopics().contains("test-topic2"));
        assertEquals(List.of("test-topic3"), service2.getKafkaPublishTopics());

        // Verify service3 with comma-separated grpc-forward-to
        ServiceConfiguration service3 = pipeline2.getService().get("service3");
        assertEquals(List.of("test-topic3"), service3.getKafkaListenTopics());
        assertEquals(2, service3.getGrpcForwardTo().size());
        assertTrue(service3.getGrpcForwardTo().contains("service4"));
        assertTrue(service3.getGrpcForwardTo().contains("service5"));

        // Verify service4 and service5
        ServiceConfiguration service4 = pipeline2.getService().get("service4");
        assertEquals(List.of("test-topic4"), service4.getKafkaListenTopics());

        ServiceConfiguration service5 = pipeline2.getService().get("service5");
        assertEquals(List.of("test-topic5"), service5.getKafkaListenTopics());
    }

    @Test
    void testLoadPropertiesFromFile_NonExistentFile() {
        // Create a DefaultPipelineConfig and try to load a non-existent file
        DefaultPipelineConfig config = new DefaultPipelineConfig();
        boolean result = config.loadPropertiesFromFile("non-existent-file.properties");

        // Verify that the file was not loaded
        assertFalse(result);

        // Verify that the pipelines map is empty
        assertTrue(config.getPipelines().isEmpty());
    }

    @Test
    void testLoadMultiplePropertiesFiles() {
        // Create a DefaultPipelineConfig and load both test property files
        DefaultPipelineConfig config = new DefaultPipelineConfig();

        // Load the first file
        boolean result1 = config.loadPropertiesFromFile("test-pipeline1.properties");
        assertTrue(result1);
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline1"));

        // Load the second file
        boolean result2 = config.loadPropertiesFromFile("test-pipeline2.properties");
        assertTrue(result2);

        // Verify that both pipelines are now in the map
        assertEquals(2, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline1"));
        assertTrue(config.getPipelines().containsKey("pipeline2"));

        // Verify some key properties from each pipeline to ensure they're still correct
        PipelineConfig pipeline1 = config.getPipelines().get("pipeline1");
        assertEquals(4, pipeline1.getService().size());

        PipelineConfig pipeline2 = config.getPipelines().get("pipeline2");
        assertEquals(5, pipeline2.getService().size());
    }

    @Test
    void testLoadPropertiesFromFile_Pipeline3() {
        // Create a DefaultPipelineConfig and load the test-pipeline3.properties file
        DefaultPipelineConfig config = new DefaultPipelineConfig();
        boolean result = config.loadPropertiesFromFile("test-pipeline3.properties");

        // Verify that the file was loaded successfully
        assertTrue(result);

        // Verify that the pipeline was parsed correctly
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline3"));

        // Verify pipeline3 services
        PipelineConfig pipeline3 = config.getPipelines().get("pipeline3");
        assertEquals(5, pipeline3.getService().size());
        assertTrue(pipeline3.containsService("service1"));
        assertTrue(pipeline3.containsService("service2"));
        assertTrue(pipeline3.containsService("service3"));
        assertTrue(pipeline3.containsService("service4"));
        assertTrue(pipeline3.containsService("service5"));

        // Verify service1 with array notation for kafka-publish-topics
        ServiceConfiguration service1 = pipeline3.getService().get("service1");
        assertEquals(2, service1.getKafkaPublishTopics().size());
        assertTrue(service1.getKafkaPublishTopics().contains("test-topic1"));
        assertTrue(service1.getKafkaPublishTopics().contains("test-topic2"));

        // Verify service2 with comma-separated kafka-listen-topics and array notation for kafka-publish-topics
        ServiceConfiguration service2 = pipeline3.getService().get("service2");
        assertEquals(2, service2.getKafkaListenTopics().size());
        assertTrue(service2.getKafkaListenTopics().contains("test-topic1"));
        assertTrue(service2.getKafkaListenTopics().contains("test-topic2"));
        assertEquals(2, service2.getKafkaPublishTopics().size());
        assertTrue(service2.getKafkaPublishTopics().contains("test-topic3"));
        assertTrue(service2.getKafkaPublishTopics().contains("test-topic4"));

        // Verify service3 with array notation for kafka-listen-topics and grpc-forward-to
        ServiceConfiguration service3 = pipeline3.getService().get("service3");
        assertEquals(2, service3.getKafkaListenTopics().size());
        assertTrue(service3.getKafkaListenTopics().contains("test-topic3"));
        assertTrue(service3.getKafkaListenTopics().contains("test-topic4"));
        assertEquals(2, service3.getGrpcForwardTo().size());
        assertTrue(service3.getGrpcForwardTo().contains("service4"));
        assertTrue(service3.getGrpcForwardTo().contains("service5"));

        // Verify service4 and service5
        ServiceConfiguration service4 = pipeline3.getService().get("service4");
        assertEquals(List.of("test-topic5"), service4.getKafkaListenTopics());

        ServiceConfiguration service5 = pipeline3.getService().get("service5");
        assertEquals(List.of("test-topic6"), service5.getKafkaListenTopics());
    }
}
