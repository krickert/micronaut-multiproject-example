package com.krickert.search.engine.service.impl;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.registration.ModuleRegistrationService;
import com.krickert.search.engine.routing.ModuleConnector;
import com.krickert.search.engine.routing.PipelineRouter;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.model.*;
import com.krickert.search.sdk.ProcessResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kiwiproject.consul.model.agent.FullService;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.Mockito;

@ExtendWith(MockitoExtension.class)
class MessageRoutingServiceImplTest {
    
    @Mock
    private ModuleRegistrationService registrationService;
    
    @Mock
    private ConsulBusinessOperationsService consulService;
    
    @Mock
    private PipelineRouter pipelineRouter;
    
    @Mock
    private ModuleConnector moduleConnector;
    
    @Mock
    private MessageRoutingServiceImpl.KafkaMessageProducer kafkaProducer;
    
    private MessageRoutingServiceImpl messageRoutingService;
    
    private static final String TEST_CLUSTER = "test-cluster";
    private static final String TEST_PIPELINE = "test-pipeline";
    private static final String TEST_MODULE_ID = "test-module";
    
    @BeforeEach
    void setup() {
        messageRoutingService = new MessageRoutingServiceImpl(
            registrationService,
            consulService,
            pipelineRouter,
            moduleConnector,
            kafkaProducer,
            TEST_CLUSTER
        );
    }
    
    @Test
    @DisplayName("Should route message through pipeline successfully")
    void testRouteMessageSuccess() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        PipelineConfig pipelineConfig = createTestPipelineConfig();
        PipelineStepConfig nextStep = pipelineConfig.pipelineSteps().get("step1");
        
        // Mock pipeline config loading
        when(consulService.getSpecificPipelineConfig(TEST_CLUSTER, TEST_PIPELINE))
            .thenReturn(Mono.just(Optional.of(pipelineConfig)));
        
        // Mock pipeline routing - first time not complete, has next step
        when(pipelineRouter.isPipelineComplete(any(PipeStream.class), eq(pipelineConfig)))
            .thenReturn(false, true); // First call returns false, second returns true
        when(pipelineRouter.getNextStep(any(PipeStream.class), eq(pipelineConfig)))
            .thenReturn(Optional.of(nextStep));
        
        // Mock module lookup
        when(registrationService.isModuleHealthy(TEST_MODULE_ID))
            .thenReturn(Mono.just(true));
        
        FullService fullService = mock(FullService.class);
        when(fullService.getService()).thenReturn("Test Service");
        when(fullService.getAddress()).thenReturn("localhost");
        when(fullService.getPort()).thenReturn(8080);
        
        when(consulService.getAgentServiceDetails(TEST_MODULE_ID))
            .thenReturn(Mono.just(Optional.of(fullService)));
        
        // Mock module processing
        ProcessResponse processResponse = ProcessResponse.newBuilder()
            .setSuccess(true)
            .setOutputDoc(inputStream.getDocument().toBuilder()
                .addKeywords("processed")
                .build())
            .build();
        
        when(moduleConnector.processDocument(
            any(ModuleInfo.class),
            any(PipeStream.class),
            eq("step1"),
            any(Map.class)
        )).thenReturn(Mono.just(processResponse));
        
        // Execute
        StepVerifier.create(messageRoutingService.routeMessage(inputStream, TEST_PIPELINE))
            .assertNext(resultStream -> {
                assertNotNull(resultStream);
                assertTrue(resultStream.getDocument().getKeywordsList().contains("processed"));
                assertEquals(1, resultStream.getHistoryCount());
            })
            .verifyComplete();
        
