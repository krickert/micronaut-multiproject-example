package com.krickert.search.pipeline.api;

import io.micronaut.context.annotation.Property;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Property(name = "micronaut.http.client.follow-redirects", value = StringUtils.FALSE)
@MicronautTest
class OpenApiTest {

    @Test
    void testRedirectionToOpenApiExplorer(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        HttpResponse<?> response = assertDoesNotThrow(() -> client.exchange("/"));
        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertNotNull(response.getHeaders().get(HttpHeaders.LOCATION));
        assertEquals("/swagger-ui/index.html", response.getHeaders().get(HttpHeaders.LOCATION));
    }

    @Test
    void testOpenApiSpecificationIsAccessible(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        String yml = assertDoesNotThrow(() -> client.retrieve("/swagger/yappy-api-0.1.yml"));
        
        // Verify basic OpenAPI structure
        assertTrue(yml.contains("openapi:"));
        assertTrue(yml.contains("YAPPY Pipeline API"));
        assertTrue(yml.contains("paths:"));
        
        // Verify our endpoints are documented
        assertTrue(yml.contains("/api/v1/pipelines"));
        assertTrue(yml.contains("/api/v1/modules"));
        assertTrue(yml.contains("/api/v1/test-utils"));
        
        // Verify tags
        assertTrue(yml.contains("Pipelines"));
        assertTrue(yml.contains("Modules"));
        assertTrue(yml.contains("Test Utilities"));
    }

    @Test
    void testPipelineEndpointsInOpenApi(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        String yml = assertDoesNotThrow(() -> client.retrieve("/swagger/yappy-api-0.1.yml"));
        
        // Verify pipeline operations
        assertTrue(yml.contains("operationId: listPipelines"));
        assertTrue(yml.contains("operationId: getPipeline"));
        assertTrue(yml.contains("operationId: createPipeline"));
        assertTrue(yml.contains("operationId: updatePipeline"));
        assertTrue(yml.contains("operationId: deletePipeline"));
        assertTrue(yml.contains("operationId: testPipeline"));
        assertTrue(yml.contains("operationId: validatePipeline"));
        assertTrue(yml.contains("operationId: getTemplates"));
    }

    @Test
    void testModuleEndpointsInOpenApi(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        String yml = assertDoesNotThrow(() -> client.retrieve("/swagger/yappy-api-0.1.yml"));
        
        // Verify module operations
        assertTrue(yml.contains("operationId: listModules"));
        assertTrue(yml.contains("operationId: getModule"));
        assertTrue(yml.contains("operationId: registerModule"));
        assertTrue(yml.contains("operationId: updateModule"));
        assertTrue(yml.contains("operationId: deregisterModule"));
        assertTrue(yml.contains("operationId: checkModuleHealth"));
        assertTrue(yml.contains("operationId: testModule"));
    }

    @Test
    void testResponseCodesDocumented(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        String yml = assertDoesNotThrow(() -> client.retrieve("/swagger/yappy-api-0.1.yml"));
        
        // Verify HTTP response codes are documented
        assertTrue(yml.contains("'200':"));
        assertTrue(yml.contains("'201':"));
        assertTrue(yml.contains("'204':"));
        assertTrue(yml.contains("'400':"));
        assertTrue(yml.contains("'409':"));
    }

    @Test
    void testSchemasDocumented(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        String yml = assertDoesNotThrow(() -> client.retrieve("/swagger/yappy-api-0.1.yml"));
        
        // Verify schemas are present
        assertTrue(yml.contains("CreatePipelineRequest"));
        assertTrue(yml.contains("PipelineView"));
        assertTrue(yml.contains("PipelineSummary"));
        assertTrue(yml.contains("ModuleInfo"));
        assertTrue(yml.contains("ValidationResponse"));
        assertTrue(yml.contains("TestPipelineRequest"));
        assertTrue(yml.contains("TestPipelineResponse"));
    }

    @Test
    void testValidationConstraintsInOpenApi(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        String yml = assertDoesNotThrow(() -> client.retrieve("/swagger/yappy-api-0.1.yml"));
        
        // Verify validation patterns are documented
        assertTrue(yml.contains("pattern:"));
        assertTrue(yml.contains("^[a-z0-9-]+$")); // Service ID pattern
        assertTrue(yml.contains("required:"));
        assertTrue(yml.contains("minimum:"));
        assertTrue(yml.contains("maximum:"));
    }

    @Test
    void testSecurityNotRequired(@Client("/") HttpClient httpClient) {
        // For now, verify that endpoints are accessible without auth
        // When security is added, this test should be updated
        BlockingHttpClient client = httpClient.toBlocking();
        
        // Test a few endpoints without auth headers
        HttpResponse<?> pipelinesResponse = client.exchange("/api/v1/pipelines");
        assertEquals(HttpStatus.OK, pipelinesResponse.getStatus());
        
        HttpResponse<?> modulesResponse = client.exchange("/api/v1/modules");
        assertEquals(HttpStatus.OK, modulesResponse.getStatus());
    }
}