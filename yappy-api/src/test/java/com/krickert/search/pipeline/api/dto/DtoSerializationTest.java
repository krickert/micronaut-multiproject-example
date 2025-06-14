package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class DtoSerializationTest {

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testCreatePipelineRequestSerialization() throws Exception {
        // Given
        var request = new CreatePipelineRequest(
                "test-pipeline",
                "Test Pipeline",
                "A test pipeline for serialization",
                List.of(
                        new CreatePipelineRequest.StepDefinition(
                                "extract", "tika-parser", 
                                Map.of("maxSize", "10MB"), 
                                List.of("chunk")
                        ),
                        new CreatePipelineRequest.StepDefinition(
                                "chunk", "chunker",
                                Map.of("size", 512, "overlap", 50),
                                List.of("embed")
                        )
                ),
                List.of("test", "nlp"),
                Map.of("custom", "config")
        );

        // When
        String json = objectMapper.writeValueAsString(request);
        CreatePipelineRequest deserialized = objectMapper.readValue(json, CreatePipelineRequest.class);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("\"id\":\"test-pipeline\""));
        assertTrue(json.contains("\"name\":\"Test Pipeline\""));
        assertTrue(json.contains("\"steps\":"));
        
        assertEquals(request.id(), deserialized.id());
        assertEquals(request.name(), deserialized.name());
        assertEquals(request.description(), deserialized.description());
        assertEquals(request.steps().size(), deserialized.steps().size());
        assertEquals(request.tags(), deserialized.tags());
    }

    @Test
    void testPipelineViewSerialization() throws Exception {
        // Given
        var now = Instant.now();
        var pipelineView = new PipelineView(
                "pipeline-1",
                "Pipeline One",
                "First test pipeline",
                List.of(
                        new PipelineView.PipelineStepView(
                                "step1", "module1", 
                                Map.of("key", "value"),
                                List.of("step2"),
                                "direct",
                                null
                        )
                ),
                List.of("production"),
                true,
                now,
                now,
                "default"
        );

        // When
        String json = objectMapper.writeValueAsString(pipelineView);
        PipelineView deserialized = objectMapper.readValue(json, PipelineView.class);

        // Then
        assertEquals(pipelineView.id(), deserialized.id());
        assertEquals(pipelineView.name(), deserialized.name());
        assertEquals(pipelineView.active(), deserialized.active());
        assertEquals(pipelineView.steps().size(), deserialized.steps().size());
        assertEquals(pipelineView.cluster(), deserialized.cluster());
    }

    @Test
    void testValidationResponseSerialization() throws Exception {
        // Given
        var response = new ValidationResponse(
                false,
                List.of(
                        new ValidationResponse.ValidationError(
                                "steps[0].module",
                                "Module 'unknown' not found",
                                "MODULE_NOT_FOUND"
                        )
                ),
                List.of(
                        new ValidationResponse.ValidationWarning(
                                "config.timeout",
                                "Timeout value is very high",
                                "HIGH_TIMEOUT"
                        )
                )
        );

        // When
        String json = objectMapper.writeValueAsString(response);
        ValidationResponse deserialized = objectMapper.readValue(json, ValidationResponse.class);

        // Then
        assertFalse(deserialized.valid());
        assertEquals(1, deserialized.errors().size());
        assertEquals(1, deserialized.warnings().size());
        assertEquals("MODULE_NOT_FOUND", deserialized.errors().get(0).code());
    }

    @Test
    void testTestPipelineResponseSerialization() throws Exception {
        // Given
        var response = new TestPipelineResponse(
                true,
                Duration.ofSeconds(5),
                List.of(
                        new TestPipelineResponse.StepResult(
                                "extract",
                                "tika-parser",
                                Duration.ofMillis(1500),
                                true,
                                Map.of("text", "extracted content"),
                                null
                        ),
                        new TestPipelineResponse.StepResult(
                                "chunk",
                                "chunker",
                                Duration.ofMillis(500),
                                true,
                                Map.of("chunks", List.of("chunk1", "chunk2")),
                                null
                        )
                ),
                List.of(),
                Map.of("totalChunks", 2)
        );

        // When
        String json = objectMapper.writeValueAsString(response);
        TestPipelineResponse deserialized = objectMapper.readValue(json, TestPipelineResponse.class);

        // Then
        assertTrue(deserialized.success());
        assertEquals(2, deserialized.stepResults().size());
        assertEquals("extract", deserialized.stepResults().get(0).stepId());
        assertNotNull(deserialized.processingTime());
    }

    @Test
    void testModuleInfoSerialization() throws Exception {
        // Given
        var now = Instant.now();
        var moduleInfo = new ModuleInfo(
                "tika-parser",
                "Apache Tika Parser",
                "Extracts text from documents",
                "1.0.0",
                3,
                "healthy",
                List.of("text-extraction", "metadata-extraction"),
                List.of("application/pdf", "text/plain"),
                List.of("text/plain"),
                Map.of("maxFileSize", Map.of("type", "string", "default", "10MB")),
                now,
                now
        );

        // When
        String json = objectMapper.writeValueAsString(moduleInfo);
        ModuleInfo deserialized = objectMapper.readValue(json, ModuleInfo.class);

        // Then
        assertEquals(moduleInfo.serviceId(), deserialized.serviceId());
        assertEquals(moduleInfo.version(), deserialized.version());
        assertEquals(moduleInfo.instances(), deserialized.instances());
        assertEquals(moduleInfo.capabilities(), deserialized.capabilities());
        assertEquals(moduleInfo.inputTypes(), deserialized.inputTypes());
    }

    @Test
    void testPipelineTemplateSerialization() throws Exception {
        // Given
        var template = new PipelineTemplate(
                "nlp-basic",
                "Basic NLP Pipeline",
                "Standard NLP processing pipeline",
                List.of("Document processing", "Search indexing"),
                "text-processing",
                List.of(
                        new CreatePipelineRequest.StepDefinition(
                                "extract", "tika-parser", Map.of(), List.of("chunk")
                        ),
                        new CreatePipelineRequest.StepDefinition(
                                "chunk", "chunker", Map.of("size", 512), List.of("embed")
                        ),
                        new CreatePipelineRequest.StepDefinition(
                                "embed", "embedder", Map.of("model", "all-MiniLM-L6-v2"), List.of()
                        )
                ),
                List.of(
                        new PipelineTemplate.TemplateVariable(
                                "chunkSize",
                                "Size of text chunks",
                                "number",
                                512,
                                true
                        )
                ),
                Map.of("defaultTimeout", 30)
        );

        // When
        String json = objectMapper.writeValueAsString(template);
        PipelineTemplate deserialized = objectMapper.readValue(json, PipelineTemplate.class);

        // Then
        assertEquals(template.id(), deserialized.id());
        assertEquals(template.steps().size(), deserialized.steps().size());
        assertEquals(template.variables().size(), deserialized.variables().size());
        assertEquals("chunkSize", deserialized.variables().get(0).name());
    }

    @Test
    void testEnvironmentStatusSerialization() throws Exception {
        // Given
        var status = new EnvironmentStatus(
                "ready",
                Instant.now(),
                List.of(
                        new EnvironmentStatus.ServiceStatus(
                                "consul",
                                "infrastructure",
                                "running",
                                "1.16.0",
                                "http://localhost:8500",
                                true
                        ),
                        new EnvironmentStatus.ServiceStatus(
                                "kafka",
                                "messaging",
                                "running",
                                "3.5.0",
                                "localhost:9092",
                                true
                        )
                ),
                List.of(),
                true
        );

        // When
        String json = objectMapper.writeValueAsString(status);
        EnvironmentStatus deserialized = objectMapper.readValue(json, EnvironmentStatus.class);

        // Then
        assertEquals("ready", deserialized.status());
        assertEquals(2, deserialized.services().size());
        assertTrue(deserialized.readyForTesting());
    }

    @ParameterizedTest
    @MethodSource("provideNullableFieldDtos")
    void testNullableFieldHandling(Object dto) throws Exception {
        // When
        String json = objectMapper.writeValueAsString(dto);
        Object deserialized = objectMapper.readValue(json, dto.getClass());

        // Then
        assertNotNull(json);
        assertNotNull(deserialized);
        // Ensure null fields are handled correctly
        assertFalse(json.contains("null"));
    }

    static Stream<Object> provideNullableFieldDtos() {
        return Stream.of(
                new UpdatePipelineRequest(
                        null, // name
                        "Updated description",
                        null, // steps
                        List.of("updated"),
                        true,
                        null  // config
                ),
                new TestPipelineRequest(
                        "Test content",
                        null, // contentType
                        null, // metadata
                        null  // timeoutSeconds
                ),
                new ModuleUpdateRequest(
                        "Updated Module",
                        null, // description
                        null, // version
                        null, // host
                        null, // port
                        null, // capabilities
                        null, // inputTypes
                        null, // outputTypes
                        null, // configSchema
                        null  // metadata
                )
        );
    }

    @Test
    void testEmptyCollectionSerialization() throws Exception {
        // Given
        var request = new CreatePipelineRequest(
                "empty-pipeline",
                "Empty Pipeline",
                "Pipeline with empty collections",
                List.of(), // Empty steps - should fail validation but serialize OK
                List.of(), // Empty tags
                Map.of()   // Empty config
        );

        // When
        String json = objectMapper.writeValueAsString(request);
        CreatePipelineRequest deserialized = objectMapper.readValue(json, CreatePipelineRequest.class);

        // Then
        assertTrue(json.contains("\"steps\":[]"));
        assertTrue(json.contains("\"tags\":[]"));
        assertTrue(json.contains("\"config\":{}"));
        assertEquals(0, deserialized.steps().size());
        assertEquals(0, deserialized.tags().size());
        assertEquals(0, deserialized.config().size());
    }
}