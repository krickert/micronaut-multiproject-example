package com.krickert.search.pipeline.config;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class PipelineConfigServiceTest {

    @Inject
    private PipelineConfigService pipelineConfigService;

    @Test
    void testPipelineConfigServiceInjection() {
        // Verify that the pipeline config service is injected
        assertNotNull(pipelineConfigService);
        assertEquals("pipeline1", pipelineConfigService.getActivePipelineName());
    }

    @Test
    void testGetActivePipelineConfig() {
        // Verify that the active pipeline config is returned
        PipelineConfig activeConfig = pipelineConfigService.getActivePipelineConfig();
        assertNotNull(activeConfig);
        assertEquals("pipeline1", activeConfig.getName());
    }

    @Test
    void testGetAllPipelineConfigs() {
        // Verify that all pipeline configs are returned
        assertNotNull(pipelineConfigService.getAllPipelineConfigs());
        assertTrue(pipelineConfigService.getAllPipelineConfigs().size() > 0);
    }

    @Test
    void testGetAllPipelineNames() {
        // Verify that all pipeline names are returned
        assertNotNull(pipelineConfigService.getAllPipelineNames());
        assertTrue(pipelineConfigService.getAllPipelineNames().contains("pipeline1"));
    }

    @Test
    void testSetActivePipelineName() {
        // Setup - create a new pipeline config service with multiple pipelines
        Map<String, PipelineConfig> configs = new HashMap<>();
        configs.put("pipeline1", new PipelineConfig("pipeline1"));
        configs.put("pipeline2", new PipelineConfig("pipeline2"));
        PipelineConfigService service = new PipelineConfigService(configs, "pipeline1");

        // Test
        assertEquals("pipeline1", service.getActivePipelineName());
        service.setActivePipelineName("pipeline2");
        assertEquals("pipeline2", service.getActivePipelineName());

        // Test with non-existent pipeline name
        service.setActivePipelineName("nonExistentPipeline");
        // Should still be pipeline2 since nonExistentPipeline doesn't exist
        assertEquals("pipeline2", service.getActivePipelineName());
    }

    @Test
    void testDeleteService() {
        // Setup - create a new pipeline config service with a pipeline that has services
        Map<String, PipelineConfig> configs = new HashMap<>();
        PipelineConfig pipeline = new PipelineConfig("pipeline1");

        Map<String, ServiceConfiguration> services = new HashMap<>();

        ServiceConfiguration chunker = new ServiceConfiguration("chunker");
        chunker.setGrpcForwardTo(new ArrayList<>(List.of("embedder")));
        services.put("chunker", chunker);

        ServiceConfiguration embedder = new ServiceConfiguration("embedder");
        embedder.setGrpcForwardTo(new ArrayList<>(List.of("solr-indexer")));
        services.put("embedder", embedder);

        ServiceConfiguration solrIndexer = new ServiceConfiguration("solr-indexer");
        services.put("solr-indexer", solrIndexer);

        pipeline.setService(services);
        configs.put("pipeline1", pipeline);

        PipelineConfigService service = new PipelineConfigService(configs, "pipeline1");

        // Test
        assertTrue(service.getActivePipelineConfig().containsService("embedder"));
        assertEquals(List.of("embedder"), service.getActivePipelineConfig().getService().get("chunker").getGrpcForwardTo());

        // Delete embedder service
        service.deleteService("embedder");

        // Verify
        assertFalse(service.getActivePipelineConfig().containsService("embedder"));
        // Verify that references to embedder are removed from chunker's grpcForwardTo
        assertTrue(service.getActivePipelineConfig().getService().get("chunker").getGrpcForwardTo().isEmpty());
    }
}
