package com.krickert.search.engine.bootstrap;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.engine.bootstrap.impl.InMemoryBootstrapService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test-new-bootstrap")
@DisplayName("Bootstrap Service Tests")
class BootstrapServiceTest {
    
    @Inject
    BootstrapService bootstrapService;
    
    @Inject
    SeedDataService seedDataService;
    
    private static final String TEST_CLUSTER = "test-cluster";
    
    @BeforeEach
    void setup() {
        // Reset bootstrap state before each test
        bootstrapService.resetBootstrap(TEST_CLUSTER).block();
    }
    
    @Test
    @DisplayName("Bootstrap creates minimal cluster configuration")
    void testBootstrapCreatesMinimalConfig() {
        StepVerifier.create(bootstrapService.bootstrap(TEST_CLUSTER))
                .verifyComplete();
        
        StepVerifier.create(bootstrapService.isBootstrapped(TEST_CLUSTER))
                .expectNext(true)
                .verifyComplete();
        
        // Verify configuration was created
        if (bootstrapService instanceof InMemoryBootstrapService) {
            InMemoryBootstrapService inMemory = (InMemoryBootstrapService) bootstrapService;
            PipelineClusterConfig config = inMemory.getConfiguration(TEST_CLUSTER);
            assertNotNull(config);
            assertEquals(TEST_CLUSTER, config.clusterName());
            assertTrue(config.pipelineGraphConfig().pipelines().isEmpty());
            assertTrue(config.pipelineModuleMap().availableModules().isEmpty());
        }
    }
    
    @Test
    @DisplayName("Bootstrap is idempotent")
    void testBootstrapIsIdempotent() {
        // First bootstrap
        StepVerifier.create(bootstrapService.bootstrap(TEST_CLUSTER))
                .verifyComplete();
        
        // Second bootstrap should complete without error
        StepVerifier.create(bootstrapService.bootstrap(TEST_CLUSTER))
                .verifyComplete();
        
        StepVerifier.create(bootstrapService.isBootstrapped(TEST_CLUSTER))
                .expectNext(true)
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Reset bootstrap clears state")
    void testResetBootstrap() {
        // Bootstrap first
        StepVerifier.create(bootstrapService.bootstrap(TEST_CLUSTER))
                .verifyComplete();
        
        StepVerifier.create(bootstrapService.isBootstrapped(TEST_CLUSTER))
                .expectNext(true)
                .verifyComplete();
        
        // Reset
        StepVerifier.create(bootstrapService.resetBootstrap(TEST_CLUSTER))
                .verifyComplete();
        
        StepVerifier.create(bootstrapService.isBootstrapped(TEST_CLUSTER))
                .expectNext(false)
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Seed data service creates test pipeline configuration")
    void testSeedDataService() {
        StepVerifier.create(seedDataService.createTestPipelineConfig(TEST_CLUSTER))
                .assertNext(config -> {
                    assertEquals(TEST_CLUSTER, config.clusterName());
                    assertFalse(config.pipelineGraphConfig().pipelines().isEmpty());
                    assertTrue(config.pipelineGraphConfig().pipelines().containsKey("test-pipeline"));
                    
                    // Verify modules
                    assertEquals(2, config.pipelineModuleMap().availableModules().size());
                    assertTrue(config.pipelineModuleMap().availableModules().containsKey("tika-parser"));
                    assertTrue(config.pipelineModuleMap().availableModules().containsKey("chunker"));
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Bootstrap with seeding creates complete configuration")
    void testBootstrapWithSeeding() {
        // Bootstrap
        StepVerifier.create(bootstrapService.bootstrap(TEST_CLUSTER))
                .verifyComplete();
        
        // Seed with test template
        StepVerifier.create(seedDataService.seedCluster(TEST_CLUSTER, "test"))
                .verifyComplete();
        
        // Verify configuration
        if (bootstrapService instanceof InMemoryBootstrapService) {
            InMemoryBootstrapService inMemory = (InMemoryBootstrapService) bootstrapService;
            PipelineClusterConfig config = inMemory.getConfiguration(TEST_CLUSTER);
            assertNotNull(config);
            assertEquals(TEST_CLUSTER, config.clusterName());
            assertFalse(config.pipelineGraphConfig().pipelines().isEmpty());
            assertFalse(config.pipelineModuleMap().availableModules().isEmpty());
        }
    }
}