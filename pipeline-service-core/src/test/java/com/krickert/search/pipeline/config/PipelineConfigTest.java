package com.krickert.search.pipeline.config;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class PipelineConfigTest {

    @Inject
    private PipelineConfig pipelineConfig;

    @Test
    void testPipelineConfigInjection() {
        // Verify that the pipeline config is injected
        assertNotNull(pipelineConfig);
        assertEquals("pipeline1", pipelineConfig.getName());
    }

    @Test
    void testContainsService() {
        // Setup
        PipelineConfig config = new PipelineConfig("testPipeline");
        Map<String, ServiceConfiguration> services = new HashMap<>();
        
        ServiceConfiguration chunker = new ServiceConfiguration("chunker");
        services.put("chunker", chunker);
        
        config.setService(services);
        
        // Test
        assertTrue(config.containsService("chunker"));
        assertFalse(config.containsService("nonExistentService"));
    }

    @Test
    void testAddOrUpdateService() {
        // Setup
        PipelineConfig config = new PipelineConfig("testPipeline");
        config.setService(new HashMap<>());
        
        ServiceConfigurationDto dto = new ServiceConfigurationDto();
        dto.setName("embedder");
        dto.setKafkaListenTopics(List.of("chunker-results"));
        dto.setKafkaPublishTopics(List.of("enhanced-documents"));
        dto.setGrpcForwardTo(List.of("solr-indexer"));
        
        // Test
        config.addOrUpdateService(dto);
        
        // Verify
        assertTrue(config.containsService("embedder"));
        ServiceConfiguration addedService = config.getService().get("embedder");
        assertEquals("embedder", addedService.getName());
        assertEquals(List.of("chunker-results"), addedService.getKafkaListenTopics());
        assertEquals(List.of("enhanced-documents"), addedService.getKafkaPublishTopics());
        assertEquals(List.of("solr-indexer"), addedService.getGrpcForwardTo());
        
        // Test update
        dto.setKafkaListenTopics(List.of("new-topic"));
        config.addOrUpdateService(dto);
        
        // Verify update
        ServiceConfiguration updatedService = config.getService().get("embedder");
        assertEquals(List.of("new-topic"), updatedService.getKafkaListenTopics());
    }
}