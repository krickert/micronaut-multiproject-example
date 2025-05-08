package com.krickert.search.pipeline.router;

import com.krickert.search.config.consul.model.KafkaRouteTarget;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.service.ConfigurationService;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.executor.GrpcPipelineStepExecutor;
import com.krickert.search.pipeline.kafka.KafkaForwarder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PipelineRouter.
 */
@ExtendWith(MockitoExtension.class) // Initialize mocks
class PipelineRouterTest {

    // Mocks for dependencies
    @Mock
    private ConfigurationService configurationService;
    @Mock
    private GrpcPipelineStepExecutor grpcExecutor;
    @Mock
    private KafkaForwarder kafkaForwarder;

    // Inject mocks into the class under test
    @InjectMocks
    private PipelineRouter pipelineRouter;

    private PipelineConfigDto testPipelineConfig;
    private PipeStepConfigurationDto completedStepConfig;
    private PipeStepConfigurationDto nextGrpcStepConfig;
    private PipeStream.Builder outgoingStreamBuilder;
    private String pipelineName = "test-pipeline";
    private String streamId = UUID.randomUUID().toString();
    private String completedStepId = "stepA";
    private String nextGrpcStepId = "stepB";
    private String nextKafkaStepId = "stepC"; // Logical step ID for Kafka consumer
    private String targetKafkaTopic = "topic-for-stepC";
    private String targetGrpcService = "service-for-stepB";

    @BeforeEach
    void setUp() {
        // Reset mocks if needed (though @ExtendWith handles basic setup)
        // Mockito.reset(configurationService, grpcExecutor, kafkaForwarder);

        // --- Setup common test data ---

        // Create a base outgoing stream builder
        outgoingStreamBuilder = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setPipelineName(pipelineName)
                .setCurrentHopNumber(1); // Assuming step A was hop 0

        // Config for the step that just completed ("stepA")
        completedStepConfig = new PipeStepConfigurationDto();
        completedStepConfig.setName(completedStepId);
        completedStepConfig.setServiceImplementation("service-A-impl");
        // Routing rules will be set per test case

        // Config for the next potential gRPC step ("stepB")
        nextGrpcStepConfig = new PipeStepConfigurationDto();
        nextGrpcStepConfig.setName(nextGrpcStepId);
        nextGrpcStepConfig.setServiceImplementation(targetGrpcService); // Consul app name for step B

        // Full pipeline config DTO containing the steps
        testPipelineConfig = new PipelineConfigDto(pipelineName);
        testPipelineConfig.getServices().put(completedStepId, completedStepConfig);
        testPipelineConfig.getServices().put(nextGrpcStepId, nextGrpcStepConfig);
        // Add other steps if needed for multi-hop tests later

        // Mock ConfigurationService to return the test pipeline config
        when(configurationService.getPipeline(pipelineName)).thenReturn(testPipelineConfig);
    }

