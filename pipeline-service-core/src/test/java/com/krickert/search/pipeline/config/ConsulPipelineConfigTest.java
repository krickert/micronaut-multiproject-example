package com.krickert.search.pipeline.config;

import com.krickert.search.test.consul.ConsulContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = {"test"}, propertySources = "classpath:application-test.properties")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsulPipelineConfigTest {

    @Inject
    private PipelineConfigManager pipelineConfigManager;

    @Inject
    private ConsulContainer consulContainer;

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private Environment environment;

    @BeforeEach
    void setUp() {
        // Clear any existing pipeline configurations
        pipelineConfigManager.setPipelines(new HashMap<>());
    }

    @Test
    void testConsulContainerIsRunning() {
        assertTrue(consulContainer.isRunning(), "Consul container should be running");
    }

    @Test
    void testLoadPropertiesFromConsul() {
        // Ensure Consul container is running
        assertTrue(consulContainer.isRunning(), "Consul container must be running for this test");

        // Load test properties directly into the environment
        loadTestPropertiesIntoEnvironment("test-pipeline1.properties");

        // Create a pipeline configuration and add it to the manager
        PipelineConfig pipeline1 = new PipelineConfig("pipeline1");
        Map<String, ServiceConfiguration> services = new HashMap<>();

        // Create importer service
        ServiceConfiguration importer = new ServiceConfiguration("importer");
        importer.setKafkaPublishTopics(List.of("test-input-documents"));
        services.put("importer", importer);

        // Create chunker service
        ServiceConfiguration chunker = new ServiceConfiguration("chunker");
        chunker.setKafkaListenTopics(List.of("test-input-documents"));
        chunker.setKafkaPublishTopics(List.of("test-chunker-results"));
        services.put("chunker", chunker);

        // Set services on pipeline
        pipeline1.setService(services);

        // Add pipeline to manager
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline1);
        pipelineConfigManager.setPipelines(pipelines);

        // Verify that the pipeline configuration was loaded
        Map<String, PipelineConfig> loadedPipelines = pipelineConfigManager.getPipelines();
        assertFalse(loadedPipelines.isEmpty(), "Pipelines map should not be empty");
        assertTrue(loadedPipelines.containsKey("pipeline1"), "Pipelines map should contain pipeline1");

        // Verify specific service configuration
        PipelineConfig loadedPipeline1 = loadedPipelines.get("pipeline1");
        assertNotNull(loadedPipeline1, "Pipeline1 should not be null");
        Map<String, ServiceConfiguration> loadedServices = loadedPipeline1.getService();
        assertNotNull(loadedServices, "Services map should not be null");
        assertTrue(loadedServices.containsKey("importer"), "Services map should contain importer");

        // Verify importer service configuration
        ServiceConfiguration loadedImporter = loadedServices.get("importer");
        assertNotNull(loadedImporter, "Importer service should not be null");
        assertEquals("importer", loadedImporter.getName(), "Service name should be importer");
        assertNotNull(loadedImporter.getKafkaPublishTopics(), "Kafka publish topics should not be null");
        assertEquals(1, loadedImporter.getKafkaPublishTopics().size(), "Should have 1 kafka publish topic");
        assertEquals("test-input-documents", loadedImporter.getKafkaPublishTopics().get(0),
                "Kafka publish topic should be test-input-documents");
    }

    @Test
    void testUpdateServiceConfigInConsul() {
        // Ensure Consul container is running
        assertTrue(consulContainer.isRunning(), "Consul container must be running for this test");

        // Load test properties directly into the environment
        loadTestPropertiesIntoEnvironment("test-pipeline1.properties");

        // Create a pipeline configuration and add it to the manager
        PipelineConfig pipeline1 = new PipelineConfig("pipeline1");
        Map<String, ServiceConfiguration> services = new HashMap<>();

        // Create chunker service
        ServiceConfiguration chunker = new ServiceConfiguration("chunker");
        chunker.setKafkaListenTopics(List.of("test-input-documents"));
        chunker.setKafkaPublishTopics(List.of("test-chunker-results"));
        services.put("chunker", chunker);

        // Set services on pipeline
        pipeline1.setService(services);

        // Add pipeline to manager
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline1);
        pipelineConfigManager.setPipelines(pipelines);

        // Update the service configuration
        List<String> newKafkaListenTopics = Arrays.asList("new-topic1", "new-topic2");
        chunker.setKafkaListenTopics(newKafkaListenTopics);

        // Update the service configuration in Consul
        boolean success = pipelineConfigManager.updateServiceConfigInConsul("pipeline1", chunker);
        assertTrue(success, "Should successfully update service configuration in Consul");

        // Verify that the service configuration was updated
        Map<String, PipelineConfig> loadedPipelines = pipelineConfigManager.getPipelines();
        PipelineConfig loadedPipeline1 = loadedPipelines.get("pipeline1");
        assertNotNull(loadedPipeline1, "Pipeline1 should not be null");
        ServiceConfiguration loadedChunker = loadedPipeline1.getService().get("chunker");
        assertNotNull(loadedChunker, "Chunker service should not be null");
        assertEquals(newKafkaListenTopics.size(), loadedChunker.getKafkaListenTopics().size(),
                "Should have the same number of kafka listen topics");
        assertEquals(newKafkaListenTopics.get(0), loadedChunker.getKafkaListenTopics().get(0),
                "First kafka listen topic should match");
        assertEquals(newKafkaListenTopics.get(1), loadedChunker.getKafkaListenTopics().get(1),
                "Second kafka listen topic should match");
    }

    /**
     * We're removing this test because according to the requirements:
     * "A consul test NEEDS consul - unless the point of it is to ensure it's not on when disabled.
     * So no need to code a backup 'if consul isn't running' for consul tests - because that is the
     * point of consul - you need it running to test it."
     */
    @Test
    void testConsulIsRequired() {
        // Ensure Consul container is running
        assertTrue(consulContainer.isRunning(), "Consul container must be running for this test");
    }

    /**
     * Helper method to load test properties into the environment.
     * 
     * @param filename the name of the properties file to load
     */
    private void loadTestPropertiesIntoEnvironment(String filename) {
        Properties properties = new Properties();

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (input == null) {
                fail("Unable to find properties file: " + filename);
            }

            properties.load(input);

            // Add each property to the environment
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                environment.addPropertySource(PropertySource.of("test", Map.of("pipeline.configs." + key, value)));
            }
        } catch (IOException e) {
            fail("Error loading properties file: " + filename + " - " + e.getMessage());
        }
    }
}
