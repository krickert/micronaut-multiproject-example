package com.krickert.search.pipeline.router;

// Import necessary classes
import com.krickert.search.config.consul.model.KafkaRouteTarget;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.service.ConfigurationService;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.executor.GrpcPipelineStepExecutor;
import com.krickert.search.pipeline.kafka.KafkaForwarder; // Your KafkaForwarder class

// JUnit 5 and Mockito Imports
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Unit tests for the PipelineRouter class.
 * Verifies that routing rules are processed correctly and forwarders are invoked appropriately.
 */
@ExtendWith(MockitoExtension.class) // Initialize mocks and inject them
class PipelineRouterTest {

    // Mocks for all dependencies of PipelineRouter
    @Mock
    private ConfigurationService configurationService;
    @Mock
    private GrpcPipelineStepExecutor grpcExecutor;
    @Mock
    private KafkaForwarder kafkaForwarder; // Mock your KafkaForwarder

    // Inject the mocks into the instance of PipelineRouter under test
    @InjectMocks
    private PipelineRouter pipelineRouter;

    // Argument captors to verify data passed to mocks
    @Captor
    private ArgumentCaptor<String> stringCaptor;
    @Captor
    private ArgumentCaptor<PipeStream> pipeStreamCaptor;
    @Captor
    private ArgumentCaptor<UUID> uuidCaptor; // For Kafka key

    // Common test data setup
    private PipelineConfigDto testPipelineConfig;
    private PipeStepConfigurationDto stepA_Config; // Config for the step that just finished
    private PipeStepConfigurationDto stepB_GrpcConfig; // Config for the next potential gRPC step
    private PipeStepConfigurationDto stepC_KafkaConfig; // Config for a step consuming Kafka output
    private PipeStream.Builder outgoingStreamBuilder; // Builder state AFTER step A finished

    private final String pipelineName = "test-pipeline";
    private final String streamId = UUID.randomUUID().toString();
    private final String stepA_Id = "stepA"; // Logical ID of completed step
    private final String stepB_Id = "stepB"; // Logical ID of next gRPC step
    private final String stepC_Id = "stepC"; // Logical ID of next Kafka step (consumer side)
    private final String targetGrpcService = "service-for-stepB"; // Consul name for step B's app
    private final String targetKafkaTopic = "topic-for-stepC";

    @BeforeEach
    void setUp() {
        // --- Create Test Configuration DTOs ---

        // Config for Step A (completed step) - routing rules set per test
        stepA_Config = new PipeStepConfigurationDto();
        stepA_Config.setName(stepA_Id);
        stepA_Config.setServiceImplementation("service-A-impl");

        // Config for Step B (next gRPC step)
        stepB_GrpcConfig = new PipeStepConfigurationDto();
        stepB_GrpcConfig.setName(stepB_Id);
        stepB_GrpcConfig.setServiceImplementation(targetGrpcService); // Consul app name

        // Config for Step C (step that would consume Kafka output)
        // Need this primarily to simulate the lookup performed by the router for Kafka routes
        stepC_KafkaConfig = new PipeStepConfigurationDto();
        stepC_KafkaConfig.setName(stepC_Id);
        stepC_KafkaConfig.setServiceImplementation("service-C-impl"); // Some app consumes Kafka

        // Full pipeline config containing these steps
        testPipelineConfig = new PipelineConfigDto(pipelineName);
        testPipelineConfig.getServices().put(stepA_Id, stepA_Config);
        testPipelineConfig.getServices().put(stepB_Id, stepB_GrpcConfig);
        testPipelineConfig.getServices().put(stepC_Id, stepC_KafkaConfig); // Add step C config

        // --- Mock ConfigurationService ---
        // Always return the test pipeline config when asked for pipelineName
        when(configurationService.getPipeline(pipelineName)).thenReturn(testPipelineConfig);

        // --- Prepare Base PipeStream Builder ---
        // Represents the state *after* step A's developer logic ran,
        // hop incremented, history added etc. (done by the caller of the router)
        outgoingStreamBuilder = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setPipelineName(pipelineName)
                .setCurrentHopNumber(1); // Example: hop was incremented by caller
        // current_pipestep_id is NOT set here, router sets it before forwarding
    }

