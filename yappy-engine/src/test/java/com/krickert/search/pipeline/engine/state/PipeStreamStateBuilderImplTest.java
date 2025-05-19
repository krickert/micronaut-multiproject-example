package com.krickert.search.pipeline.engine.state;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.common.RouteData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipeStreamStateBuilderImplTest {

    @Mock
    private DynamicConfigurationManager mockConfigManager;

    private PipeStreamStateBuilderImpl stateBuilder;
    private PipeStream requestState;

    private final String pipelineName = "test-pipeline";
    private final String stepName = "test-step";
    private final String streamId = "test-stream";

    @BeforeEach
    void setUp() {
        requestState = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .setCurrentHopNumber(1)
                .setDocument(PipeDoc.newBuilder().setId("test-doc").build())
                .build();

        stateBuilder = new PipeStreamStateBuilderImpl(requestState, mockConfigManager);
    }

    @Test
    void getRequestState_shouldReturnOriginalRequest() {
        assertEquals(requestState, stateBuilder.getRequestState());
    }

    @Test
    void getPresentState_shouldReturnMutableCopyOfRequest() {
        PipeStream.Builder presentState = stateBuilder.getPresentState();
        assertNotNull(presentState);
        assertEquals(requestState.getStreamId(), presentState.getStreamId());
        assertEquals(requestState.getCurrentPipelineName(), presentState.getCurrentPipelineName());
        assertEquals(requestState.getTargetStepName(), presentState.getTargetStepName());
        assertEquals(requestState.getCurrentHopNumber(), presentState.getCurrentHopNumber());
        assertEquals(requestState.getDocument(), presentState.getDocument());
    }

    @Test
    void withHopNumber_shouldUpdateHopNumber() {
        stateBuilder.withHopNumber(2);
        assertEquals(2, stateBuilder.getPresentState().getCurrentHopNumber());
    }

    @Test
    void withTargetStep_shouldUpdateTargetStep() {
        String newStepName = "new-step";
        stateBuilder.withTargetStep(newStepName);
        assertEquals(newStepName, stateBuilder.getPresentState().getTargetStepName());
    }

    @Test
    void addLogEntry_shouldAddLogToHistory() {
        String logEntry = "Test log entry";
        stateBuilder.addLogEntry(logEntry);

        PipeStream.Builder presentState = stateBuilder.getPresentState();
        assertTrue(presentState.getHistoryCount() > 0);
        assertEquals(logEntry, presentState.getHistory(0).getProcessorLogs(0));
        assertEquals(stepName, presentState.getHistory(0).getStepName());
        assertEquals(1, presentState.getHistory(0).getHopNumber());
        assertEquals("SUCCESS", presentState.getHistory(0).getStatus());
    }

    @Test
    void calculateNextRoutes_shouldReturnEmptyListWhenPipelineNotFound() {
        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.empty());

        List<RouteData> routes = stateBuilder.calculateNextRoutes();

        assertTrue(routes.isEmpty());
        verify(mockConfigManager).getPipelineConfig(pipelineName);
    }

    @Test
    void calculateNextRoutes_shouldReturnEmptyListWhenStepNotFound() {
        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(new HashMap<>())
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        List<RouteData> routes = stateBuilder.calculateNextRoutes();

        assertTrue(routes.isEmpty());
        verify(mockConfigManager).getPipelineConfig(pipelineName);
    }

    @Test
    void calculateNextRoutes_shouldReturnGrpcRoutes() {
        // Setup step with GRPC output
        String targetStepName = "target-step";
        String serviceName = "target-service";

        GrpcTransportConfig grpcConfig = new GrpcTransportConfig(
                serviceName, null);

        PipelineStepConfig.OutputTarget outputTarget = new PipelineStepConfig.OutputTarget(
                targetStepName, TransportType.GRPC, grpcConfig, null);

        HashMap<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        outputs.put("default", outputTarget);

        PipelineStepConfig stepConfig = new PipelineStepConfig(
                stepName,
                StepType.PIPELINE,
                "Test Step",
                null,
                null,
                null,
                outputs,
                null,
                null,
                null,
                null,
                null,
                new PipelineStepConfig.ProcessorInfo("test-service", null)
        );

        HashMap<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(stepName, stepConfig);

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(steps)
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        List<RouteData> routes = stateBuilder.calculateNextRoutes();

        assertEquals(1, routes.size());
        RouteData route = routes.get(0);
        assertEquals(pipelineName, route.targetPipeline());
        assertEquals(targetStepName, route.nextTargetStep());
        assertEquals(serviceName, route.destination());
        assertEquals(streamId, route.streamId());
        assertEquals(TransportType.GRPC, route.transportType());

        verify(mockConfigManager).getPipelineConfig(pipelineName);
    }

    @Test
    void calculateNextRoutes_shouldReturnKafkaRoutes() {
        // Setup step with Kafka output
        String targetStepName = "target-step";
        String topicName = "target-topic";

        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(
                topicName, null);

        PipelineStepConfig.OutputTarget outputTarget = new PipelineStepConfig.OutputTarget(
                targetStepName, TransportType.KAFKA, null, kafkaConfig);

        HashMap<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        outputs.put("default", outputTarget);

        PipelineStepConfig stepConfig = new PipelineStepConfig(
                stepName,
                StepType.PIPELINE,
                "Test Step",
                null,
                null,
                null,
                outputs,
                null,
                null,
                null,
                null,
                null,
                new PipelineStepConfig.ProcessorInfo("test-service", null)
        );

        HashMap<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(stepName, stepConfig);

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(steps)
                .build();

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        List<RouteData> routes = stateBuilder.calculateNextRoutes();

        assertEquals(1, routes.size());
        RouteData route = routes.get(0);
        assertEquals(pipelineName, route.targetPipeline());
        assertEquals(targetStepName, route.nextTargetStep());
        assertEquals(topicName, route.destination());
        assertEquals(streamId, route.streamId());
        assertEquals(TransportType.KAFKA, route.transportType());

        verify(mockConfigManager).getPipelineConfig(pipelineName);
    }

    @Test
    void build_shouldReturnFinalState() {
        String newStepName = "new-step";
        stateBuilder.withTargetStep(newStepName);
        stateBuilder.withHopNumber(2);
        stateBuilder.addLogEntry("Test log entry");

        PipeStream result = stateBuilder.build();

        assertNotNull(result);
        assertEquals(streamId, result.getStreamId());
        assertEquals(pipelineName, result.getCurrentPipelineName());
        assertEquals(newStepName, result.getTargetStepName());
        assertEquals(2, result.getCurrentHopNumber());
        assertEquals(1, result.getHistoryCount());
        assertEquals("Test log entry", result.getHistory(0).getProcessorLogs(0));
    }
}
