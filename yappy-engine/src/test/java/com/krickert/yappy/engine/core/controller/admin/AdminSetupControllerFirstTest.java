package com.krickert.yappy.engine.core.controller.admin;

import com.krickert.yappy.engine.controller.admin.dto.*;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.DisplayName;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// REMOVE: import io.micronaut.context.annotation.Replaces; // No longer using @Replaces directly here for this mock
import io.micronaut.test.annotation.MockBean; // ADD THIS
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.io.FileReader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*; // Can use static imports for mock() and reset()

import io.micronaut.core.type.Argument;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;

import java.util.List;

@MicronautTest
class AdminSetupControllerFirstTest { // Renamed as per your log

    @Inject
    @Client("/")
    HttpClient client;

    @Inject // This should now receive the mock from the @MockBean factory method
    ConsulBusinessOperationsService mockConsulService;

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private Path mockYappyDir;
    private Path mockBootstrapFile;

    // Use @MockBean to provide the mock for ConsulBusinessOperationsService
    @MockBean(ConsulBusinessOperationsService.class)
    ConsulBusinessOperationsService consulBusinessOperationsServiceMock() {
        System.out.println("### @MockBean factory: Creating mock for ConsulBusinessOperationsService ###");
        return mock(ConsulBusinessOperationsService.class); // Creates and returns a Mockito mock
    }

    @BeforeEach
    void setUp() throws IOException {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());

        mockYappyDir = tempDir.resolve(".yappy");
        Files.createDirectories(mockYappyDir);
        mockBootstrapFile = mockYappyDir.resolve("engine-bootstrap.properties");

        System.clearProperty("consul.client.host");
        System.clearProperty("consul.client.port");
        System.clearProperty("consul.client.acl-token");

        // This reset should now work because mockConsulService will be a mock
        assertNotNull(mockConsulService, "mockConsulService should be injected by @MockBean factory.");
        // Optional: Verify it's a mock before resetting, though @MockBean should guarantee it.
        if (Mockito.mockingDetails(mockConsulService).isMock()) {
            reset(mockConsulService);
            System.out.println("### SETUP: Mockito.reset() called on mock instance: " + mockConsulService + " ###");
        } else {
            // This case should ideally not be hit if @MockBean works as expected.
            System.err.println("### SETUP WARNING: mockConsulService is NOT a mock. @MockBean did not work as expected. Instance: " + mockConsulService);
            // You might want to fail the test here if it's not a mock
            // fail("mockConsulService was not replaced by a mock from @MockBean factory.");
        }