    @Test
    void routeAndForward_shouldDoNothing_whenNoRoutesDefined() {
        // Arrange: Step A has empty/null routing lists
        stepA_Config.setGrpcForwardTo(Collections.emptyList());
        stepA_Config.setKafkaPublishTopics(Collections.emptyList());

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: Verify no forwarders were called
        verifyNoInteractions(grpcExecutor);
        // Verify KafkaForwarder call based on its actual signature
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString());;
        // Or if signature is forwardToKafka(PipeStream, String):
        // verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString());
    }

    @Test
    void routeAndForward_shouldCallGrpcExecutor_whenGrpcRouteDefined() {
        // Arrange: Step A routes to Step B via gRPC
        stepA_Config.setGrpcForwardTo(List.of(stepB_Id)); // Point to logical step name "stepB"
        stepA_Config.setKafkaPublishTopics(Collections.emptyList());

        // Mock GrpcExecutor to return success immediately
        when(grpcExecutor.forwardPipeStreamAsync(anyString(), any(PipeStream.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: Verify GrpcExecutor was called correctly
        ArgumentCaptor<String> targetServiceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PipeStream> streamCaptor = ArgumentCaptor.forClass(PipeStream.class);

        verify(grpcExecutor, times(1)).forwardPipeStreamAsync(targetServiceCaptor.capture(), streamCaptor.capture());
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString());

        // Check arguments passed to grpcExecutor
        assertEquals(targetGrpcService, stringCaptor.getValue(), "Should target Step B's Consul service name");
        PipeStream forwardedStream = pipeStreamCaptor.getValue();
        assertEquals(streamId, forwardedStream.getStreamId());
        assertEquals(pipelineName, forwardedStream.getPipelineName());
        assertEquals(stepB_Id, forwardedStream.getCurrentPipestepId(), "Should set pipestep ID for receiver"); // Crucial check
        assertEquals(1, forwardedStream.getCurrentHopNumber(), "Hop number should be unchanged by router");
    }

    @Test
    void routeAndForward_shouldCallKafkaForwarder_whenKafkaRouteDefined() {
        // Arrange: Step A routes to Step C via Kafka topic
        KafkaRouteTarget kafkaRoute = new KafkaRouteTarget(targetKafkaTopic, stepC_Id); // Topic and target logical step
        stepA_Config.setGrpcForwardTo(Collections.emptyList());
        stepA_Config.setKafkaPublishTopics(List.of(kafkaRoute));

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: Verify KafkaForwarder was called correctly
        // Adapt verification based on KafkaForwarder's actual method signature
        // Assuming signature: forwardToKafka(String topic, UUID key, PipeStream pipe)
        verify(kafkaForwarder, times(1)).forwardToKafka(pipeStreamCaptor.capture(), stringCaptor.capture());
        verifyNoInteractions(grpcExecutor);

        // Check arguments
        assertEquals(targetKafkaTopic, stringCaptor.getValue());
        assertEquals(UUID.fromString(streamId), uuidCaptor.getValue());
        PipeStream forwardedStream = pipeStreamCaptor.getValue();
        assertEquals(streamId, forwardedStream.getStreamId());
        assertEquals(pipelineName, forwardedStream.getPipelineName());
        assertEquals(stepC_Id, forwardedStream.getCurrentPipestepId(), "Should set pipestep ID for Kafka consumer"); // Crucial check
        assertEquals(1, forwardedStream.getCurrentHopNumber());
    }

    @Test
    void routeAndForward_shouldHandleBothGrpcAndKafkaRoutes() {
        // Arrange: Define both route types
        KafkaRouteTarget kafkaRoute = new KafkaRouteTarget(targetKafkaTopic, stepC_Id);
        stepA_Config.setGrpcForwardTo(List.of(stepB_Id));
        stepA_Config.setKafkaPublishTopics(List.of(kafkaRoute));

        // Mock gRPC executor
        when(grpcExecutor.forwardPipeStreamAsync(anyString(), any(PipeStream.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: Verify both forwarders are called
        ArgumentCaptor<PipeStream> grpcStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        ArgumentCaptor<PipeStream> kafkaStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);

        // Verify gRPC call
        verify(grpcExecutor, times(1)).forwardPipeStreamAsync(eq(targetGrpcService), grpcStreamCaptor.capture());
        // Verify Kafka call (adapt signature as needed)
        verify(kafkaForwarder, times(1)).forwardToKafka(kafkaStreamCaptor.capture(), eq(targetKafkaTopic));

        // Verify correct pipestep_id was set independently for each distinct forward
        assertEquals(stepB_Id, grpcStreamCaptor.getValue().getCurrentPipestepId(), "gRPC stream should target step B");
        assertEquals(stepC_Id, kafkaStreamCaptor.getValue().getCurrentPipestepId(), "Kafka stream should target step C");

        // Verify other base stream details are consistent
        assertEquals(streamId, grpcStreamCaptor.getValue().getStreamId());
        assertEquals(streamId, kafkaStreamCaptor.getValue().getStreamId());
        assertEquals(1, grpcStreamCaptor.getValue().getCurrentHopNumber());
        assertEquals(1, kafkaStreamCaptor.getValue().getCurrentHopNumber());
    }

    @Test
    void routeAndForward_shouldLogErrorAndSkip_whenNextGrpcStepNotFoundInConfig() {
        // Arrange: Route points to a step ID that doesn't exist in the pipeline config map
        String invalidStepId = "nonExistentStep";
        stepA_Config.setGrpcForwardTo(List.of(invalidStepId));
        stepA_Config.setKafkaPublishTopics(Collections.emptyList());

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: Verify executor was NOT called, and an error should have been logged
        verifyNoInteractions(grpcExecutor);
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString()); // Adapt if needed
        // Manual check of logs for "Routing config error! Next step ID 'nonExistentStep' ... not found" expected
    }

    @Test
    void routeAndForward_shouldLogErrorAndSkip_whenNextGrpcStepHasNoServiceImpl() {
        // Arrange: Next step exists but has no serviceImplementation defined
        String stepWithNoImplId = "stepWithNoImpl";
        PipeStepConfigurationDto stepWithNoImplConfig = new PipeStepConfigurationDto();
        stepWithNoImplConfig.setName(stepWithNoImplId);
        stepWithNoImplConfig.setServiceImplementation(null); // Missing implementation
        testPipelineConfig.getServices().put(stepWithNoImplId, stepWithNoImplConfig); // Add to mock config

        stepA_Config.setGrpcForwardTo(List.of(stepWithNoImplId));
        stepA_Config.setKafkaPublishTopics(Collections.emptyList());

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: Verify executor was NOT called, and an error should have been logged
        verifyNoInteractions(grpcExecutor);
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString()); // Adapt if needed
        // Manual check of logs for "Routing config error! Next step ID 'stepWithNoImpl' ... has no serviceImplementation" expected
    }

    @Test
    void routeAndForward_shouldLogErrorAndStop_whenPipelineConfigNotFound() {
        // Arrange: Config service returns null for the pipeline
        when(configurationService.getPipeline(pipelineName)).thenReturn(null);

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: No forwarders called, error logged
        verifyNoInteractions(grpcExecutor);
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString()); // Adapt if needed
        // Manual check of logs for "Cannot route - Pipeline configuration 'test-pipeline' not found." expected
    }

    @Test
    void routeAndForward_shouldHandleNullOrBlankRoutingListsGracefully() {
        // Arrange: Routing lists are null
        stepA_Config.setGrpcForwardTo(null);
        stepA_Config.setKafkaPublishTopics(null);

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: No forwarders called, no exceptions thrown
        verifyNoInteractions(grpcExecutor);
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString()); // Adapt if needed
    }

    @Test
    void routeAndForward_shouldSkipBlankEntriesInGrpcForwardToList() {
        // Arrange: Routing list contains blank/null entries plus a valid one
        stepA_Config.setGrpcForwardTo(List.of("", stepB_Id, "  ", null));
        stepA_Config.setKafkaPublishTopics(Collections.emptyList());

        when(grpcExecutor.forwardPipeStreamAsync(anyString(), any(PipeStream.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: Executor called only ONCE for the valid step ID "stepB"
        verify(grpcExecutor, times(1)).forwardPipeStreamAsync(eq(targetGrpcService), pipeStreamCaptor.capture());
        assertEquals(stepB_Id, pipeStreamCaptor.getValue().getCurrentPipestepId());
    }

    @Test
    void routeAndForward_shouldSkipInvalidKafkaRouteTargets() {
        // Arrange: Kafka routes list contains invalid entries plus a valid one
        List<KafkaRouteTarget> kafkaRoutes = List.of(
                new KafkaRouteTarget(null, "stepX"),                   // Invalid (null topic)
                new KafkaRouteTarget("topicY", null),                   // Invalid (null target step)
                new KafkaRouteTarget("  ", "stepZ"),                   // Invalid (blank topic)
                new KafkaRouteTarget("topicW", " "),                    // Invalid (blank target step)
                new KafkaRouteTarget(targetKafkaTopic, stepC_Id)        // Valid
        );
        stepA_Config.setGrpcForwardTo(Collections.emptyList());
        stepA_Config.setKafkaPublishTopics(kafkaRoutes);

        // Act
        pipelineRouter.routeAndForward(pipelineName, stepA_Config, outgoingStreamBuilder);

        // Assert: KafkaForwarder called only ONCE for the valid route target
        verify(kafkaForwarder, times(1)).forwardToKafka(pipeStreamCaptor.capture(), eq(targetKafkaTopic));
        assertEquals(stepC_Id, pipeStreamCaptor.getValue().getCurrentPipestepId());
    }

}