    @Test
    void routeAndForward_shouldDoNothing_whenNoRoutesDefined() {
        // Arrange: Completed step has no routing rules
        completedStepConfig.setGrpcForwardTo(Collections.emptyList());
        completedStepConfig.setKafkaPublishTopics(Collections.emptyList());

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: Verify no forwarders were called
        verify(grpcExecutor, never()).forwardPipeStreamAsync(anyString(), any(PipeStream.class));
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString());;
        // Could also capture logs to verify terminal step message
    }

    @Test
    void routeAndForward_shouldCallGrpcExecutor_whenGrpcRouteDefined() {
        // Arrange: Define gRPC route to nextLogicalStepId ("stepB")
        completedStepConfig.setGrpcForwardTo(List.of(nextGrpcStepId));
        completedStepConfig.setKafkaPublishTopics(Collections.emptyList());

        // Mock GrpcPipelineStepExecutor to return a completed future
        when(grpcExecutor.forwardPipeStreamAsync(anyString(), any(PipeStream.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: Verify GrpcExecutor was called correctly
        ArgumentCaptor<String> targetServiceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PipeStream> streamCaptor = ArgumentCaptor.forClass(PipeStream.class);

        verify(grpcExecutor, times(1)).forwardPipeStreamAsync(targetServiceCaptor.capture(), streamCaptor.capture());
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString());

        // Check arguments passed to grpcExecutor
        assertEquals(targetGrpcService, targetServiceCaptor.getValue()); // Correct Consul app name?
        PipeStream forwardedStream = streamCaptor.getValue();
        assertEquals(streamId, forwardedStream.getStreamId());
        assertEquals(pipelineName, forwardedStream.getPipelineName());
        assertEquals(nextGrpcStepId, forwardedStream.getCurrentPipestepId()); // Crucial: ID for receiver set?
        assertEquals(1, forwardedStream.getCurrentHopNumber()); // Hop number unchanged by router
    }

    @Test
    void routeAndForward_shouldCallKafkaForwarder_whenKafkaRouteDefined() {
        // Arrange: Define Kafka route
        KafkaRouteTarget kafkaRoute = new KafkaRouteTarget(targetKafkaTopic, nextKafkaStepId);
        completedStepConfig.setGrpcForwardTo(Collections.emptyList());
        completedStepConfig.setKafkaPublishTopics(List.of(kafkaRoute));

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: Verify KafkaForwarder was called correctly
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> keyCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<PipeStream> streamCaptor = ArgumentCaptor.forClass(PipeStream.class);

        // Use doNothing() for void methods if needed, or just verify call
        // doNothing().when(kafkaForwarder).forwardToKafka(anyString(), any(UUID.class), any(PipeStream.class));
        verify(kafkaForwarder, times(1)).forwardToKafka(streamCaptor.capture(), topicCaptor.capture());
        verify(grpcExecutor, never()).forwardPipeStreamAsync(anyString(), any(PipeStream.class));

        // Check arguments passed to kafkaForwarder
        assertEquals(targetKafkaTopic, topicCaptor.getValue());
        assertEquals(UUID.fromString(streamId), keyCaptor.getValue()); // Assuming key is streamId UUID
        PipeStream forwardedStream = streamCaptor.getValue();
        assertEquals(streamId, forwardedStream.getStreamId());
        assertEquals(pipelineName, forwardedStream.getPipelineName());
        assertEquals(nextKafkaStepId, forwardedStream.getCurrentPipestepId()); // Crucial: ID for receiver set?
        assertEquals(1, forwardedStream.getCurrentHopNumber());
    }

    @Test
    void routeAndForward_shouldHandleBothGrpcAndKafkaRoutes() {
        // Arrange: Define both route types
        KafkaRouteTarget kafkaRoute = new KafkaRouteTarget(targetKafkaTopic, nextKafkaStepId);
        completedStepConfig.setGrpcForwardTo(List.of(nextGrpcStepId));
        completedStepConfig.setKafkaPublishTopics(List.of(kafkaRoute));

        when(grpcExecutor.forwardPipeStreamAsync(anyString(), any(PipeStream.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: Verify both forwarders are called
        ArgumentCaptor<PipeStream> grpcStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        ArgumentCaptor<PipeStream> kafkaStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);

        verify(grpcExecutor, times(1)).forwardPipeStreamAsync(eq(targetGrpcService), grpcStreamCaptor.capture());
        verify(kafkaForwarder, times(1)).forwardToKafka(kafkaStreamCaptor.capture(), eq(targetKafkaTopic));

        // Verify correct pipestep_id was set for each distinct forward
        assertEquals(nextGrpcStepId, grpcStreamCaptor.getValue().getCurrentPipestepId());
        assertEquals(nextKafkaStepId, kafkaStreamCaptor.getValue().getCurrentPipestepId());

        // Verify other base stream details are consistent
        assertEquals(streamId, grpcStreamCaptor.getValue().getStreamId());
        assertEquals(streamId, kafkaStreamCaptor.getValue().getStreamId());
        assertEquals(1, grpcStreamCaptor.getValue().getCurrentHopNumber());
        assertEquals(1, kafkaStreamCaptor.getValue().getCurrentHopNumber());
    }

    @Test
    void routeAndForward_shouldLogError_whenNextGrpcStepNotFoundInConfig() {
        // Arrange: Route points to a step ID that doesn't exist in the pipeline config
        String invalidStepId = "nonExistentStep";
        completedStepConfig.setGrpcForwardTo(List.of(invalidStepId));
        completedStepConfig.setKafkaPublishTopics(Collections.emptyList());

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: Verify executor was NOT called, and an error was logged (difficult to assert log directly without framework/captor)
        verify(grpcExecutor, never()).forwardPipeStreamAsync(anyString(), any(PipeStream.class));
        // We expect an ERROR log message containing "Routing config error! Next step ID 'nonExistentStep'"
    }

     @Test
    void routeAndForward_shouldLogError_whenNextGrpcStepHasNoServiceImpl() {
        // Arrange: Next step exists but has no serviceImplementation defined
        String stepWithNoImplId = "stepWithNoImpl";
        PipeStepConfigurationDto stepWithNoImplConfig = new PipeStepConfigurationDto();
        stepWithNoImplConfig.setName(stepWithNoImplId);
        stepWithNoImplConfig.setServiceImplementation(null); // Missing implementation
        testPipelineConfig.getServices().put(stepWithNoImplId, stepWithNoImplConfig);

        completedStepConfig.setGrpcForwardTo(List.of(stepWithNoImplId));
        completedStepConfig.setKafkaPublishTopics(Collections.emptyList());

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: Verify executor was NOT called, and an error was logged
        verify(grpcExecutor, never()).forwardPipeStreamAsync(anyString(), any(PipeStream.class));
         // We expect an ERROR log message containing "Routing config error! Next step ID 'stepWithNoImpl' ... has no serviceImplementation"
    }

     @Test
    void routeAndForward_shouldLogError_whenPipelineConfigNotFound() {
        // Arrange: Config service returns null for the pipeline
        when(configurationService.getPipeline(pipelineName)).thenReturn(null);

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: No forwarders called, error logged
        verify(grpcExecutor, never()).forwardPipeStreamAsync(anyString(), any(PipeStream.class));
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString());
        // We expect an ERROR log message containing "Cannot route - Pipeline configuration 'test-pipeline' not found."
    }

     @Test
    void routeAndForward_shouldHandleNullOrBlankRoutingLists() {
        // Arrange: Routing lists are null
        completedStepConfig.setGrpcForwardTo(null);
        completedStepConfig.setKafkaPublishTopics(null);

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: No forwarders called
        verify(grpcExecutor, never()).forwardPipeStreamAsync(anyString(), any(PipeStream.class));
        verify(kafkaForwarder, never()).forwardToKafka(any(PipeStream.class), anyString());
    }

     @Test
    void routeAndForward_shouldHandleBlankStringsInGrpcForwardTo() {
        // Arrange: Routing list contains blank/null entries
        completedStepConfig.setGrpcForwardTo(List.of(nextGrpcStepId, "", "  ", null));
        completedStepConfig.setKafkaPublishTopics(Collections.emptyList());

        when(grpcExecutor.forwardPipeStreamAsync(anyString(), any(PipeStream.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: Executor called only ONCE for the valid step ID
        verify(grpcExecutor, times(1)).forwardPipeStreamAsync(eq(targetGrpcService), any(PipeStream.class));
    }

     @Test
    void routeAndForward_shouldHandleInvalidKafkaRouteTargets() {
        // Arrange: Kafka routes list contains invalid entries
        List<KafkaRouteTarget> kafkaRoutes = List.of(
            new KafkaRouteTarget(targetKafkaTopic, nextKafkaStepId), // Valid
            new KafkaRouteTarget(null, "stepX"),                   // Invalid (null topic)
            new KafkaRouteTarget("topicY", null),                   // Invalid (null target step)
            new KafkaRouteTarget("  ", "stepZ"),                   // Invalid (blank topic)
            new KafkaRouteTarget("topicW", " ")                    // Invalid (blank target step)
        );
        completedStepConfig.setGrpcForwardTo(Collections.emptyList());
        completedStepConfig.setKafkaPublishTopics(kafkaRoutes);

        // Act
        pipelineRouter.routeAndForward(pipelineName, completedStepConfig, outgoingStreamBuilder);

        // Assert: KafkaForwarder called only ONCE for the valid route target
        verify(kafkaForwarder, times(1)).forwardToKafka(any(PipeStream.class), eq(targetKafkaTopic));
    }

}
