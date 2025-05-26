package com.krickert.yappy.engine.controller.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.yappy.engine.dto.SchemaListInfoEntryDto;
import com.krickert.search.schema.registry.SchemaRegistryServiceGrpc;
import com.krickert.search.schema.registry.ListSchemasRequest;
import com.krickert.search.schema.registry.ListSchemasResponse;
import com.krickert.search.schema.registry.GetSchemaRequest;
import com.krickert.search.schema.registry.SchemaInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller("/api/schemas")
public class SchemaController {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaController.class);
    private final SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub schemaRegistryClient;
    private final ObjectMapper objectMapper;

    @Inject
    public SchemaController(SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub schemaRegistryClient, ObjectMapper objectMapper) {
        this.schemaRegistryClient = schemaRegistryClient;
        this.objectMapper = objectMapper;
    }

    @Get("/")
    public HttpResponse<List<SchemaListInfoEntryDto>> getAllSchemas() {
        LOG.info("Request to get all schemas");
        try {
            ListSchemasResponse listResponse = schemaRegistryClient.listSchemas(ListSchemasRequest.newBuilder().build());
            List<SchemaListInfoEntryDto> resultList = new ArrayList<>();

            for (SchemaInfo briefInfo : listResponse.getSchemasList()) {
                try {
                    SchemaInfo fullSchemaInfo = schemaRegistryClient.getSchema(GetSchemaRequest.newBuilder().setSchemaId(briefInfo.getSchemaId()).build());
                    Map<String, Object> schemaContentMap = parseSchemaContent(fullSchemaInfo.getSchemaContent());

                    String name = (String) schemaContentMap.getOrDefault("name", "N/A");
                    String description = (String) schemaContentMap.getOrDefault("description", "N/A");
                    String type = (String) schemaContentMap.getOrDefault("type", "N/A");

                    resultList.add(new SchemaListInfoEntryDto(fullSchemaInfo.getSchemaId(), name, description, type));
                } catch (StatusRuntimeException sre) {
                    LOG.warn("Error fetching full schema details for ID {}: {}", briefInfo.getSchemaId(), sre.getStatus());
                    // Optionally skip this schema or add a placeholder
                } catch (JsonProcessingException jpe) {
                    LOG.warn("Error parsing schema content for ID {}: {}", briefInfo.getSchemaId(), jpe.getMessage());
                    // Optionally skip this schema or add a placeholder with error info
                     resultList.add(new SchemaListInfoEntryDto(briefInfo.getSchemaId(), "Error Parsing", "Could not parse schema content", "Error"));
                }
            }
            LOG.info("Returning {} schemas.", resultList.size());
            return HttpResponse.ok(resultList);
        } catch (StatusRuntimeException e) {
            LOG.error("gRPC error while listing schemas: {}", e.getStatus(), e);
            return HttpResponse.serverError(); // Or a more specific error
        }
    }

    @Get("/{id}")
    public HttpResponse<Object> getSchemaById(@PathVariable String id) {
        LOG.info("Request to get schema by id: {}", id);
        try {
            GetSchemaRequest request = GetSchemaRequest.newBuilder().setSchemaId(id).build();
            SchemaInfo schemaInfo = schemaRegistryClient.getSchema(request);

            if (schemaInfo == null || schemaInfo.getSchemaId().isEmpty()) {
                // This case might not be hit if gRPC throws NOT_FOUND directly
                LOG.warn("Schema with id {} not found (empty response).", id);
                return HttpResponse.notFound();
            }

            Map<String, Object> schemaContentMap = parseSchemaContent(schemaInfo.getSchemaContent());
            LOG.info("Found schema with id: {}. Returning its content.", id);
            return HttpResponse.ok(schemaContentMap);

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                LOG.warn("Schema with id {} not found. gRPC status: {}", id, e.getStatus());
                return HttpResponse.notFound();
            }
            LOG.error("gRPC error while getting schema with id {}: {}", id, e.getStatus(), e);
            return HttpResponse.serverError(); // Or a more specific error
        } catch (JsonProcessingException e) {
            LOG.error("Error parsing schema content for id {}: {}", id, e.getMessage(), e);
            // Consider what to return. Maybe the raw string or an error object.
            return HttpResponse.serverError("Error parsing schema content");
        }
    }

    private Map<String, Object> parseSchemaContent(String schemaContent) throws JsonProcessingException {
        if (schemaContent == null || schemaContent.isEmpty()) {
            return Map.of();
        }
        return objectMapper.readValue(schemaContent, new TypeReference<Map<String, Object>>() {});
    }
}