        // Verify interactions
        verify(consulService).getSpecificPipelineConfig(TEST_CLUSTER, TEST_PIPELINE);
        verify(moduleConnector).processDocument(any(), any(), eq("step1"), any());
    }
    
    @Test
    @DisplayName("Should handle pipeline not found")
    void testRouteMessagePipelineNotFound() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        
        // Mock pipeline config not found
        when(consulService.getSpecificPipelineConfig(TEST_CLUSTER, TEST_PIPELINE))
            .thenReturn(Mono.just(Optional.empty()));
        
        // Execute - the implementation returns a stream with error data instead of throwing
        StepVerifier.create(messageRoutingService.routeMessage(inputStream, TEST_PIPELINE))
            .assertNext(resultStream -> {
                assertNotNull(resultStream);
                assertTrue(resultStream.hasStreamErrorData());
                assertEquals("Pipeline configuration not found: test-pipeline", 
                    resultStream.getStreamErrorData().getErrorMessage());
                assertEquals("routing", resultStream.getStreamErrorData().getOriginatingStepName());
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle module processing failure")
    void testRouteMessageModuleFailure() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        PipelineConfig pipelineConfig = createTestPipelineConfig();
        PipelineStepConfig nextStep = pipelineConfig.pipelineSteps().get("step1");
        
        // Mock pipeline config loading
        when(consulService.getSpecificPipelineConfig(TEST_CLUSTER, TEST_PIPELINE))
            .thenReturn(Mono.just(Optional.of(pipelineConfig)));
        
        // Mock pipeline routing
        when(pipelineRouter.isPipelineComplete(any(PipeStream.class), eq(pipelineConfig)))
            .thenReturn(false);
        when(pipelineRouter.getNextStep(any(PipeStream.class), eq(pipelineConfig)))
            .thenReturn(Optional.of(nextStep));
        
        // Mock module lookup
        lenient().when(registrationService.isModuleHealthy(TEST_MODULE_ID))
            .thenReturn(Mono.just(true));
        
        FullService fullService = mock(FullService.class);
        lenient().when(fullService.getService()).thenReturn("Test Service");
        lenient().when(fullService.getAddress()).thenReturn("localhost");
        lenient().when(fullService.getPort()).thenReturn(8080);
        
        lenient().when(consulService.getAgentServiceDetails(TEST_MODULE_ID))
            .thenReturn(Mono.just(Optional.of(fullService)));
        
        // Mock module processing failure
        when(moduleConnector.processDocument(
            any(ModuleInfo.class),
            any(PipeStream.class),
            eq("step1"),
            any(Map.class)
        )).thenReturn(Mono.error(new RuntimeException("Module processing failed")));
        
        // Execute
        StepVerifier.create(messageRoutingService.routeMessage(inputStream, TEST_PIPELINE))
            .assertNext(resultStream -> {
                assertNotNull(resultStream);
                assertTrue(resultStream.hasStreamErrorData());
                assertEquals("Module processing failed", resultStream.getStreamErrorData().getErrorMessage());
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should send message directly to module")
    void testSendToModuleSuccess() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        
        // Mock module health check
        when(registrationService.isModuleHealthy(TEST_MODULE_ID))
            .thenReturn(Mono.just(true));
        
        // Mock service details
        FullService fullService = mock(FullService.class);
        lenient().when(fullService.getService()).thenReturn("Test Service");
        lenient().when(fullService.getAddress()).thenReturn("localhost");
        lenient().when(fullService.getPort()).thenReturn(8080);
        
        when(consulService.getAgentServiceDetails(TEST_MODULE_ID))
            .thenReturn(Mono.just(Optional.of(fullService)));
        
        // Mock module processing
        ProcessResponse processResponse = ProcessResponse.newBuilder()
            .setSuccess(true)
            .setOutputDoc(inputStream.getDocument().toBuilder()
                .addKeywords("direct-processed")
                .build())
            .build();
        
        when(moduleConnector.processDocument(
            any(ModuleInfo.class),
            any(PipeStream.class),
            eq("direct-call"),
            any(Map.class)
        )).thenReturn(Mono.just(processResponse));
        
        // Execute
        StepVerifier.create(messageRoutingService.sendToModule(inputStream, TEST_MODULE_ID))
            .assertNext(resultStream -> {
                assertNotNull(resultStream);
                assertTrue(resultStream.getDocument().getKeywordsList().contains("direct-processed"));
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle unhealthy module")
    void testSendToModuleUnhealthy() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        
        // Mock module as unhealthy
        when(registrationService.isModuleHealthy(TEST_MODULE_ID))
            .thenReturn(Mono.just(false));
        
        // Execute
        StepVerifier.create(messageRoutingService.sendToModule(inputStream, TEST_MODULE_ID))
            .expectErrorMatches(throwable -> 
                throwable instanceof IllegalStateException &&
                throwable.getMessage().contains("is not healthy"))
            .verify();
    }
    
    @Test
    @DisplayName("Should send message to Kafka topic")
    void testSendToKafkaTopicSuccess() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        String topicName = "test-topic";
        
        // Execute
        StepVerifier.create(messageRoutingService.sendToKafkaTopic(inputStream, topicName))
            .expectNext(true)
            .verifyComplete();
        
        // Verify Kafka interaction
        verify(kafkaProducer).sendMessage(
            eq(topicName),
            eq(inputStream.getDocument().getId()),
            any(byte[].class)
        );
    }
    
    @Test
    @DisplayName("Should handle Kafka send failure")
    void testSendToKafkaTopicFailure() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        String topicName = "test-topic";
        
        // Mock Kafka failure
        doThrow(new RuntimeException("Kafka error"))
            .when(kafkaProducer).sendMessage(anyString(), anyString(), any(byte[].class));
        
        // Execute
        StepVerifier.create(messageRoutingService.sendToKafkaTopic(inputStream, topicName))
            .expectNext(false)
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should cache pipeline configuration")
    void testPipelineCaching() {
        // Setup
        PipeStream inputStream = createTestPipeStream();
        PipelineConfig pipelineConfig = createTestPipelineConfig();
        
        // Mock pipeline config loading
        when(consulService.getSpecificPipelineConfig(TEST_CLUSTER, TEST_PIPELINE))
            .thenReturn(Mono.just(Optional.of(pipelineConfig)));
        
        // Mock pipeline as complete
        when(pipelineRouter.isPipelineComplete(any(PipeStream.class), eq(pipelineConfig)))
            .thenReturn(true);
        
        // Execute twice
        StepVerifier.create(messageRoutingService.routeMessage(inputStream, TEST_PIPELINE))
            .expectNextCount(1)
            .verifyComplete();
        
        StepVerifier.create(messageRoutingService.routeMessage(inputStream, TEST_PIPELINE))
            .expectNextCount(1)
            .verifyComplete();
        
        // Verify config was only loaded once (cached)
        verify(consulService, times(1)).getSpecificPipelineConfig(TEST_CLUSTER, TEST_PIPELINE);
    }
    
    private PipeStream createTestPipeStream() {
        return PipeStream.newBuilder()
            .setStreamId("stream-123")
            .setDocument(PipeDoc.newBuilder()
                .setId("doc-123")
                .setTitle("Test Document")
                .setBody("Test content")
                .build())
            .setCurrentPipelineName(TEST_PIPELINE)
            .setCurrentHopNumber(0)
            .build();
    }
    
    private PipelineConfig createTestPipelineConfig() {
        Map<String, PipelineStepConfig> steps = new TreeMap<>();
        
        PipelineStepConfig.ProcessorInfo processorInfo = 
            new PipelineStepConfig.ProcessorInfo(TEST_MODULE_ID, null);
        
        PipelineStepConfig step1 = new PipelineStepConfig(
            "step1",
            StepType.PIPELINE,
            processorInfo,
            new PipelineStepConfig.JsonConfigOptions(Map.of("key", "value"))
        );
        
        steps.put("step1", step1);
        
        return new PipelineConfig(TEST_PIPELINE, steps);
    }
}