package com.krickert.search.config.pipeline.model.test;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example test class demonstrating how to use the test utilities for serialization testing.
 * This class can be extended or used as a reference for creating serialization tests.
 */
public class PipelineConfigSerializationTest {

    /**
     * Tests serialization and deserialization of a minimal PipelineClusterConfig.
     */
    @Test
    public void testMinimalPipelineClusterConfigSerialization() throws IOException {
        // Create a minimal PipelineClusterConfig
        PipelineClusterConfig config = SamplePipelineConfigObjects.createMinimalPipelineClusterConfig();

        // Serialize to JSON
        String json = PipelineConfigTestUtils.toJson(config);
        
        // Verify that the JSON contains expected elements
        assertTrue(json.contains("\"clusterName\" : \"minimal-cluster\""));
        assertTrue(json.contains("\"defaultPipelineName\" : \"minimal-pipeline\""));
        assertTrue(json.contains("\"stepName\" : \"minimal-step\""));
        assertTrue(json.contains("\"stepType\" : \"PIPELINE\""));
        assertTrue(json.contains("\"grpcServiceName\" : \"minimal-service\""));
        
        // Deserialize back to object
        PipelineClusterConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineClusterConfig.class);
        
        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);
        
        // Verify specific properties
        assertEquals("minimal-cluster", deserialized.clusterName());
        assertEquals("minimal-pipeline", deserialized.defaultPipelineName());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().containsKey("minimal-pipeline"));
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().get("minimal-pipeline").pipelineSteps().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().get("minimal-pipeline").pipelineSteps().containsKey("minimal-step"));
    }

    /**
     * Tests serialization and deserialization of a search indexing PipelineClusterConfig.
     */
    @Test
    public void testSearchIndexingPipelineClusterConfigSerialization() throws IOException {
        // Create a search indexing PipelineClusterConfig
        PipelineClusterConfig config = SamplePipelineConfigObjects.createSearchIndexingPipelineClusterConfig();

        // Serialize to JSON
        String json = PipelineConfigTestUtils.toJson(config);
        
        // Verify that the JSON contains expected elements
        assertTrue(json.contains("\"clusterName\" : \"search-indexing-cluster\""));
        assertTrue(json.contains("\"defaultPipelineName\" : \"search-indexing-pipeline\""));
        assertTrue(json.contains("\"stepName\" : \"file-connector\""));
        assertTrue(json.contains("\"stepType\" : \"INITIAL_PIPELINE\""));
        assertTrue(json.contains("\"topic\" : \"search.files.incoming\""));
        
        // Deserialize back to object
        PipelineClusterConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineClusterConfig.class);
        
        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);
        
        // Verify specific properties
        assertEquals("search-indexing-cluster", deserialized.clusterName());
        assertEquals("search-indexing-pipeline", deserialized.defaultPipelineName());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().containsKey("search-indexing-pipeline"));
        assertEquals(5, deserialized.pipelineGraphConfig().pipelines().get("search-indexing-pipeline").pipelineSteps().size());
        assertTrue(deserialized.allowedKafkaTopics().contains("search.files.incoming"));
        assertTrue(deserialized.allowedGrpcServices().contains("file-connector-service"));
    }

    /**
     * Tests serialization and deserialization of an empty pipeline PipelineClusterConfig.
     */
    @Test
    public void testEmptyPipelineClusterConfigSerialization() throws IOException {
        // Create an empty pipeline PipelineClusterConfig
        PipelineClusterConfig config = SamplePipelineConfigObjects.createEmptyPipelineClusterConfig();

        // Serialize to JSON
        String json = PipelineConfigTestUtils.toJson(config);
        
        // Verify that the JSON contains expected elements
        assertTrue(json.contains("\"clusterName\" : \"empty-pipeline-cluster\""));
        assertTrue(json.contains("\"defaultPipelineName\" : \"empty-pipeline\""));
        assertTrue(json.contains("\"pipelineSteps\" : { }"));
        
        // Deserialize back to object
        PipelineClusterConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineClusterConfig.class);
        
        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);
        
        // Verify specific properties
        assertEquals("empty-pipeline-cluster", deserialized.clusterName());
        assertEquals("empty-pipeline", deserialized.defaultPipelineName());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().containsKey("empty-pipeline"));
        assertEquals(0, deserialized.pipelineGraphConfig().pipelines().get("empty-pipeline").pipelineSteps().size());
    }

    /**
     * Tests serialization and deserialization of an orphan steps PipelineClusterConfig.
     */
    @Test
    public void testOrphanStepsPipelineClusterConfigSerialization() throws IOException {
        // Create an orphan steps PipelineClusterConfig
        PipelineClusterConfig config = SamplePipelineConfigObjects.createOrphanStepsPipelineClusterConfig();

        // Serialize to JSON
        String json = PipelineConfigTestUtils.toJson(config);
        
        // Verify that the JSON contains expected elements
        assertTrue(json.contains("\"clusterName\" : \"orphan-steps-cluster\""));
        assertTrue(json.contains("\"defaultPipelineName\" : \"orphan-steps-pipeline\""));
        assertTrue(json.contains("\"stepName\" : \"orphan-step-1\""));
        assertTrue(json.contains("\"stepName\" : \"orphan-step-2\""));
        assertTrue(json.contains("\"stepName\" : \"orphan-step-3\""));
        assertTrue(json.contains("\"outputs\" : { }"));
        
        // Deserialize back to object
        PipelineClusterConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineClusterConfig.class);
        
        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);
        
        // Verify specific properties
        assertEquals("orphan-steps-cluster", deserialized.clusterName());
        assertEquals("orphan-steps-pipeline", deserialized.defaultPipelineName());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().containsKey("orphan-steps-pipeline"));
        assertEquals(3, deserialized.pipelineGraphConfig().pipelines().get("orphan-steps-pipeline").pipelineSteps().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().get("orphan-steps-pipeline").pipelineSteps().containsKey("orphan-step-1"));
        assertTrue(deserialized.pipelineGraphConfig().pipelines().get("orphan-steps-pipeline").pipelineSteps().containsKey("orphan-step-2"));
        assertTrue(deserialized.pipelineGraphConfig().pipelines().get("orphan-steps-pipeline").pipelineSteps().containsKey("orphan-step-3"));
    }

    /**
     * Tests serialization and deserialization of an initial seeded PipelineClusterConfig.
     */
    @Test
    public void testInitialSeededPipelineClusterConfigSerialization() throws IOException {
        // Create an initial seeded PipelineClusterConfig
        PipelineClusterConfig config = SamplePipelineConfigObjects.createInitialSeededPipelineClusterConfig();

        // Serialize to JSON
        String json = PipelineConfigTestUtils.toJson(config);
        
        // Verify that the JSON contains expected elements
        assertTrue(json.contains("\"clusterName\" : \"initial-seeded-cluster\""));
        assertTrue(json.contains("\"defaultPipelineName\" : \"initial-seeded-pipeline\""));
        assertTrue(json.contains("\"stepName\" : \"initial-service\""));
        assertTrue(json.contains("\"stepType\" : \"INITIAL_PIPELINE\""));
        assertTrue(json.contains("\"outputs\" : { }"));
        
        // Deserialize back to object
        PipelineClusterConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineClusterConfig.class);
        
        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);
        
        // Verify specific properties
        assertEquals("initial-seeded-cluster", deserialized.clusterName());
        assertEquals("initial-seeded-pipeline", deserialized.defaultPipelineName());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().containsKey("initial-seeded-pipeline"));
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().get("initial-seeded-pipeline").pipelineSteps().size());
        assertTrue(deserialized.pipelineGraphConfig().pipelines().get("initial-seeded-pipeline").pipelineSteps().containsKey("initial-service"));
    }

    /**
     * Tests serialization and deserialization using the JSON files.
     */
    @Test
    public void testSerializationFromJsonFiles() throws IOException {
        // Test minimal pipeline cluster config
        String minimalJson = SamplePipelineConfigJson.getMinimalPipelineClusterConfigJson();
        PipelineClusterConfig minimalConfig = PipelineConfigTestUtils.fromJson(minimalJson, PipelineClusterConfig.class);
        String serializedMinimal = PipelineConfigTestUtils.toJson(minimalConfig);
        PipelineClusterConfig deserializedMinimal = PipelineConfigTestUtils.fromJson(serializedMinimal, PipelineClusterConfig.class);
        assertEquals(minimalConfig, deserializedMinimal);

        // Test search indexing pipeline
        String searchIndexingJson = SamplePipelineConfigJson.getSearchIndexingPipelineJson();
        PipelineClusterConfig searchIndexingConfig = PipelineConfigTestUtils.fromJson(searchIndexingJson, PipelineClusterConfig.class);
        String serializedSearchIndexing = PipelineConfigTestUtils.toJson(searchIndexingConfig);
        PipelineClusterConfig deserializedSearchIndexing = PipelineConfigTestUtils.fromJson(serializedSearchIndexing, PipelineClusterConfig.class);
        assertEquals(searchIndexingConfig, deserializedSearchIndexing);

        // Test empty pipeline
        String emptyPipelineJson = SamplePipelineConfigJson.getEmptyPipelineJson();
        PipelineClusterConfig emptyPipelineConfig = PipelineConfigTestUtils.fromJson(emptyPipelineJson, PipelineClusterConfig.class);
        String serializedEmptyPipeline = PipelineConfigTestUtils.toJson(emptyPipelineConfig);
        PipelineClusterConfig deserializedEmptyPipeline = PipelineConfigTestUtils.fromJson(serializedEmptyPipeline, PipelineClusterConfig.class);
        assertEquals(emptyPipelineConfig, deserializedEmptyPipeline);
    }

    /**
     * Tests the testSerialization utility method.
     */
    @Test
    public void testSerializationUtility() {
        // Create a minimal PipelineClusterConfig
        PipelineClusterConfig config = SamplePipelineConfigObjects.createMinimalPipelineClusterConfig();

        // Test serialization using the utility method
        assertTrue(PipelineConfigTestUtils.testSerialization(config, PipelineClusterConfig.class));

        // Test serialization with a custom equality function
        assertTrue(PipelineConfigTestUtils.testSerialization(config, PipelineClusterConfig.class, 
                deserialized -> deserialized.clusterName().equals("minimal-cluster")));
    }
}