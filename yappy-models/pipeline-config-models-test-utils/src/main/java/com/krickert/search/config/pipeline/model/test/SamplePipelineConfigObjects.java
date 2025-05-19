package com.krickert.search.config.pipeline.model.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.krickert.search.config.pipeline.model.*;

import java.util.*;

/**
 * Provides sample pipeline configuration objects created using builders.
 * These objects can be used for testing.
 */
public class SamplePipelineConfigObjects {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Creates a minimal PipelineClusterConfig with a single pipeline and a single step.
     */
    public static PipelineClusterConfig createMinimalPipelineClusterConfig() {
        // Create a minimal pipeline step
        PipelineStepConfig.ProcessorInfo processorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("minimal-service")
                .build();

        PipelineStepConfig minimalStep = PipelineStepConfig.builder()
                .stepName("minimal-step")
                .stepType(StepType.PIPELINE)
                .description("A minimal pipeline step")
                .processorInfo(processorInfo)
                .build();

        // Create a pipeline with the minimal step
        Map<String, PipelineStepConfig> pipelineSteps = new HashMap<>();
        pipelineSteps.put(minimalStep.stepName(), minimalStep);

        PipelineConfig minimalPipeline = PipelineConfig.builder()
                .name("minimal-pipeline")
                .pipelineSteps(pipelineSteps)
                .build();

        // Create a pipeline graph with the minimal pipeline
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(minimalPipeline.name(), minimalPipeline);

        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
                .pipelines(pipelines)
                .build();

        // Create a module map with the minimal service
        PipelineModuleConfiguration minimalModule = PipelineModuleConfiguration.builder()
                .implementationName("Minimal Service")
                .implementationId("minimal-service")
                .build();

        Map<String, PipelineModuleConfiguration> availableModules = new HashMap<>();
        availableModules.put(minimalModule.implementationId(), minimalModule);

        PipelineModuleMap pipelineModuleMap = PipelineModuleMap.builder()
                .availableModules(availableModules)
                .build();

        // Create the cluster config
        return PipelineClusterConfig.builder()
                .clusterName("minimal-cluster")
                .pipelineGraphConfig(pipelineGraphConfig)
                .pipelineModuleMap(pipelineModuleMap)
                .defaultPipelineName("minimal-pipeline")
                .allowedGrpcServices(Set.of("minimal-service"))
                .build();
    }

    /**
     * Creates a PipelineClusterConfig for a search indexing pipeline.
     */
    public static PipelineClusterConfig createSearchIndexingPipelineClusterConfig() {
        // Create the file connector step
        ObjectNode fileConnectorConfig = OBJECT_MAPPER.createObjectNode();
        fileConnectorConfig.put("directory", "/data/incoming");
        fileConnectorConfig.put("pollingIntervalMs", 5000);
        fileConnectorConfig.putArray("filePatterns")
                .add("*.pdf")
                .add("*.docx")
                .add("*.txt")
                .add("*.html")
                .add("*.xml");

        PipelineStepConfig.JsonConfigOptions fileConnectorJsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(fileConnectorConfig)
                .build();

        PipelineStepConfig.ProcessorInfo fileConnectorProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("file-connector-service")
                .build();

        Map<String, PipelineStepConfig.OutputTarget> fileConnectorOutputs = new HashMap<>();
        fileConnectorOutputs.put("default", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("document-parser")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("search.files.incoming")
                        .kafkaProducerProperties(Map.of("compression.type", "lz4"))
                        .build())
                .build());

        PipelineStepConfig fileConnectorStep = PipelineStepConfig.builder()
                .stepName("file-connector")
                .stepType(StepType.INITIAL_PIPELINE)
                .description("Monitors a directory for new files to index")
                .customConfigSchemaId("file-connector-schema")
                .customConfig(fileConnectorJsonConfig)
                .processorInfo(fileConnectorProcessorInfo)
                .outputs(fileConnectorOutputs)
                .build();

        // Create the document parser step
        ObjectNode documentParserConfig = OBJECT_MAPPER.createObjectNode();
        documentParserConfig.put("extractMetadata", true);
        documentParserConfig.put("extractText", true);
        documentParserConfig.put("maxDocumentSizeMb", 50);

        PipelineStepConfig.JsonConfigOptions documentParserJsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(documentParserConfig)
                .build();

        PipelineStepConfig.ProcessorInfo documentParserProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("document-parser-service")
                .build();

        List<KafkaInputDefinition> documentParserInputs = List.of(
                KafkaInputDefinition.builder()
                        .listenTopics(List.of("search.files.incoming"))
                        .consumerGroupId("document-parser-group")
                        .kafkaConsumerProperties(Map.of(
                                "auto.offset.reset", "earliest",
                                "max.poll.records", "100"))
                        .build()
        );