        // IMPORTANT: Stub for DefaultConfigurationSeeder to prevent NPE during startup
        // This assumes your mockConsulService is now indeed a mock.
        System.out.println("### SETUP: Stubbing getPipelineClusterConfig for mockConsulService: " + mockConsulService + " ###");
        when(mockConsulService.getPipelineClusterConfig(anyString()))
                .thenReturn(Mono.just(java.util.Optional.empty()));
        System.out.println("### SETUP: Stubbed getPipelineClusterConfig for DefaultConfigurationSeeder ###");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
        if (Files.exists(mockBootstrapFile)) {
            Files.delete(mockBootstrapFile);
        }
        if (Files.exists(mockYappyDir)) {
            Files.delete(mockYappyDir);
        }
    }

    private void createMockPropertiesFile(Properties props) throws IOException {
        // ... (no change)
        try (FileWriter writer = new FileWriter(mockBootstrapFile.toFile())) {
            props.store(writer, "Test Bootstrap Properties");
        }
    }

    private Properties loadPropertiesFromFile() throws IOException {
        // ... (no change)
        Properties props = new Properties();
        if (Files.exists(mockBootstrapFile)) {
            try (FileReader reader = new FileReader(mockBootstrapFile.toFile())) {
                props.load(reader);
            }
        }
        return props;
    }

    // --- Your existing tests ---
    // They should now use `mockConsulService` which will be the actual mock.


    @Test
    @DisplayName("GET /consul - All Properties Exist")
    void testGetCurrentConsulConfiguration_allPropertiesExist() throws IOException {
        // ... (no change to test logic itself needed here as it doesn't use the mock)
        Properties props = new Properties();
        props.setProperty("yappy.bootstrap.consul.host", "testhost");
        props.setProperty("yappy.bootstrap.consul.port", "1234");
        props.setProperty("yappy.bootstrap.consul.aclToken", "testtoken");
        props.setProperty("yappy.bootstrap.cluster.selectedName", "testcluster");
        createMockPropertiesFile(props);

        HttpRequest<Object> request = HttpRequest.GET("/api/setup/consul");
        var response = client.toBlocking().exchange(request, ConsulConfigResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        ConsulConfigResponse body = response.body();
        assertNotNull(body);
        assertEquals("testhost", body.getHost());
        assertEquals("1234", body.getPort());
        assertEquals("testtoken", body.getAclToken());
        assertEquals("testcluster", body.getSelectedYappyClusterName());
    }

    @Test
    @DisplayName("GET /consul - Some Properties Missing")
    void testGetCurrentConsulConfiguration_somePropertiesMissing() throws IOException {
        Properties props = new Properties();
        props.setProperty("yappy.bootstrap.consul.host", "testhost");
        createMockPropertiesFile(props);

        HttpRequest<Object> request = HttpRequest.GET("/api/setup/consul");
        var response = client.toBlocking().exchange(request, ConsulConfigResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        ConsulConfigResponse body = response.body();
        assertNotNull(body);
        assertEquals("testhost", body.getHost());
        assertNull(body.getPort());
        assertNull(body.getAclToken());
        assertNull(body.getSelectedYappyClusterName());
    }

    @Test
    @DisplayName("GET /consul - Properties File Does Not Exist")
    void testGetCurrentConsulConfiguration_propertiesFileDoesNotExist() throws IOException {
        if (Files.exists(mockBootstrapFile)) {
            Files.delete(mockBootstrapFile);
        }

        HttpRequest<Object> request = HttpRequest.GET("/api/setup/consul");
        var response = client.toBlocking().exchange(request, ConsulConfigResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        ConsulConfigResponse body = response.body();
        assertNotNull(body);
        assertNull(body.getHost());
        assertNull(body.getPort());
        assertNull(body.getAclToken());
        assertNull(body.getSelectedYappyClusterName());
    }

    @Test
    @DisplayName("POST /consul - Valid Request, Ping Successful")
    void testSetConsulConfiguration_validRequest_pingSuccess() throws IOException {
        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.isConsulAvailable()).thenReturn(Mono.just(true)); // This should now work on the mock

        SetConsulConfigRequest newConfig = new SetConsulConfigRequest("newhost", 8501, "newtoken");
        HttpRequest<SetConsulConfigRequest> request = HttpRequest.POST("/api/setup/consul", newConfig);
        // This is the test that was previously failing with "Method Not Allowed"
        // We still need to ensure AdminSetupController has a POST /api/setup/consul endpoint
        var response = client.toBlocking().exchange(request, SetConsulConfigResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        // ... (rest of assertions)
        SetConsulConfigResponse body = response.body();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertTrue(body.getMessage().contains("Ping successful"));
        assertTrue(body.getMessage().contains("restart the Yappy engine"));
        assertNotNull(body.getCurrentConfig());
        assertEquals("newhost", body.getCurrentConfig().getHost());
        assertEquals("8501", body.getCurrentConfig().getPort());
        assertEquals("newtoken", body.getCurrentConfig().getAclToken());

        Properties savedProps = loadPropertiesFromFile();
        assertEquals("newhost", savedProps.getProperty("yappy.bootstrap.consul.host"));
        assertEquals("8501", savedProps.getProperty("yappy.bootstrap.consul.port"));
        assertEquals("newtoken", savedProps.getProperty("yappy.bootstrap.consul.aclToken"));

        assertEquals("newhost", System.getProperty("consul.client.host"));
        assertEquals("8501", System.getProperty("consul.client.port"));
        assertEquals("newtoken", System.getProperty("consul.client.acl-token"));
    }

    @Test
    @DisplayName("POST /consul - Valid Request, Ping Fails")
    void testSetConsulConfiguration_validRequest_pingFails() throws IOException {
        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.isConsulAvailable()).thenReturn(Mono.just(false));

        SetConsulConfigRequest newConfig = new SetConsulConfigRequest("anotherhost", 8502, null);
        HttpRequest<SetConsulConfigRequest> request = HttpRequest.POST("/api/setup/consul", newConfig);
        var response = client.toBlocking().exchange(request, SetConsulConfigResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        SetConsulConfigResponse body = response.body();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertTrue(body.getMessage().contains("failed to ping Consul"));
        assertTrue(body.getMessage().contains("restart the Yappy engine"));
        assertNotNull(body.getCurrentConfig());
        assertEquals("anotherhost", body.getCurrentConfig().getHost());
        assertEquals("8502", body.getCurrentConfig().getPort());
        assertNull(body.getCurrentConfig().getAclToken());

        Properties savedProps = loadPropertiesFromFile();
        assertEquals("anotherhost", savedProps.getProperty("yappy.bootstrap.consul.host"));
        assertEquals("8502", savedProps.getProperty("yappy.bootstrap.consul.port"));
        assertNull(savedProps.getProperty("yappy.bootstrap.consul.aclToken"));

        assertEquals("anotherhost", System.getProperty("consul.client.host"));
        assertEquals("8502", System.getProperty("consul.client.port"));
        assertNull(System.getProperty("consul.client.acl-token"));
    }

    @Test
    @DisplayName("POST /consul - Invalid Request (Missing Host)")
    void testSetConsulConfiguration_invalidRequest_missingHost() {
        SetConsulConfigRequest newConfig = new SetConsulConfigRequest(null, 8500, "token");

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/setup/consul", newConfig), SetConsulConfigResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }

    @Test
    @DisplayName("POST /consul - Invalid Request (Invalid Port)")
    void testSetConsulConfiguration_invalidRequest_invalidPort() {
        SetConsulConfigRequest newConfig = new SetConsulConfigRequest("host", 0, "token");

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/setup/consul", newConfig), SetConsulConfigResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }

     @Test
    @DisplayName("POST /consul - Update existing properties, remove ACL token")
    void testSetConsulConfiguration_updateAndRemoveAcl() throws IOException {
        Properties initialProps = new Properties();
        initialProps.setProperty("yappy.bootstrap.consul.host", "oldhost");
        initialProps.setProperty("yappy.bootstrap.consul.port", "1111");
        initialProps.setProperty("yappy.bootstrap.consul.aclToken", "oldtoken");
        initialProps.setProperty("yappy.bootstrap.cluster.selectedName", "oldCluster");
        createMockPropertiesFile(initialProps);

        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.isConsulAvailable()).thenReturn(Mono.just(true));

        SetConsulConfigRequest updateRequest = new SetConsulConfigRequest("updatedhost", 2222, "");
        HttpRequest<SetConsulConfigRequest> request = HttpRequest.POST("/api/setup/consul", updateRequest);
        var response = client.toBlocking().exchange(request, SetConsulConfigResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        SetConsulConfigResponse body = response.body();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertEquals("updatedhost", body.getCurrentConfig().getHost());
        assertEquals("2222", body.getCurrentConfig().getPort());
        assertNull(body.getCurrentConfig().getAclToken());
        assertEquals("oldCluster", body.getCurrentConfig().getSelectedYappyClusterName());

        Properties savedProps = loadPropertiesFromFile();
        assertEquals("updatedhost", savedProps.getProperty("yappy.bootstrap.consul.host"));
        assertEquals("2222", savedProps.getProperty("yappy.bootstrap.consul.port"));
        assertFalse(savedProps.containsKey("yappy.bootstrap.consul.aclToken"));
        assertEquals("oldCluster", savedProps.getProperty("yappy.bootstrap.cluster.selectedName"));

        assertEquals("updatedhost", System.getProperty("consul.client.host"));
        assertEquals("2222", System.getProperty("consul.client.port"));
        assertNull(System.getProperty("consul.client.acl-token"));
    }

    // --- Tests for GET /clusters ---

    // ... (Include all your other original tests, ensuring they use `mockConsulService` for any when/verify) ...
    // Example for another test that uses the mock:
    @Test
    @DisplayName("GET /clusters - Service returns cluster keys")
    void testListAvailableYappyClusters_returnsClusters() {
        List<String> mockKeys = Arrays.asList(
                "yappy/pipeline-clusters/cluster-A/",
                "yappy/pipeline-clusters/cluster-B",
                "yappy/pipeline-clusters/another-cluster-C/"
        );
        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.listAvailableClusterNames()).thenReturn(Mono.just(mockKeys)); // This should now work

        HttpRequest<Object> request = HttpRequest.GET("/api/setup/clusters");
        var response = client.toBlocking().exchange(request, Argument.listOf(YappyClusterInfo.class));

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        List<YappyClusterInfo> clusters = response.body();
        assertNotNull(clusters);
        assertEquals(3, clusters.size());
        verify(mockConsulService).listAvailableClusterNames();
    }

    @Test
    @DisplayName("GET /clusters - Service returns empty list")
    void testListAvailableYappyClusters_emptyList() {
        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.listAvailableClusterNames()).thenReturn(Mono.just(Collections.emptyList()));

        HttpRequest<Object> request = HttpRequest.GET("/api/setup/clusters");
        var response = client.toBlocking().exchange(request, Argument.listOf(YappyClusterInfo.class));

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        List<YappyClusterInfo> clusters = response.body();
        assertNotNull(clusters);
        assertTrue(clusters.isEmpty());
        verify(mockConsulService).listAvailableClusterNames();
    }

    @Test
    @DisplayName("GET /clusters - Service throws exception")
    void testListAvailableYappyClusters_serviceThrowsException() {
        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.listAvailableClusterNames()).thenReturn(Mono.error(new RuntimeException("Consul error")));

        HttpRequest<Object> request = HttpRequest.GET("/api/setup/clusters");
        var response = client.toBlocking().exchange(request, Argument.listOf(YappyClusterInfo.class));

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        List<YappyClusterInfo> clusters = response.body();
        assertNotNull(clusters);
        assertTrue(clusters.isEmpty());
        verify(mockConsulService).listAvailableClusterNames();
    }

     @Test
    @DisplayName("GET /clusters - Malformed cluster keys")
    void testListAvailableYappyClusters_malformedKeys() {
        List<String> mockKeys = Arrays.asList(
            "yappy/pipeline-clusters/",
            "yappy/pipeline-clusters/clusterD",
            "some/other/path/clusterE"
        );
        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.listAvailableClusterNames()).thenReturn(Mono.just(mockKeys));

        HttpRequest<Object> request = HttpRequest.GET("/api/setup/clusters");
        var response = client.toBlocking().exchange(request, Argument.listOf(YappyClusterInfo.class));

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        List<YappyClusterInfo> clusters = response.body();
        assertNotNull(clusters);
        assertEquals(3, clusters.size());
        // ... (assertions for cluster details as in original)
    }

    // --- Tests for POST /clusters ---

    // --- Diagnostic Test for POST /api/setup/clusters ---
    @Test
    @DisplayName("POST /clusters - Valid Request - With Initial Modules (Diagnostic)")
    void testCreateNewYappyCluster_withInitialModules_Diagnostic() throws IOException {
        String clusterName = "cluster-with-modules";
        List<PipelineModuleInput> modules = Arrays.asList(
                new PipelineModuleInput("module1", "Module One"),
                new PipelineModuleInput("module2", "Module Two")
        );
        CreateClusterRequest createRequest = new CreateClusterRequest(clusterName, null, modules);

        assertNotNull(mockConsulService, "mockConsulService should be injected by Micronaut and not null here.");

        System.out.println("### TEST METHOD (Diagnostic): Configuring mock " + mockConsulService + " to throw diagnostic exception ###");
        when(mockConsulService.storeClusterConfiguration(
                anyString(),
                any(PipelineClusterConfig.class)
        )).thenThrow(new RuntimeException("MOCK_WAS_CALLED_FOR_storeClusterConfiguration"));

        HttpRequest<CreateClusterRequest> request = HttpRequest.POST("/api/setup/clusters", createRequest);
        Exception thrownException = null;
        String responseBodyAsString = null;

        try {
            System.out.println("### TEST METHOD (Diagnostic): Executing HTTP client call... ###");
            client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            thrownException = e;
            responseBodyAsString = e.getResponse().getBody(String.class).orElse("[No Response Body From HttpClientResponseException]");
        } catch (Exception e) {
            thrownException = e;
        }

        assertNotNull(thrownException, "An exception was expected.");
        System.out.println("### TEST METHOD (Diagnostic): Exception caught: " + thrownException.getClass().getName() + " - " + thrownException.getMessage() + " ###");
        if (responseBodyAsString != null && thrownException instanceof HttpClientResponseException) {
            System.out.println("### TEST METHOD (Diagnostic): HTTP Response Body on error: " + responseBodyAsString + " ###");
        }

        Throwable effectiveException = thrownException;
        if (thrownException instanceof HttpClientResponseException && thrownException.getCause() instanceof RuntimeException) {
            effectiveException = thrownException.getCause();
        }

        if (effectiveException instanceof RuntimeException && "MOCK_WAS_CALLED_FOR_storeClusterConfiguration".equals(effectiveException.getMessage())) {
            System.out.println("SUCCESSFUL DIAGNOSIS (Mocking): The MOCK 'storeClusterConfiguration' WAS CALLED!");
            // If this happens, then the routing issue ("More than 1 route matched") was likely primary.
            // If you still get "More than 1 route matched" here, then that's the problem to solve.
        } else {
            System.err.println("DIAGNOSIS (Mocking) FAILED: The mock was NOT effectively called for storeClusterConfiguration, or another error occurred first.");
            if (thrownException != null) {
                System.err.println("Actual exception in diagnostic test was: ");
                thrownException.printStackTrace();
            }
            // The "More than 1 route matched" error would appear here if it's still the case.
            fail("Mock for storeClusterConfiguration was not called as expected OR a preceding error (like routing) occurred. Check console. Error: " +
                    (thrownException != null ? thrownException.getMessage() : "Unknown error, exception was null"));
        }
    }


    // --- Original tests for POST /clusters - Keep these or adapt them after diagnosis ---
    @Test
    @DisplayName("POST /clusters - Valid Request - Minimal (only clusterName)")
    void testCreateNewYappyCluster_minimal() throws IOException {
        String clusterName = "new-minimal-cluster";
        CreateClusterRequest createRequest = new CreateClusterRequest(clusterName, null, null);

        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.storeClusterConfiguration(Mockito.eq(clusterName), Mockito.any(PipelineClusterConfig.class)))
            .thenReturn(Mono.just(true));

        HttpRequest<CreateClusterRequest> request = HttpRequest.POST("/api/setup/clusters", createRequest);
        var response = client.toBlocking().exchange(request, CreateClusterResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        CreateClusterResponse body = response.body();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertEquals(clusterName, body.getClusterName());
        // ... other assertions from original test
        verify(mockConsulService).storeClusterConfiguration(Mockito.eq(clusterName), Mockito.argThat( (PipelineClusterConfig config) ->
            clusterName.equals(config.clusterName()) &&
            config.pipelineGraphConfig().pipelines().isEmpty() &&
            config.pipelineModuleMap().availableModules().isEmpty()
        ));
    }

    // Add back your other POST /clusters tests here, ensuring they use mockConsulService correctly
    // e.g., testCreateNewYappyCluster_withFirstPipeline, _withInitialModules (original), _allFields, etc.
    // For each, ensure:
    // 1. `assertNotNull(mockConsulService, "...");` before using it.
    // 2. Correct `when(...).thenReturn(...)` setup.
    // 3. Correct `verify(...)` if needed.

    @Test
    @DisplayName("POST /clusters - Consul Store Fails (returns false)")
    void testCreateNewYappyCluster_consulStoreReturnsFalse() {
        String clusterName = "fail-store-cluster";
        CreateClusterRequest createRequest = new CreateClusterRequest(clusterName, null, null);

        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.storeClusterConfiguration(Mockito.eq(clusterName), Mockito.any(PipelineClusterConfig.class)))
            .thenReturn(Mono.just(false));

        HttpRequest<CreateClusterRequest> request = HttpRequest.POST("/api/setup/clusters", createRequest);
        var response = client.toBlocking().exchange(request, CreateClusterResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        CreateClusterResponse body = response.body();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        // ... other assertions
    }

    @Test
    @DisplayName("POST /clusters - Consul Store Throws Exception")
    void testCreateNewYappyCluster_consulStoreThrowsException() {
        String clusterName = "exception-cluster";
        CreateClusterRequest createRequest = new CreateClusterRequest(clusterName, null, null);

        assertNotNull(mockConsulService, "mockConsulService should be injected and not null");
        when(mockConsulService.storeClusterConfiguration(Mockito.eq(clusterName), Mockito.any(PipelineClusterConfig.class)))
            .thenReturn(Mono.error(new RuntimeException("Consul unavailable")));

        HttpRequest<CreateClusterRequest> request = HttpRequest.POST("/api/setup/clusters", createRequest);
        var response = client.toBlocking().exchange(request, CreateClusterResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        CreateClusterResponse body = response.body();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        // ... other assertions
    }

    @Test
    @DisplayName("POST /clusters - Invalid Request (Null Cluster Name)")
    void testCreateNewYappyCluster_invalidRequest_nullClusterName() {
        CreateClusterRequest createRequest = new CreateClusterRequest(null, "pipe", null);
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/setup/clusters", createRequest), CreateClusterResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }

    @Test
    @DisplayName("POST /clusters - Invalid Request (Empty Cluster Name)")
    void testCreateNewYappyCluster_invalidRequest_emptyClusterName() {
        CreateClusterRequest createRequest = new CreateClusterRequest("", "pipe", null);
         HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/setup/clusters", createRequest), CreateClusterResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }

    @Test
    @DisplayName("POST /clusters - Invalid Request (Module missing ID)")
    void testCreateNewYappyCluster_invalidModule_missingId() {
        List<PipelineModuleInput> modules = new ArrayList<>();
        modules.add(new PipelineModuleInput(null, "Module Name"));
        CreateClusterRequest createRequest = new CreateClusterRequest("validcluster", null, modules);

         HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/setup/clusters", createRequest), CreateClusterResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }


    // --- Tests for POST /cluster/select ---
    @Test
    @DisplayName("POST /cluster/select - Valid Request")
    void testSelectActiveYappyCluster_validRequest() throws IOException {
        String clusterToSelect = "test-cluster-select";
        SelectClusterRequest selectRequest = new SelectClusterRequest(clusterToSelect);

        Properties initialProps = new Properties();
        initialProps.setProperty("yappy.bootstrap.consul.host", "initialhost");
        initialProps.setProperty("yappy.bootstrap.cluster.selectedName", "old-selected-cluster");
        createMockPropertiesFile(initialProps);

        HttpRequest<SelectClusterRequest> request = HttpRequest.POST("/api/setup/cluster/select", selectRequest);
        var response = client.toBlocking().exchange(request, SelectClusterResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        SelectClusterResponse body = response.body();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        // ... other assertions
    }

    @Test
    @DisplayName("POST /cluster/select - Invalid Request (Null Cluster Name)")
    void testSelectActiveYappyCluster_invalidRequest_nullClusterName() {
        SelectClusterRequest selectRequest = new SelectClusterRequest(null);
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/setup/cluster/select", selectRequest), SelectClusterResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }

    @Test
    @DisplayName("POST /cluster/select - Invalid Request (Empty Cluster Name)")
    void testSelectActiveYappyCluster_invalidRequest_emptyClusterName() {
        SelectClusterRequest selectRequest = new SelectClusterRequest("");
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/setup/cluster/select", selectRequest), SelectClusterResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }

    @Test
    @DisplayName("POST /cluster/select - Bootstrap file initially does not exist")
    void testSelectActiveYappyCluster_noInitialBootstrapFile() throws IOException {
        String clusterToSelect = "new-cluster-for-new-file";
        SelectClusterRequest selectRequest = new SelectClusterRequest(clusterToSelect);

        if (Files.exists(mockBootstrapFile)) {
            Files.delete(mockBootstrapFile);
        }

        HttpRequest<SelectClusterRequest> request = HttpRequest.POST("/api/setup/cluster/select", selectRequest);
        var response = client.toBlocking().exchange(request, SelectClusterResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        SelectClusterResponse body = response.body();
        assertNotNull(body);
        assertTrue(body.isSuccess());

        Properties savedProps = loadPropertiesFromFile();
        assertTrue(Files.exists(mockBootstrapFile));
        assertEquals(clusterToSelect, savedProps.getProperty("yappy.bootstrap.cluster.selectedName"));
    }
}