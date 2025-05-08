package com.krickert.search.pipeline.router;

import com.krickert.search.config.consul.model.KafkaRouteTarget;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.service.ConfigurationService; // Real service
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.executor.GrpcPipelineStepExecutor; // Mocked service

// Micronaut Test Imports
import com.krickert.search.pipeline.kafka.KafkaForwarder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean; // Import for mocking beans

// JUnit 5 and Mockito Imports
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

// Jakarta Inject
import jakarta.inject.Inject;

// Java / Other Imports
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

// Static imports for Mockito and Assertions
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PipelineRouter.
 * Uses @MicronautTest to load application context and potentially
 * test resources like Consul (assuming configured via test-resources).
 * Mocks the GrpcPipelineStepExecutor and KafkaForwarder to verify interactions.
 */
@MicronautTest // Loads context, enables DI, potentially starts containers
class PipelineRouterIntegrationTest {

    // Inject the real router instance from the Micronaut context
    @Inject
    private PipelineRouter pipelineRouter;

    // Inject the real ConfigurationService - we assume it's populated
    // by test resources (e.g., loading test-seed-data.yaml into Consul)
    @Inject
    private ConfigurationService configurationService;

    // --- Mock the Forwarders ---
    // We want to verify the router CALLS these correctly, but don't need
    // them to actually make network calls in this test.
    @Inject
    private GrpcPipelineStepExecutor grpcExecutorMock; // Micronaut provides the mock instance

    @Inject
    private KafkaForwarder kafkaForwarderMock; // Micronaut provides the mock instance

    // --- Argument Captors ---
    @Captor
    private ArgumentCaptor<String> stringArgCaptor;
    @Captor
    private ArgumentCaptor<PipeStream> pipeStreamArgCaptor;
    @Captor
    private ArgumentCaptor<UUID> uuidArgCaptor;

    // --- Test Data ---
    // Define pipeline/step names expected to be loaded from test-seed-data.yaml
    private final String pipelineName = "pipeline1"; // Example pipeline from your seed data
    private final String stepA_Id = "chunker";       // Example step from seed data
    private final String stepB_Id = "embedder";      // Next logical step for chunker in pipeline1
    private final String targetGrpcService = "com.krickert.search.pipeline.service.EmbedderService"; // Expected target app
    private final String targetKafkaTopic = "chunker-results"; // Topic published by chunker
    private final String streamId = UUID.randomUUID().toString();

    private PipeStepConfigurationDto stepA_Config;
    private PipeStream.Builder outgoingStreamBuilder;

    // --- Mock Bean Factories ---
    // These methods tell Micronaut how to create the Mocks for DI
    @MockBean(GrpcPipelineStepExecutor.class)
    GrpcPipelineStepExecutor grpcPipelineStepExecutor() {
        return mock(GrpcPipelineStepExecutor.class);
    }

    @MockBean(KafkaForwarder.class)
    KafkaForwarder kafkaForwarder() {
        return mock(KafkaForwarder.class);
    }

    @BeforeEach
    void setUp() {
        // Reset mocks before each test for isolation
        reset(grpcExecutorMock, kafkaForwarderMock);

        // Fetch the actual config loaded from Consul (via test resources)
        // This makes the test more realistic than mocking ConfigurationService
        PipelineConfigDto pipelineConfig = configurationService.getPipeline(pipelineName);
        assertNotNull(pipelineConfig, "Pipeline config '" + pipelineName + "' should be loaded from seed data");
        stepA_Config = pipelineConfig.getServices().get(stepA_Id);
        assertNotNull(stepA_Config, "Step config '" + stepA_Id + "' should be loaded from seed data");

        // Verify seed data loaded correctly (optional but good)
        assertNotNull(stepA_Config.getKafkaPublishTopics());
        assertFalse(stepA_Config.getKafkaPublishTopics().isEmpty());
        // Assuming pipeline1.chunker routes to embedder via Kafka based on seed data
        assertEquals(stepB_Id, stepA_Config.getKafkaPublishTopics().get(0).targetPipeStepId());
        assertEquals(targetKafkaTopic, stepA_Config.getKafkaPublishTopics().get(0).topic());
        // Assuming pipeline1.chunker has no gRPC routes defined in seed data
         assertTrue(isNullOrEmpty(stepA_Config.getGrpcForwardTo()), "Chunker step in seed data shouldn't have gRPC forward");


        // Prepare outgoing stream builder state (as if step A just finished)
        outgoingStreamBuilder = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setPipelineName(pipelineName)
                .setCurrentHopNumber(2) // Example hop number after chunker
                .setCurrentPipestepId(stepA_Id); // Set the ID of the step *that just finished*
                // The router will replace this with the *next* step ID before forwarding
    }

    @Test
    void routeAndForward_shouldCallKafkaForwarderBasedOnSeedData() {
        // Arrange
        // Config is loaded from seed data in setUp()
        // stepA_Config (chunker) should publish to targetKafkaTopic for stepC_Id (embedder)

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert
        // Verify KafkaForwarder was called correctly based on seed data config
        verify(kafkaForwarderMock, times(1)).forwardToKafka(pipeStreamArgCaptor.capture(), stringArgCaptor.capture());
        verifyNoInteractions(grpcExecutorMock); // No gRPC routes expected for chunker in seed data

        assertEquals(targetKafkaTopic, stringArgCaptor.getValue());
        assertEquals(UUID.fromString(streamId), uuidArgCaptor.getValue());
        PipeStream forwardedStream = pipeStreamArgCaptor.getValue();
        assertEquals(stepB_Id, forwardedStream.getCurrentPipestepId(), "Should set pipestep ID for Kafka consumer (embedder)");
        assertEquals(2, forwardedStream.getCurrentHopNumber(), "Hop number should be unchanged by router"); // Hop number was already incremented before router call
    }

     @Test
    void routeAndForward_shouldCallGrpcExecutor_ifRouteConfigured() {
        // Arrange
        // Modify the loaded config for this test case to add a gRPC route
        String nextGrpcStepId = "solr-indexer"; // Example target step from pipeline1
        String targetConsulApp = "com.krickert.search.pipeline.service.SolrIndexerService";
        stepA_Config.setGrpcForwardTo(List.of(nextGrpcStepId));
        // Keep Kafka route as well to test both
        // KafkaRouteTarget kafkaRoute = new KafkaRouteTarget(targetKafkaTopic, stepB_Id); // stepB is embedder
        // stepA_Config.setKafkaPublishTopics(List.of(kafkaRoute)); // Already set in setup

        // Mock GrpcExecutor response
        when(grpcExecutorMock.forwardPipeStreamAsync(anyString(), any(PipeStream.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert
        // Verify gRPC call
        verify(grpcExecutorMock, times(1)).forwardPipeStreamAsync(stringArgCaptor.capture(), pipeStreamArgCaptor.capture());
        assertEquals(targetConsulApp, stringArgCaptor.getValue());
        assertEquals(nextGrpcStepId, pipeStreamArgCaptor.getValue().getCurrentPipestepId());

        // Verify Kafka call still happens
        verify(kafkaForwarderMock, times(1)).forwardToKafka(pipeStreamArgCaptor.capture(), eq(targetKafkaTopic));
        assertEquals(stepB_Id, pipeStreamArgCaptor.getValue().getCurrentPipestepId()); // stepB_Id is 'embedder'
    }

    // Add more tests:
    // - Test case where next logical step ID from routing rule is not found in pipeline config
    // - Test case where next logical step config has no serviceImplementation
    // - Test case with multiple gRPC routes
    // - Test case with multiple Kafka routes


    // Helper for null/empty check
    private boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