        Map<String, PipelineStepConfig.OutputTarget> documentParserOutputs = new HashMap<>();
        documentParserOutputs.put("default", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("text-analyzer")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("search.documents.parsed")
                        .kafkaProducerProperties(Map.of("compression.type", "lz4"))
                        .build())
                .build());
        documentParserOutputs.put("onError", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("error-handler")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("search.errors")
                        .kafkaProducerProperties(Map.of("compression.type", "lz4"))
                        .build())
                .build());

        PipelineStepConfig documentParserStep = PipelineStepConfig.builder()
                .stepName("document-parser")
                .stepType(StepType.PIPELINE)
                .description("Parses documents into text and metadata")
                .customConfigSchemaId("document-parser-schema")
                .customConfig(documentParserJsonConfig)
                .kafkaInputs(documentParserInputs)
                .processorInfo(documentParserProcessorInfo)
                .outputs(documentParserOutputs)
                .build();

        // Create the text analyzer step
        ObjectNode textAnalyzerConfig = OBJECT_MAPPER.createObjectNode();
        textAnalyzerConfig.put("languageDetection", true);
        textAnalyzerConfig.put("entityExtraction", true);
        textAnalyzerConfig.put("keywordExtraction", true);
        textAnalyzerConfig.put("summarization", true);

        PipelineStepConfig.JsonConfigOptions textAnalyzerJsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(textAnalyzerConfig)
                .build();

        PipelineStepConfig.ProcessorInfo textAnalyzerProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("text-analyzer-service")
                .build();

        List<KafkaInputDefinition> textAnalyzerInputs = List.of(
                KafkaInputDefinition.builder()
                        .listenTopics(List.of("search.documents.parsed"))
                        .consumerGroupId("text-analyzer-group")
                        .kafkaConsumerProperties(Map.of("auto.offset.reset", "earliest"))
                        .build()
        );

        Map<String, PipelineStepConfig.OutputTarget> textAnalyzerOutputs = new HashMap<>();
        textAnalyzerOutputs.put("default", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("search-indexer")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("search.documents.analyzed")
                        .kafkaProducerProperties(Map.of("compression.type", "lz4"))
                        .build())
                .build());
        textAnalyzerOutputs.put("onError", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("error-handler")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("search.errors")
                        .kafkaProducerProperties(Map.of("compression.type", "lz4"))
                        .build())
                .build());

        PipelineStepConfig textAnalyzerStep = PipelineStepConfig.builder()
                .stepName("text-analyzer")
                .stepType(StepType.PIPELINE)
                .description("Analyzes text for language, entities, and keywords")
                .customConfigSchemaId("text-analyzer-schema")
                .customConfig(textAnalyzerJsonConfig)
                .kafkaInputs(textAnalyzerInputs)
                .processorInfo(textAnalyzerProcessorInfo)
                .outputs(textAnalyzerOutputs)
                .build();

        // Create the search indexer step
        ObjectNode searchIndexerConfig = OBJECT_MAPPER.createObjectNode();
        searchIndexerConfig.put("indexName", "documents");
        searchIndexerConfig.put("batchSize", 100);
        searchIndexerConfig.put("commitWithinMs", 1000);
        searchIndexerConfig.put("optimizeAfterCommits", 10);

        PipelineStepConfig.JsonConfigOptions searchIndexerJsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(searchIndexerConfig)
                .build();

        PipelineStepConfig.ProcessorInfo searchIndexerProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("search-indexer-service")
                .build();

        List<KafkaInputDefinition> searchIndexerInputs = List.of(
                KafkaInputDefinition.builder()
                        .listenTopics(List.of("search.documents.analyzed"))
                        .consumerGroupId("search-indexer-group")
                        .kafkaConsumerProperties(Map.of("auto.offset.reset", "earliest"))
                        .build()
        );

        Map<String, PipelineStepConfig.OutputTarget> searchIndexerOutputs = new HashMap<>();
        searchIndexerOutputs.put("onError", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("error-handler")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("search.errors")
                        .kafkaProducerProperties(Map.of("compression.type", "lz4"))
                        .build())
                .build());

        PipelineStepConfig searchIndexerStep = PipelineStepConfig.builder()
                .stepName("search-indexer")
                .stepType(StepType.SINK)
                .description("Indexes documents in the search engine")
                .customConfigSchemaId("search-indexer-schema")
                .customConfig(searchIndexerJsonConfig)
                .kafkaInputs(searchIndexerInputs)
                .processorInfo(searchIndexerProcessorInfo)
                .outputs(searchIndexerOutputs)
                .build();

        // Create the error handler step
        ObjectNode errorHandlerConfig = OBJECT_MAPPER.createObjectNode();
        errorHandlerConfig.put("errorLogPath", "/var/log/search-pipeline/errors");
        errorHandlerConfig.put("notifyAdmin", true);
        errorHandlerConfig.put("retryStrategy", "exponential-backoff");

        PipelineStepConfig.JsonConfigOptions errorHandlerJsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(errorHandlerConfig)
                .build();

        PipelineStepConfig.ProcessorInfo errorHandlerProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("error-handler-service")
                .build();

        List<KafkaInputDefinition> errorHandlerInputs = List.of(
                KafkaInputDefinition.builder()
                        .listenTopics(List.of("search.errors"))
                        .consumerGroupId("error-handler-group")
                        .kafkaConsumerProperties(Map.of("auto.offset.reset", "earliest"))
                        .build()
        );

        PipelineStepConfig errorHandlerStep = PipelineStepConfig.builder()
                .stepName("error-handler")
                .stepType(StepType.SINK)
                .description("Handles errors from the pipeline")
                .customConfigSchemaId("error-handler-schema")
                .customConfig(errorHandlerJsonConfig)
                .kafkaInputs(errorHandlerInputs)
                .processorInfo(errorHandlerProcessorInfo)
                .outputs(Collections.emptyMap())
                .build();

        // Create a pipeline with all steps
        Map<String, PipelineStepConfig> pipelineSteps = new HashMap<>();
        pipelineSteps.put(fileConnectorStep.stepName(), fileConnectorStep);
        pipelineSteps.put(documentParserStep.stepName(), documentParserStep);
        pipelineSteps.put(textAnalyzerStep.stepName(), textAnalyzerStep);
        pipelineSteps.put(searchIndexerStep.stepName(), searchIndexerStep);
        pipelineSteps.put(errorHandlerStep.stepName(), errorHandlerStep);

        PipelineConfig searchPipeline = PipelineConfig.builder()
                .name("search-indexing-pipeline")
                .pipelineSteps(pipelineSteps)
                .build();

        // Create a pipeline graph with the search pipeline
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(searchPipeline.name(), searchPipeline);

        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
                .pipelines(pipelines)
                .build();

        // Create module configurations
        Map<String, PipelineModuleConfiguration> availableModules = new HashMap<>();

        availableModules.put("file-connector-service", PipelineModuleConfiguration.builder()
                .implementationName("File Connector Service")
                .implementationId("file-connector-service")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("file-connector-schema")
                        .version(1)
                        .build())
                .build());

        availableModules.put("document-parser-service", PipelineModuleConfiguration.builder()
                .implementationName("Document Parser Service")
                .implementationId("document-parser-service")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("document-parser-schema")
                        .version(1)
                        .build())
                .build());

        availableModules.put("text-analyzer-service", PipelineModuleConfiguration.builder()
                .implementationName("Text Analyzer Service")
                .implementationId("text-analyzer-service")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("text-analyzer-schema")
                        .version(1)
                        .build())
                .build());

        availableModules.put("search-indexer-service", PipelineModuleConfiguration.builder()
                .implementationName("Search Indexer Service")
                .implementationId("search-indexer-service")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("search-indexer-schema")
                        .version(1)
                        .build())
                .build());

        availableModules.put("error-handler-service", PipelineModuleConfiguration.builder()
                .implementationName("Error Handler Service")
                .implementationId("error-handler-service")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("error-handler-schema")
                        .version(1)
                        .build())
                .build());

        PipelineModuleMap pipelineModuleMap = PipelineModuleMap.builder()
                .availableModules(availableModules)
                .build();

        // Create the cluster config
        return PipelineClusterConfig.builder()
                .clusterName("search-indexing-cluster")
                .pipelineGraphConfig(pipelineGraphConfig)
                .pipelineModuleMap(pipelineModuleMap)
                .defaultPipelineName("search-indexing-pipeline")
                .allowedKafkaTopics(Set.of(
                        "search.files.incoming",
                        "search.documents.parsed",
                        "search.documents.analyzed",
                        "search.errors"))
                .allowedGrpcServices(Set.of(
                        "file-connector-service",
                        "document-parser-service",
                        "text-analyzer-service",
                        "search-indexer-service",
                        "error-handler-service"))
                .build();
    }

    /**
     * Creates a PipelineClusterConfig with an empty pipeline (no steps).
     */
    public static PipelineClusterConfig createEmptyPipelineClusterConfig() {
        // Create an empty pipeline
        PipelineConfig emptyPipeline = PipelineConfig.builder()
                .name("empty-pipeline")
                .pipelineSteps(Collections.emptyMap())
                .build();

        // Create a pipeline graph with the empty pipeline
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(emptyPipeline.name(), emptyPipeline);

        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
                .pipelines(pipelines)
                .build();

        // Create a module map with a placeholder service
        SchemaReference schemaReference = SchemaReference.builder()
                .subject("placeholder-schema")
                .version(1)
                .build();

        PipelineModuleConfiguration placeholderModule = PipelineModuleConfiguration.builder()
                .implementationName("Placeholder Service")
                .implementationId("placeholder-service")
                .customConfigSchemaReference(schemaReference)
                .build();

        Map<String, PipelineModuleConfiguration> availableModules = new HashMap<>();
        availableModules.put(placeholderModule.implementationId(), placeholderModule);

        PipelineModuleMap pipelineModuleMap = PipelineModuleMap.builder()
                .availableModules(availableModules)
                .build();

        // Create the cluster config
        return PipelineClusterConfig.builder()
                .clusterName("empty-pipeline-cluster")
                .pipelineGraphConfig(pipelineGraphConfig)
                .pipelineModuleMap(pipelineModuleMap)
                .defaultPipelineName("empty-pipeline")
                .allowedGrpcServices(Set.of("placeholder-service"))
                .build();
    }

    /**
     * Creates a PipelineClusterConfig with orphan steps (steps with no connections).
     */
    public static PipelineClusterConfig createOrphanStepsPipelineClusterConfig() {
        // Create orphan steps
        ObjectNode orphanStep1Config = OBJECT_MAPPER.createObjectNode();
        orphanStep1Config.put("parameter1", "value1");
        orphanStep1Config.put("parameter2", 42);

        PipelineStepConfig.JsonConfigOptions orphanStep1JsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(orphanStep1Config)
                .build();

        PipelineStepConfig.ProcessorInfo orphanStep1ProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("orphan-service-1")
                .build();

        PipelineStepConfig orphanStep1 = PipelineStepConfig.builder()
                .stepName("orphan-step-1")
                .stepType(StepType.PIPELINE)
                .description("An orphaned step with no connections")
                .customConfigSchemaId("orphan-step-schema")
                .customConfig(orphanStep1JsonConfig)
                .processorInfo(orphanStep1ProcessorInfo)
                .outputs(Collections.emptyMap())
                .build();

        ObjectNode orphanStep2Config = OBJECT_MAPPER.createObjectNode();
        orphanStep2Config.put("parameter1", "value2");
        orphanStep2Config.put("parameter2", 84);

        PipelineStepConfig.JsonConfigOptions orphanStep2JsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(orphanStep2Config)
                .build();

        PipelineStepConfig.ProcessorInfo orphanStep2ProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("orphan-service-2")
                .build();

        PipelineStepConfig orphanStep2 = PipelineStepConfig.builder()
                .stepName("orphan-step-2")
                .stepType(StepType.PIPELINE)
                .description("Another orphaned step with no connections")
                .customConfigSchemaId("orphan-step-schema")
                .customConfig(orphanStep2JsonConfig)
                .processorInfo(orphanStep2ProcessorInfo)
                .outputs(Collections.emptyMap())
                .build();

        ObjectNode orphanStep3Config = OBJECT_MAPPER.createObjectNode();
        orphanStep3Config.put("sinkParameter", "sink-value");
        orphanStep3Config.put("enabled", true);

        PipelineStepConfig.JsonConfigOptions orphanStep3JsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(orphanStep3Config)
                .build();

        PipelineStepConfig.ProcessorInfo orphanStep3ProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("orphan-sink-service")
                .build();

        PipelineStepConfig orphanStep3 = PipelineStepConfig.builder()
                .stepName("orphan-step-3")
                .stepType(StepType.SINK)
                .description("An orphaned sink step with no connections")
                .customConfigSchemaId("orphan-sink-schema")
                .customConfig(orphanStep3JsonConfig)
                .processorInfo(orphanStep3ProcessorInfo)
                .outputs(Collections.emptyMap())
                .build();

        // Create a pipeline with orphan steps
        Map<String, PipelineStepConfig> pipelineSteps = new HashMap<>();
        pipelineSteps.put(orphanStep1.stepName(), orphanStep1);
        pipelineSteps.put(orphanStep2.stepName(), orphanStep2);
        pipelineSteps.put(orphanStep3.stepName(), orphanStep3);

        PipelineConfig orphanStepsPipeline = PipelineConfig.builder()
                .name("orphan-steps-pipeline")
                .pipelineSteps(pipelineSteps)
                .build();

        // Create a pipeline graph with the orphan steps pipeline
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(orphanStepsPipeline.name(), orphanStepsPipeline);

        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
                .pipelines(pipelines)
                .build();

        // Create module configurations
        Map<String, PipelineModuleConfiguration> availableModules = new HashMap<>();

        availableModules.put("orphan-service-1", PipelineModuleConfiguration.builder()
                .implementationName("Orphan Service 1")
                .implementationId("orphan-service-1")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("orphan-step-schema")
                        .version(1)
                        .build())
                .build());

        availableModules.put("orphan-service-2", PipelineModuleConfiguration.builder()
                .implementationName("Orphan Service 2")
                .implementationId("orphan-service-2")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("orphan-step-schema")
                        .version(1)
                        .build())
                .build());

        availableModules.put("orphan-sink-service", PipelineModuleConfiguration.builder()
                .implementationName("Orphan Sink Service")
                .implementationId("orphan-sink-service")
                .customConfigSchemaReference(SchemaReference.builder()
                        .subject("orphan-sink-schema")
                        .version(1)
                        .build())
                .build());

        PipelineModuleMap pipelineModuleMap = PipelineModuleMap.builder()
                .availableModules(availableModules)
                .build();

        // Create the cluster config
        return PipelineClusterConfig.builder()
                .clusterName("orphan-steps-cluster")
                .pipelineGraphConfig(pipelineGraphConfig)
                .pipelineModuleMap(pipelineModuleMap)
                .defaultPipelineName("orphan-steps-pipeline")
                .allowedGrpcServices(Set.of(
                        "orphan-service-1",
                        "orphan-service-2",
                        "orphan-sink-service"))
                .build();
    }

    /**
     * Creates a PipelineClusterConfig with just the initial service registered.
     */
    public static PipelineClusterConfig createInitialSeededPipelineClusterConfig() {
        // Create the initial service step
        ObjectNode initialServiceConfig = OBJECT_MAPPER.createObjectNode();
        initialServiceConfig.put("initialParameter", "initial-value");
        initialServiceConfig.put("autoStart", true);

        PipelineStepConfig.JsonConfigOptions initialServiceJsonConfig = PipelineStepConfig.JsonConfigOptions.builder()
                .jsonConfig(initialServiceConfig)
                .build();

        PipelineStepConfig.ProcessorInfo initialServiceProcessorInfo = PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("initial-service")
                .build();

        PipelineStepConfig initialServiceStep = PipelineStepConfig.builder()
                .stepName("initial-service")
                .stepType(StepType.INITIAL_PIPELINE)
                .description("Initial seeded service with no connections")
                .customConfigSchemaId("initial-service-schema")
                .customConfig(initialServiceJsonConfig)
                .processorInfo(initialServiceProcessorInfo)
                .outputs(Collections.emptyMap())
                .build();

        // Create a pipeline with the initial service step
        Map<String, PipelineStepConfig> pipelineSteps = new HashMap<>();
        pipelineSteps.put(initialServiceStep.stepName(), initialServiceStep);

        PipelineConfig initialSeededPipeline = PipelineConfig.builder()
                .name("initial-seeded-pipeline")
                .pipelineSteps(pipelineSteps)
                .build();

        // Create a pipeline graph with the initial seeded pipeline
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(initialSeededPipeline.name(), initialSeededPipeline);

        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
                .pipelines(pipelines)
                .build();

        // Create a module map with the initial service
        SchemaReference schemaReference = SchemaReference.builder()
                .subject("initial-service-schema")
                .version(1)
                .build();

        PipelineModuleConfiguration initialServiceModule = PipelineModuleConfiguration.builder()
                .implementationName("Initial Service")
                .implementationId("initial-service")
                .customConfigSchemaReference(schemaReference)
                .build();

        Map<String, PipelineModuleConfiguration> availableModules = new HashMap<>();
        availableModules.put(initialServiceModule.implementationId(), initialServiceModule);

        PipelineModuleMap pipelineModuleMap = PipelineModuleMap.builder()
                .availableModules(availableModules)
                .build();

        // Create the cluster config
        return PipelineClusterConfig.builder()
                .clusterName("initial-seeded-cluster")
                .pipelineGraphConfig(pipelineGraphConfig)
                .pipelineModuleMap(pipelineModuleMap)
                .defaultPipelineName("initial-seeded-pipeline")
                .allowedGrpcServices(Set.of("initial-service"))
                .build();
    }
}