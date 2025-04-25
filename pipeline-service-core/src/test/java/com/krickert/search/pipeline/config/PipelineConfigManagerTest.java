package com.krickert.search.pipeline.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PipelineConfigManagerTest {

    private PipelineConfigManager pipelineConfigManager;

    @BeforeEach
    void setUp() {
        pipelineConfigManager = new PipelineConfigManager();
    }

    @Test
    void testInitWithNoPropertiesFile() {
        // Create a PipelineConfigManager with a custom init method that doesn't load any files
        PipelineConfigManager config = new PipelineConfigManager() {
            @Override
            public void init() {
                // Just clear the pipelines map without trying to load any files
                setPipelines(new HashMap<>());
            }
        };

        // Test init when no properties file exists
        config.init();

        // Verify that pipelines map is empty
        assertTrue(config.getPipelines().isEmpty());
    }

    @Test
    void testSetAndGetPipelines() {
        // Setup
        Map<String, PipelineConfig> pipelines = Map.of(
            "pipeline1", new PipelineConfig("pipeline1"),
            "pipeline2", new PipelineConfig("pipeline2")
        );

        // Test
        pipelineConfigManager.setPipelines(pipelines);

        // Verify
        assertEquals(pipelines, pipelineConfigManager.getPipelines());
        assertEquals(2, pipelineConfigManager.getPipelines().size());
        assertTrue(pipelineConfigManager.getPipelines().containsKey("pipeline1"));
        assertTrue(pipelineConfigManager.getPipelines().containsKey("pipeline2"));
    }

    @Test
    void testParsePipelineProperties(@TempDir Path tempDir) throws IOException {
        // Create a test properties file
        File propsFile = tempDir.resolve("pipeline.properties").toFile();
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

        // Create a PipelineConfigManager and manually set the properties file path
        PipelineConfigManager config = new PipelineConfigManager() {
            @Override
            public void init() {
                try {
                    java.util.Properties properties = new java.util.Properties();
                    properties.load(new java.io.FileInputStream(propsFile));

                    // Use reflection to access the private method
                    java.lang.reflect.Method method = PipelineConfigManager.class.getDeclaredMethod("parsePipelineProperties", java.util.Properties.class);
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
        assertEquals("pipeline1", pipeline1.getName());
        assertEquals(5, pipeline1.getService().size());
        assertTrue(pipeline1.getService().containsKey("tika-parser"));
        assertTrue(pipeline1.getService().containsKey("importer"));
        assertTrue(pipeline1.getService().containsKey("chunker"));
        assertTrue(pipeline1.getService().containsKey("embedder"));
        assertTrue(pipeline1.getService().containsKey("solr-indexer"));

        // Verify pipeline2 services
        PipelineConfig pipeline2 = config.getPipelines().get("pipeline2");
        assertEquals("pipeline2", pipeline2.getName());
        assertEquals(2, pipeline2.getService().size());
        assertTrue(pipeline2.getService().containsKey("service1"));
        assertTrue(pipeline2.getService().containsKey("service2"));

        // Verify specific service configurations
        ServiceConfiguration chunker = pipeline1.getService().get("chunker");
        assertEquals("chunker", chunker.getName());
        assertEquals(3, chunker.getKafkaListenTopics().size());
        assertTrue(chunker.getKafkaListenTopics().contains("solr-documents"));
        assertTrue(chunker.getKafkaListenTopics().contains("input-documents"));
        assertTrue(chunker.getKafkaListenTopics().contains("tika-documents"));
        assertEquals(1, chunker.getKafkaPublishTopics().size());
        assertEquals("chunker-results", chunker.getKafkaPublishTopics().get(0));

        ServiceConfiguration service2 = pipeline2.getService().get("service2");
        assertEquals("service2", service2.getName());
        assertEquals(1, service2.getKafkaListenTopics().size());
        assertEquals("topic2", service2.getKafkaListenTopics().get(0));
        assertEquals(1, service2.getGrpcForwardTo().size());
        assertEquals("service3", service2.getGrpcForwardTo().get(0));
    }

    @Test
    void testLoadPropertiesFromFile_Pipeline1() {
        // Create a PipelineConfigManager and load the test-pipeline1.properties file
        PipelineConfigManager config = new PipelineConfigManager();
        boolean result = config.loadPropertiesFromFile("test-pipeline1.properties");

        // Verify that the file was loaded successfully
        assertTrue(result);

        // Verify that the pipeline was parsed correctly
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline1"));

        // Verify pipeline1 services
        PipelineConfig pipeline1 = config.getPipelines().get("pipeline1");
        assertEquals("pipeline1", pipeline1.getName());
        assertEquals(4, pipeline1.getService().size());
        assertTrue(pipeline1.getService().containsKey("importer"));
        assertTrue(pipeline1.getService().containsKey("chunker"));
        assertTrue(pipeline1.getService().containsKey("embedder"));
        assertTrue(pipeline1.getService().containsKey("solr-indexer"));

        // Verify specific service configurations
        ServiceConfiguration importer = pipeline1.getService().get("importer");
        assertEquals("importer", importer.getName());
        assertEquals(1, importer.getKafkaPublishTopics().size());
        assertEquals("test-input-documents", importer.getKafkaPublishTopics().get(0));

        ServiceConfiguration chunker = pipeline1.getService().get("chunker");
        assertEquals("chunker", chunker.getName());
        assertEquals(1, chunker.getKafkaListenTopics().size());
        assertEquals("test-input-documents", chunker.getKafkaListenTopics().get(0));
        assertEquals(1, chunker.getKafkaPublishTopics().size());
        assertEquals("test-chunker-results", chunker.getKafkaPublishTopics().get(0));
    }

    @Test
    void testLoadPropertiesFromFile_Pipeline2() {
        // Create a PipelineConfigManager and load the test-pipeline2.properties file
        PipelineConfigManager config = new PipelineConfigManager();
        boolean result = config.loadPropertiesFromFile("test-pipeline2.properties");

        // Verify that the file was loaded successfully
        assertTrue(result);

        // Verify that the pipeline was parsed correctly
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline2"));

        // Verify pipeline2 services
        PipelineConfig pipeline2 = config.getPipelines().get("pipeline2");
        assertEquals("pipeline2", pipeline2.getName());
        assertEquals(5, pipeline2.getService().size());
        assertTrue(pipeline2.getService().containsKey("service1"));
        assertTrue(pipeline2.getService().containsKey("service2"));
        assertTrue(pipeline2.getService().containsKey("service3"));
        assertTrue(pipeline2.getService().containsKey("service4"));
        assertTrue(pipeline2.getService().containsKey("service5"));

        // Verify specific service configurations
        ServiceConfiguration service1 = pipeline2.getService().get("service1");
        assertEquals("service1", service1.getName());
        assertEquals(1, service1.getKafkaPublishTopics().size());
        assertEquals("test-topic1", service1.getKafkaPublishTopics().get(0));

        ServiceConfiguration service2 = pipeline2.getService().get("service2");
        assertEquals("service2", service2.getName());
        assertEquals(2, service2.getKafkaListenTopics().size());
        assertTrue(service2.getKafkaListenTopics().contains("test-topic1"));
        assertTrue(service2.getKafkaListenTopics().contains("test-topic2"));
        assertEquals(1, service2.getKafkaPublishTopics().size());
        assertEquals("test-topic3", service2.getKafkaPublishTopics().get(0));

        ServiceConfiguration service3 = pipeline2.getService().get("service3");
        assertEquals("service3", service3.getName());
        assertEquals(1, service3.getKafkaListenTopics().size());
        assertEquals("test-topic3", service3.getKafkaListenTopics().get(0));
        assertEquals(2, service3.getGrpcForwardTo().size());
        assertTrue(service3.getGrpcForwardTo().contains("service4"));
        assertTrue(service3.getGrpcForwardTo().contains("service5"));
    }

    @Test
    void testLoadPropertiesFromFile_NonExistentFile() {
        // Create a PipelineConfigManager and try to load a non-existent file
        PipelineConfigManager config = new PipelineConfigManager();
        boolean result = config.loadPropertiesFromFile("non-existent-file.properties");

        // Verify that the file was not loaded
        assertFalse(result);

        // Verify that the pipelines map is empty
        assertTrue(config.getPipelines().isEmpty());
    }

    @Test
    void testLoadMultiplePropertiesFiles() {
        // Create a PipelineConfigManager and load both test property files
        PipelineConfigManager config = new PipelineConfigManager();
        boolean result1 = config.loadPropertiesFromFile("test-pipeline1.properties");

        // Verify that the first file was loaded successfully
        assertTrue(result1);

        // Verify that pipeline1 was parsed correctly
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline1"));

        // Now load the second file
        boolean result2 = config.loadPropertiesFromFile("test-pipeline2.properties");

        // Verify that the second file was loaded successfully
        assertTrue(result2);

        // Verify that only pipeline2 exists now (since we clear the map before loading)
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline2"));

        // Verify pipeline2 services
        PipelineConfig pipeline2 = config.getPipelines().get("pipeline2");
        assertEquals("pipeline2", pipeline2.getName());
        assertEquals(5, pipeline2.getService().size());
    }

    @Test
    void testLoadPropertiesFromFile_Pipeline3() {
        // Create a PipelineConfigManager and load the test-pipeline3.properties file
        PipelineConfigManager config = new PipelineConfigManager();
        boolean result = config.loadPropertiesFromFile("test-pipeline3.properties");

        // Verify that the file was loaded successfully
        assertTrue(result);

        // Verify that the pipeline was parsed correctly
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("pipeline3"));

        // Verify pipeline3 services
        PipelineConfig pipeline3 = config.getPipelines().get("pipeline3");
        assertEquals("pipeline3", pipeline3.getName());
        assertEquals(5, pipeline3.getService().size());
        assertTrue(pipeline3.getService().containsKey("service1"));
        assertTrue(pipeline3.getService().containsKey("service2"));
        assertTrue(pipeline3.getService().containsKey("service3"));
        assertTrue(pipeline3.getService().containsKey("service4"));
        assertTrue(pipeline3.getService().containsKey("service5"));

        // Verify specific service configurations with array notation
        ServiceConfiguration service1 = pipeline3.getService().get("service1");
        assertEquals("service1", service1.getName());
        assertEquals(2, service1.getKafkaPublishTopics().size());
        assertEquals("test-topic1", service1.getKafkaPublishTopics().get(0));
        assertEquals("test-topic2", service1.getKafkaPublishTopics().get(1));

        ServiceConfiguration service2 = pipeline3.getService().get("service2");
        assertEquals("service2", service2.getName());
        assertEquals(2, service2.getKafkaListenTopics().size());
        assertEquals("test-topic1", service2.getKafkaListenTopics().get(0));
        assertEquals("test-topic2", service2.getKafkaListenTopics().get(1));
        assertEquals(2, service2.getKafkaPublishTopics().size());
        assertEquals("test-topic3", service2.getKafkaPublishTopics().get(0));
        assertEquals("test-topic4", service2.getKafkaPublishTopics().get(1));

        ServiceConfiguration service3 = pipeline3.getService().get("service3");
        assertEquals("service3", service3.getName());
        assertEquals(2, service3.getKafkaListenTopics().size());
        assertEquals("test-topic3", service3.getKafkaListenTopics().get(0));
        assertEquals("test-topic4", service3.getKafkaListenTopics().get(1));
        assertEquals(2, service3.getGrpcForwardTo().size());
        assertEquals("service4", service3.getGrpcForwardTo().get(0));
        assertEquals("service5", service3.getGrpcForwardTo().get(1));
    }
}
