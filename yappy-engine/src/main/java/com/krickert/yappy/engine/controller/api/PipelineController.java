package com.krickert.yappy.engine.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.DefaultConfigurationValidator;
import com.krickert.search.config.consul.ValidationResult;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.schema.registry.GetSchemaRequest;
import com.krickert.search.schema.registry.SchemaInfo;
import com.krickert.search.schema.registry.SchemaRegistryServiceGrpc;
import com.krickert.yappy.engine.dto.PipelineDto;
import com.krickert.yappy.engine.dto.validation.PipelineValidationRequestDto;
import com.krickert.yappy.engine.dto.validation.PipelineValidationResponseDto;
import com.krickert.yappy.engine.dto.validation.ValidationErrorDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject; // Ensure this is used for constructor injection
import io.micronaut.http.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller("/api/pipelines")
public class PipelineController {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineController.class);
    private final Map<String, PipelineDto> pipelineStore = new ConcurrentHashMap<>();
    private final DefaultConfigurationValidator advancedValidator;
    private final SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub schemaRegistryClient;
    private final ObjectMapper objectMapper; // For data conversion

    @Inject // Added Inject annotation
    public PipelineController(
            DefaultConfigurationValidator advancedValidator,
            SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub schemaRegistryClient,
            ObjectMapper objectMapper
    ) {
        this.advancedValidator = advancedValidator;
        this.schemaRegistryClient = schemaRegistryClient;
        this.objectMapper = objectMapper;
        // Initialize with new dummy data
        String pipeline1Id = "pipeline-uuid-1"; // Using fixed IDs for predictability
        Map<String, Object> pipeline1Structure = new HashMap<>();
        List<Map<String, Object>> p1Nodes = new ArrayList<>();

        // Node 1.1 (KafkaSource)
        Map<String, Object> p1n1DataConfig = new HashMap<>();
        p1n1DataConfig.put("topic", "com.example.raw_events");
        p1n1DataConfig.put("brokers", "kafka:9092");
        p1n1DataConfig.put("groupId", "raw-event-processor");
        Map<String, Object> p1n1Data = new HashMap<>();
        p1n1Data.put("label", "Raw Event Kafka Source");
        p1n1Data.put("config", p1n1DataConfig);
        p1n1Data.put("status", "running");
        Map<String, Object> p1n1Position = new HashMap<>();
        p1n1Position.put("x", 50);
        p1n1Position.put("y", 50);
        Map<String, Object> p1n1 = new HashMap<>();
        p1n1.put("id", "p1_node_kafka_in");
        p1n1.put("type", "kafkaSource");
        p1n1.put("data", p1n1Data);
        p1n1.put("position", p1n1Position);
        p1Nodes.add(p1n1);

        // Node 1.2 (TikaParser)
        Map<String, Object> p1n2DataConfig = new HashMap<>();
        p1n2DataConfig.put("outputField", "content");
        p1n2DataConfig.put("metadataPrefix", "meta_");
        Map<String, Object> p1n2Data = new HashMap<>();
        p1n2Data.put("label", "Parse Document (Tika)");
        p1n2Data.put("config", p1n2DataConfig);
        p1n2Data.put("status", "running");
        Map<String, Object> p1n2Position = new HashMap<>();
        p1n2Position.put("x", 250);
        p1n2Position.put("y", 150);
        Map<String, Object> p1n2 = new HashMap<>();
        p1n2.put("id", "p1_node_tika");
        p1n2.put("type", "tikaParser");
        p1n2.put("data", p1n2Data);
        p1n2.put("position", p1n2Position);
        p1Nodes.add(p1n2);

        // Node 1.3 (OpenSearchSink)
        Map<String, Object> p1n3DataConfig = new HashMap<>();
        p1n3DataConfig.put("indexName", "parsed_events");
        p1n3DataConfig.put("connectionUrl", "http://opensearch:9200");
        Map<String, Object> p1n3Data = new HashMap<>();
        p1n3Data.put("label", "Store in OpenSearch");
        p1n3Data.put("config", p1n3DataConfig);
        p1n3Data.put("status", "stopped");
        Map<String, Object> p1n3Position = new HashMap<>();
        p1n3Position.put("x", 450);
        p1n3Position.put("y", 50);
        Map<String, Object> p1n3 = new HashMap<>();
        p1n3.put("id", "p1_node_opensearch_out");
        p1n3.put("type", "openSearchSink");
        p1n3.put("data", p1n3Data);
        p1n3.put("position", p1n3Position);
        p1Nodes.add(p1n3);

        // Node 1.4 (KafkaTopic) - for DQL
        Map<String, Object> p1n4DataConfig = new HashMap<>();
        p1n4DataConfig.put("name", "com.example.parser_errors_dql");
        p1n4DataConfig.put("partitions", 3);
        p1n4DataConfig.put("replicationFactor", 1);
        Map<String, Object> p1n4Data = new HashMap<>();
        p1n4Data.put("label", "Parser Error DQL");
        p1n4Data.put("config", p1n4DataConfig);
        Map<String, Object> p1n4Position = new HashMap<>();
        p1n4Position.put("x", 250);
        p1n4Position.put("y", 300);
        Map<String, Object> p1n4 = new HashMap<>();
        p1n4.put("id", "p1_node_kafka_dql");
        p1n4.put("type", "kafkaTopic");
        p1n4.put("data", p1n4Data);
        p1n4.put("position", p1n4Position);
        p1Nodes.add(p1n4);

        pipeline1Structure.put("nodes", p1Nodes);

        List<Map<String, Object>> p1Edges = new ArrayList<>();
        p1Edges.add(Map.of("id", "p1_edge1", "source", "p1_node_kafka_in", "target", "p1_node_tika"));
        p1Edges.add(Map.of("id", "p1_edge2", "source", "p1_node_tika", "target", "p1_node_opensearch_out"));
        p1Edges.add(Map.of("id", "p1_edge3", "source", "p1_node_tika", "target", "p1_node_kafka_dql", "type", "conditionalEdge", "label", "on parsing error")); // Example of a labeled/typed edge
        pipeline1Structure.put("edges", p1Edges);

        PipelineDto pipeline1 = new PipelineDto(pipeline1Id, "Event Processing Pipeline", "Processes raw events, parses them, and stores them. Errors go to DQL.", pipeline1Structure);
        pipelineStore.put(pipeline1Id, pipeline1);


        // Pipeline 2: Customer Data Synchronization
        String pipeline2Id = "pipeline-uuid-2";
        Map<String, Object> pipeline2Structure = new HashMap<>();
        List<Map<String, Object>> p2Nodes = new ArrayList<>();

        // Node 2.1 (grpcConnector) - Source
        Map<String, Object> p2n1DataConfig = new HashMap<>();
        p2n1DataConfig.put("serviceUrl", "customer-service.internal:50051");
        p2n1DataConfig.put("methodName", "StreamAllCustomers");
        Map<String, Object> p2n1Data = new HashMap<>();
        p2n1Data.put("label", "Fetch Customers (gRPC)");
        p2n1Data.put("config", p2n1DataConfig);
        p2n1Data.put("status", "running");
        Map<String, Object> p2n1Position = new HashMap<>();
        p2n1Position.put("x", 50);
        p2n1Position.put("y", 100);
        Map<String, Object> p2n1 = new HashMap<>();
        p2n1.put("id", "p2_node_grpc_in");
        p2n1.put("type", "grpcConnector");
        p2n1.put("data", p2n1Data);
        p2n1.put("position", p2n1Position);
        p2Nodes.add(p2n1);

        // Node 2.2 (customProcessor) - Transformation
        Map<String, Object> p2n2DataConfig = new HashMap<>();
        p2n2DataConfig.put("scriptPath", "/scripts/transform_customer.js");
        p2n2DataConfig.put("timeoutMs", 500);
        Map<String, Object> p2n2Data = new HashMap<>();
        p2n2Data.put("label", "Transform Customer Data");
        p2n2Data.put("config", p2n2DataConfig);
        p2n2Data.put("status", "running");
        Map<String, Object> p2n2Position = new HashMap<>();
        p2n2Position.put("x", 300);
        p2n2Position.put("y", 100);
        Map<String, Object> p2n2 = new HashMap<>();
        p2n2.put("id", "p2_node_transformer");
        p2n2.put("type", "customProcessor");
        p2n2.put("data", p2n2Data);
        p2n2.put("position", p2n2Position);
        p2Nodes.add(p2n2);

        // Node 2.3 (kafkaSink) - Target
        Map<String, Object> p2n3DataConfig = new HashMap<>();
        p2n3DataConfig.put("topic", "com.example.synced_customers");
        p2n3DataConfig.put("brokers", "kafka:9092");
        p2n3DataConfig.put("ackMode", "all");
        Map<String, Object> p2n3Data = new HashMap<>();
        p2n3Data.put("label", "Publish to Kafka Topic");
        p2n3Data.put("config", p2n3DataConfig);
        p2n3Data.put("status", "error"); // Example of an error status
        Map<String, Object> p2n3Position = new HashMap<>();
        p2n3Position.put("x", 550);
        p2n3Position.put("y", 100);
        Map<String, Object> p2n3 = new HashMap<>();
        p2n3.put("id", "p2_node_kafka_out");
        p2n3.put("type", "kafkaSink"); // Using generic "kafkaSink" type
        p2n3.put("data", p2n3Data);
        p2n3.put("position", p2n3Position);
        p2Nodes.add(p2n3);

        pipeline2Structure.put("nodes", p2Nodes);

        List<Map<String, Object>> p2Edges = new ArrayList<>();
        p2Edges.add(Map.of("id", "p2_edge1", "source", "p2_node_grpc_in", "target", "p2_node_transformer"));
        p2Edges.add(Map.of("id", "p2_edge2", "source", "p2_node_transformer", "target", "p2_node_kafka_out"));
        pipeline2Structure.put("edges", p2Edges);

        PipelineDto pipeline2 = new PipelineDto(pipeline2Id, "Customer Data Sync", "Synchronizes customer data from gRPC to Kafka.", pipeline2Structure);
        pipelineStore.put(pipeline2Id, pipeline2);
        
        // Pipeline 3: Simple File Ingest to Log
        String pipeline3Id = "pipeline-uuid-3";
        Map<String, Object> pipeline3Structure = new HashMap<>();
        List<Map<String, Object>> p3Nodes = new ArrayList<>();

        Map<String, Object> p3n1DataConfig = new HashMap<>();
        p3n1DataConfig.put("directoryPath", "/mnt/shared/input_files");
        p3n1DataConfig.put("filePattern", "*.csv");
        Map<String, Object> p3n1Data = new HashMap<>();
        p3n1Data.put("label", "Watch CSV Files");
        p3n1Data.put("config", p3n1DataConfig);
        p3n1Data.put("status", "running");
        Map<String, Object> p3n1 = Map.of(
            "id", "p3_file_source", 
            "type", "fileSource", 
            "data", p3n1Data, 
            "position", Map.of("x", 100, "y", 200)
        );
        p3Nodes.add(p3n1);

        Map<String, Object> p3n2DataConfig = new HashMap<>();
        // No specific config for a simple logger, or could be log level, etc.
        p3n2DataConfig.put("logLevel", "INFO");
        Map<String, Object> p3n2Data = new HashMap<>();
        p3n2Data.put("label", "Log File Info");
        p3n2Data.put("config", p3n2DataConfig);
        Map<String, Object> p3n2 = Map.of(
            "id", "p3_logger_sink", 
            "type", "logSink", 
            "data", p3n2Data, 
            "position", Map.of("x", 350, "y", 200)
        );
        p3Nodes.add(p3n2);
        
        pipeline3Structure.put("nodes", p3Nodes);
        pipeline3Structure.put("edges", List.of(
            Map.of("id", "p3_edge1", "source", "p3_file_source", "target", "p3_logger_sink")
        ));
        
        PipelineDto pipeline3 = new PipelineDto(pipeline3Id, "File Ingest and Log", "Watches for files and logs their info.", pipeline3Structure);
        pipelineStore.put(pipeline3Id, pipeline3);


        LOG.info("Initialized PipelineController with {} dummy pipelines.", pipelineStore.size());
    }

    @Get("/")
    public List<PipelineDto> getAllPipelines() {
        LOG.info("Request to get all pipelines. Returning {} pipelines.", pipelineStore.size());
        return new ArrayList<>(pipelineStore.values());
    }

    @Get("/{id}")
    public HttpResponse<PipelineDto> getPipelineById(@PathVariable String id) {
        LOG.info("Request to get pipeline by id: {}", id);
        PipelineDto pipeline = pipelineStore.get(id);
        if (pipeline != null) {
            LOG.info("Found pipeline: {}", pipeline);
            return HttpResponse.ok(pipeline);
        } else {
            LOG.warn("Pipeline with id {} not found.", id);
            return HttpResponse.notFound();
        }
    }

    @Post("/")
    public HttpResponse<PipelineDto> createPipeline(@Body PipelineDto pipelineDto) {
        LOG.info("Request to create a new pipeline: {}", pipelineDto);
        if (pipelineDto == null) {
            LOG.warn("Received null pipelineDto in create request.");
            return HttpResponse.badRequest();
        }
        String newId = UUID.randomUUID().toString();
        pipelineDto.setId(newId);
        pipelineStore.put(newId, pipelineDto);
        LOG.info("Created new pipeline with id: {}. Pipeline: {}", newId, pipelineDto);
        return HttpResponse.created(pipelineDto);
    }

    @Put("/{id}")
    public HttpResponse<PipelineDto> updatePipeline(@PathVariable String id, @Body PipelineDto pipelineDto) {
        LOG.info("Request to update pipeline with id: {}. Update data: {}", id, pipelineDto);
        if (pipelineDto == null) {
            LOG.warn("Received null pipelineDto in update request for id: {}.", id);
            return HttpResponse.badRequest();
        }
        if (!pipelineStore.containsKey(id)) {
            LOG.warn("Pipeline with id {} not found for update.", id);
            return HttpResponse.notFound();
        }
        pipelineDto.setId(id); // Ensure ID remains consistent
        pipelineStore.put(id, pipelineDto);
        LOG.info("Updated pipeline with id: {}. New data: {}", id, pipelineDto);
        return HttpResponse.ok(pipelineDto);
    }

    @Delete("/{id}")
    public HttpResponse<?> deletePipeline(@PathVariable String id) {
        LOG.info("Request to delete pipeline with id: {}", id);
        if (pipelineStore.containsKey(id)) {
            pipelineStore.remove(id);
            LOG.info("Deleted pipeline with id: {}", id);
            return HttpResponse.status(HttpStatus.NO_CONTENT);
        } else {
            LOG.warn("Pipeline with id {} not found for deletion.", id);
            return HttpResponse.notFound();
        }
    }

    @Post("/validate")
    public PipelineValidationResponseDto validatePipeline(@Body PipelineValidationRequestDto requestDto) {
        LOG.info("Request to validate pipeline structure. Name: {}", requestDto.getName());
        PipelineValidationResponseDto response = new PipelineValidationResponseDto();
        response.setValid(true); // Assume valid until an error is found

        // --- Basic Structural Checks (Keep these as a first pass) ---
        if (requestDto.getStructure() == null) {
            response.setValid(false);
            response.addError(new ValidationErrorDto(null, "global", null, "Pipeline structure is missing."));
            return response; // Early exit if no structure
        }

        List<Map<String, Object>> nodesFromRequest = requestDto.getStructure().getNodes();
        List<Map<String, Object>> edgesFromRequest = requestDto.getStructure().getEdges();

        if ((nodesFromRequest == null || nodesFromRequest.isEmpty()) && (edgesFromRequest != null && !edgesFromRequest.isEmpty())) {
            response.setValid(false);
            response.addError(new ValidationErrorDto(null, "global", null, "Pipeline has edges but no nodes."));
        }

        Set<String> nodeIds = new HashSet<>();
        if (nodesFromRequest != null) {
            for (Map<String, Object> nodeMap : nodesFromRequest) {
                String nodeId = (String) nodeMap.get("id");
                String nodeType = (String) nodeMap.get("type");
                if (nodeId == null || nodeId.trim().isEmpty()) {
                    response.setValid(false);
                    response.addError(new ValidationErrorDto(null, "node", "id", "Node ID is missing or empty."));
                } else {
                    nodeIds.add(nodeId);
                }
                if (nodeType == null || nodeType.trim().isEmpty()) {
                    response.setValid(false);
                    response.addError(new ValidationErrorDto(nodeId, "node", "type", "Node type is missing or empty."));
                }
            }
        }

        if (edgesFromRequest != null) {
            for (Map<String, Object> edgeMap : edgesFromRequest) {
                String edgeId = (String) edgeMap.get("id");
                String sourceId = (String) edgeMap.get("source");
                String targetId = (String) edgeMap.get("target");
                if (sourceId == null || sourceId.trim().isEmpty() || !nodeIds.contains(sourceId)) {
                    response.setValid(false);
                    response.addError(new ValidationErrorDto(edgeId, "edge", "source", "Edge source ID '" + sourceId + "' is invalid or missing."));
                }
                if (targetId == null || targetId.trim().isEmpty() || !nodeIds.contains(targetId)) {
                    response.setValid(false);
                    response.addError(new ValidationErrorDto(edgeId, "edge", "target", "Edge target ID '" + targetId + "' is invalid or missing."));
                }
            }
        }
        // Note: Basic orphaned node check was here, DefaultConfigurationValidator handles graph validation more robustly.

        // --- If basic checks pass, proceed to Advanced Validation ---
        if (response.isValid()) {
            try {
                PipelineClusterConfig adaptedConfig = adaptToPipelineClusterConfig(requestDto);
                Function<SchemaReference, Optional<String>> schemaProvider = ref -> {
                    try {
                        GetSchemaRequest schemaRequest = GetSchemaRequest.newBuilder().setSchemaId(ref.getSchemaId()).build();
                        SchemaInfo schemaInfo = schemaRegistryClient.getSchema(schemaRequest);
                        return Optional.ofNullable(schemaInfo.getSchemaContent());
                    } catch (Exception e) {
                        LOG.warn("Schema content not found for {}:{}", ref.getSchemaType(), ref.getSchemaId(), e);
                        return Optional.empty();
                    }
                };

                ValidationResult advancedResult = advancedValidator.validate(adaptedConfig, schemaProvider);

                if (!advancedResult.isValid()) {
                    response.setValid(false);
                    for (String errorMsg : advancedResult.getMessages()) {
                        // Attempt to parse out element ID/type if possible, otherwise general error
                        // This is a simplification; DefaultConfigurationValidator might need more structured errors.
                        response.addError(new ValidationErrorDto(null, "advanced", null, errorMsg));
                    }
                } else {
                    // If basic was valid and advanced is also valid
                    response.getMessages().addAll(advancedResult.getMessages()); // Add any info messages from advanced validation
                }

            } catch (Exception e) {
                LOG.error("Error during advanced validation: {}", e.getMessage(), e);
                response.setValid(false);
                response.addError(new ValidationErrorDto(null, "global", "advanced-validation", "Error during advanced validation: " + e.getMessage()));
            }
        }


        if (response.isValid() && response.getMessages().isEmpty()) {
             // Add a generic success message if no other messages (e.g. from advanced validation) were added
            response.addMessage("Pipeline structure is valid.");
        }
        return response;
    }

    private PipelineClusterConfig adaptToPipelineClusterConfig(PipelineValidationRequestDto requestDto) {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig();
        PipelineConfig pipelineConfig = new PipelineConfig();
        pipelineConfig.setName(requestDto.getName() != null ? requestDto.getName() : "temp-validation-pipeline");
        pipelineConfig.setDescription("Temporary pipeline for validation purposes");

        PipelineStepConfig stepConfig = new PipelineStepConfig();
        stepConfig.setName("default-step"); // Single step to hold all nodes/edges

        List<PipelineNodeConfig> nodeConfigs = new ArrayList<>();
        if (requestDto.getStructure().getNodes() != null) {
            for (Map<String, Object> nodeMap : requestDto.getStructure().getNodes()) {
                PipelineNodeConfig node = objectMapper.convertValue(nodeMap, PipelineNodeConfig.class);
                nodeConfigs.add(node);
            }
        }
        stepConfig.setNodes(nodeConfigs);

        List<PipelineEdgeConfig> edgeConfigs = new ArrayList<>();
        if (requestDto.getStructure().getEdges() != null) {
            for (Map<String, Object> edgeMap : requestDto.getStructure().getEdges()) {
                PipelineEdgeConfig edge = objectMapper.convertValue(edgeMap, PipelineEdgeConfig.class);
                edgeConfigs.add(edge);
            }
        }
        stepConfig.setEdges(edgeConfigs);
        
        // The DefaultConfigurationValidator expects a structure with inputs/outputs for steps.
        // For a flat list of nodes/edges, we'll assume this single step is a "processor" type for now.
        // The validator might have issues if it strictly requires specific source/sink nodes not connected to step inputs/outputs.
        // This adaptation might need refinement based on how DefaultConfigurationValidator interprets a single step.
        stepConfig.setInputs(new ArrayList<>()); // Assuming no explicit step inputs for this flat validation
        stepConfig.setOutputs(new ArrayList<>()); // Assuming no explicit step outputs for this flat validation

        pipelineConfig.setSteps(Collections.singletonList(stepConfig));
        clusterConfig.setPipelines(Collections.singletonList(pipelineConfig));
        clusterConfig.setSchemaRegistryType(SchemaReference.SchemaType.GRPC_SERVICE); // Or a sensible default
        return clusterConfig;
    }
}
