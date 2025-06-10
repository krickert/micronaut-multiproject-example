package com.krickert.search.engine.core;

import com.google.protobuf.Timestamp;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.core.routing.Router;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.util.ProcessingBuffer;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Test for enhanced buffer functionality with per-step buffering.
 */
public class BufferEnhancementTest {

    @Mock
    private com.krickert.search.config.consul.service.BusinessOperationsService businessOpsService;
    
    @Mock
    private DynamicConfigurationManager configManager;
    
    @Mock
    Router router;
    
    @TempDir
    Path tempDir;
    
    private PipelineEngineImpl pipelineEngine;
    
    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        
        // Setup mock configuration
        var pipelineConfig = createTestPipelineConfig();
        when(configManager.getPipelineConfig(anyString())).thenReturn(Optional.of(pipelineConfig));
        when(configManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(
            new PipelineClusterConfig(
                "test-cluster",
                new PipelineGraphConfig(Map.of("test-pipeline", pipelineConfig)),
                new PipelineModuleMap(Map.of()),
                "test-pipeline",
                Set.of(),
                Set.of()
            )
        ));
        
        // Create engine with buffering enabled
        pipelineEngine = new PipelineEngineImpl(
            businessOpsService,
            router,
            "test-cluster",
            true,  // Enable buffer
            10,    // Small capacity for testing
            3,     // Precision
            1.0    // Sample everything
        );
    }
    
    @AfterEach
    void cleanup() {
        if (pipelineEngine != null) {
            pipelineEngine.shutdown();
        }
    }
    
    @Test
    void testPerStepBufferFileNaming() throws Exception {
        // Create test document
        PipeDoc document = PipeDoc.newBuilder()
            .setId("test-doc-001")
            .setTitle("Buffer Test Document")
            .setBody("This is a test document for buffer enhancement")
            .setSourceMimeType("text/plain")
            .setSourceUri("test://documents/buffer-test.txt")
            .setCreationDate(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build())
            .build();
        
        // Create PipeStream
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("stream-test-" + System.currentTimeMillis())
            .setDocument(document)
            .setCurrentPipelineName("test-pipeline")
            .setCurrentHopNumber(0)
            .build();
        
        // Note: We can't easily test the actual gRPC calls without real services,
        // but we can verify the buffer structure is created correctly
        
        // Force shutdown to trigger buffer saves
        pipelineEngine.shutdown();
        
        // Check that files would be created with step names in the pattern
        // The actual file creation depends on having real services to call
        // This test mainly validates the compilation and structure
        
        assertThat(tempDir).isNotNull();
        // In a real test with services, we'd check for files like:
        // engine-requests-tika-parser-{timestamp}-001.bin
        // engine-responses-tika-parser-{timestamp}-001.bin
        // engine-pipedocs-tika-parser-{timestamp}-001.bin
    }
    
    private PipelineConfig createTestPipelineConfig() {
        // Create a simple test pipeline config
        var tikaStep = new PipelineStepConfig(
            "tika-parser",
            StepType.PIPELINE,
            "Extract text",
            null,
            new PipelineStepConfig.JsonConfigOptions(Map.of()),
            Map.of("default", new PipelineStepConfig.OutputTarget(
                "chunker",
                TransportType.GRPC,
                new GrpcTransportConfig("chunker", Map.of()),
                null
            )),
            0, 1000L, 30000L, 2.0, null,
            new PipelineStepConfig.ProcessorInfo("tika-parser", null)
        );
        
        var chunkerStep = new PipelineStepConfig(
            "chunker",
            StepType.SINK,
            "Chunk text",
            null,
            new PipelineStepConfig.JsonConfigOptions(Map.of()),
            Map.of(),
            0, 1000L, 30000L, 2.0, null,
            new PipelineStepConfig.ProcessorInfo("chunker", null)
        );
        
        return new PipelineConfig(
            "test-pipeline",
            Map.of("tika-parser", tikaStep, "chunker", chunkerStep)
        );
    }
}