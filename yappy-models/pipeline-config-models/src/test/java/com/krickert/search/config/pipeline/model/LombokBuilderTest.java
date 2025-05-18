package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that the Lombok @Builder annotation works correctly with the record types.
 */
public class LombokBuilderTest {

    @Test
    void testSchemaReferenceBuilder() {
        SchemaReference schemaReference = SchemaReference.builder()
                .subject("test-subject")
                .version(1)
                .build();

        assertEquals("test-subject", schemaReference.subject());
        assertEquals(1, schemaReference.version());
    }

    @Test
    void testKafkaPublishTopicBuilder() {
        KafkaPublishTopic kafkaPublishTopic = KafkaPublishTopic.builder()
                .topic("test-topic")
                .build();

        assertEquals("test-topic", kafkaPublishTopic.topic());
    }

    @Test
    void testKafkaInputDefinitionBuilder() {
        KafkaInputDefinition kafkaInputDefinition = KafkaInputDefinition.builder()
                .listenTopics(List.of("test-topic"))
                .consumerGroupId("test-group")
                .kafkaConsumerProperties(Map.of("key", "value"))
                .build();

        assertEquals(List.of("test-topic"), kafkaInputDefinition.listenTopics());
        assertEquals("test-group", kafkaInputDefinition.consumerGroupId());
        assertEquals(Map.of("key", "value"), kafkaInputDefinition.kafkaConsumerProperties());
    }

    @Test
    void testGrpcTransportConfigBuilder() {
        GrpcTransportConfig grpcTransportConfig = GrpcTransportConfig.builder()
                .serviceName("test-service")
                .grpcClientProperties(Map.of("key", "value"))
                .build();

        assertEquals("test-service", grpcTransportConfig.serviceName());
        assertEquals(Map.of("key", "value"), grpcTransportConfig.grpcClientProperties());
    }

    @Test
    void testKafkaTransportConfigBuilder() {
        KafkaTransportConfig kafkaTransportConfig = KafkaTransportConfig.builder()
                .topic("test-topic")
                .kafkaProducerProperties(Map.of("key", "value"))
                .build();

        assertEquals("test-topic", kafkaTransportConfig.topic());
        assertEquals(Map.of("key", "value"), kafkaTransportConfig.kafkaProducerProperties());
    }

    @Test
    void testPipelineStepConfigInnerRecordBuilders() {
        // Test OutputTarget builder
        PipelineStepConfig.OutputTarget outputTarget = PipelineStepConfig.OutputTarget.builder()
                .targetStepName("test-target")
                .transportType(TransportType.GRPC)
                .grpcTransport(GrpcTransportConfig.builder().serviceName("test-service").build())
                .build();

        assertEquals("test-target", outputTarget.targetStepName());
        assertEquals(TransportType.GRPC, outputTarget.transportType());
        assertEquals("test-service", outputTarget.grpcTransport().serviceName());

        // Test JsonConfigOptions builder
        JsonNode jsonNode = JsonNodeFactory.instance.objectNode();
        Map<String, String> configParams = Map.of("key", "value");
        PipelineStepConfig.JsonConfigOptions jsonConfigOptions = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(jsonNode)
                .configParams(configParams)
                .build();

        assertEquals(jsonNode, jsonConfigOptions.jsonConfig());
        assertEquals(configParams, jsonConfigOptions.configParams());

        // Test ProcessorInfo builder
        PipelineStepConfig.ProcessorInfo processorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("test-service")
                .build();

        assertEquals("test-service", processorInfo.grpcServiceName());
        assertNull(processorInfo.internalProcessorBeanName());
    }

    @Test
    void testPipelineStepConfigBuilder() {
        PipelineStepConfig.ProcessorInfo processorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("test-service")
                .build();

        PipelineStepConfig pipelineStepConfig = PipelineStepConfig.builder()
                .stepName("test-step")
                .stepType(StepType.PIPELINE)
                .description("Test step")
                .processorInfo(processorInfo)
                .build();

        assertEquals("test-step", pipelineStepConfig.stepName());
        assertEquals(StepType.PIPELINE, pipelineStepConfig.stepType());
        assertEquals("Test step", pipelineStepConfig.description());
        assertEquals("test-service", pipelineStepConfig.processorInfo().grpcServiceName());
    }

    @Test
    void testPipelineConfigBuilder() {
        PipelineStepConfig.ProcessorInfo processorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("test-service")
                .build();

        PipelineStepConfig pipelineStepConfig = PipelineStepConfig.builder()
                .stepName("test-step")
                .stepType(StepType.PIPELINE)
                .processorInfo(processorInfo)
                .build();

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("test-step", pipelineStepConfig);

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name("test-pipeline")
                .pipelineSteps(steps)
                .build();

        assertEquals("test-pipeline", pipelineConfig.name());
        assertEquals(1, pipelineConfig.pipelineSteps().size());
        assertEquals("test-step", pipelineConfig.pipelineSteps().get("test-step").stepName());
    }

    @Test
    void testPipelineGraphConfigBuilder() {
        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name("test-pipeline")
                .pipelineSteps(Collections.emptyMap())
                .build();

        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("test-pipeline", pipelineConfig);

        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
                .pipelines(pipelines)
                .build();

        assertEquals(1, pipelineGraphConfig.pipelines().size());
        assertEquals("test-pipeline", pipelineGraphConfig.pipelines().get("test-pipeline").name());
    }

    @Test
    void testPipelineClusterConfigBuilder() {
        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
                .pipelines(Collections.emptyMap())
                .build();

        PipelineModuleMap pipelineModuleMap = PipelineModuleMap.builder()
                .availableModules(Collections.emptyMap())
                .build();

        PipelineClusterConfig pipelineClusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(pipelineGraphConfig)
                .pipelineModuleMap(pipelineModuleMap)
                .defaultPipelineName("test-pipeline")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();

        assertEquals("test-cluster", pipelineClusterConfig.clusterName());
        assertEquals("test-pipeline", pipelineClusterConfig.defaultPipelineName());
        assertNotNull(pipelineClusterConfig.pipelineGraphConfig());
        assertNotNull(pipelineClusterConfig.pipelineModuleMap());
    }
}