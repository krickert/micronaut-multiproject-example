package com.krickert.yappy.bootstrap.service;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.SchemaReference;

import com.krickert.yappy.bootstrap.api.*;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BootstrapConfigServiceImplTest {

    @Mock
    private ConsulBusinessOperationsService mockConsulBusinessOpsService;

    @Mock
    private ApplicationContext mockApplicationContext;

    private BootstrapConfigServiceImpl service;

    @TempDir
    Path tempDir;

    private Path testBootstrapFilePath;

    private static final String YAPPY_BOOTSTRAP_CONSUL_HOST = "yappy.bootstrap.consul.host";
    private static final String YAPPY_BOOTSTRAP_CONSUL_PORT = "yappy.bootstrap.consul.port";
    private static final String YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN = "yappy.bootstrap.consul.acl_token";
    private static final String YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME = "yappy.bootstrap.cluster.selected_name";
    private static final String YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH = "yappy.bootstrap.consul.config.base_path";
    private static final String DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH = "pipeline-configs/clusters";


    @Captor
    private ArgumentCaptor<ConsulConnectionStatus> consulConnectionStatusCaptor;
    @Captor
    private ArgumentCaptor<ConsulConfigDetails> consulConfigDetailsCaptor;
    @Captor
    private ArgumentCaptor<ClusterList> clusterListCaptor;
    @Captor
    private ArgumentCaptor<OperationStatus> operationStatusCaptor;
    @Captor
    private ArgumentCaptor<ClusterCreationStatus> clusterCreationStatusCaptor;
    @Captor
    private ArgumentCaptor<PipelineClusterConfig> pipelineClusterConfigCaptor;
    @Captor
    private ArgumentCaptor<Throwable> throwableCaptor;


    @BeforeEach
    void setUp() throws IOException {
        testBootstrapFilePath = tempDir.resolve("test-engine-bootstrap.properties");
        Files.deleteIfExists(testBootstrapFilePath); // Ensure clean state
        Files.createFile(testBootstrapFilePath); // Create the file so it exists for tests that load properties
        service = new BootstrapConfigServiceImpl(mockConsulBusinessOpsService, testBootstrapFilePath.toString(), mockApplicationContext);
        System.clearProperty("yappy.consul.configured"); // Clear before each test

        // Mock the applicationContext.publishEvent call leniently
        lenient().doNothing().when(mockApplicationContext).publishEvent(any(ApplicationEvent.class));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("yappy.consul.configured"); // Clear after each test
    }


    private void writeTestProperties(Properties props) throws IOException {
        // Ensure base path is always present for tests that might need it,
        // unless a test specifically wants to test its absence.
        if (!props.containsKey(YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH)) {
            props.setProperty(YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH, DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH);
        }
        try (OutputStream output = Files.newOutputStream(testBootstrapFilePath)) {
            props.store(output, null);
        }
    }

    private Properties loadTestProperties() throws IOException {
        Properties props = new Properties();
        if (Files.exists(testBootstrapFilePath)) {
            try (var input = Files.newInputStream(testBootstrapFilePath)) {
                props.load(input);
            }
        }
        return props;
    }

    @SuppressWarnings("unchecked")
    private <T> StreamObserver<T> mockStreamObserver() {
        return (StreamObserver<T>) mock(StreamObserver.class);
    }

    @Test
    void getConsulConfiguration_whenFileDoesNotExist_returnsEmptyDetails() throws IOException {
        Files.deleteIfExists(testBootstrapFilePath);
        StreamObserver<ConsulConfigDetails> mockObserver = mockStreamObserver();
        service.getConsulConfiguration(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(consulConfigDetailsCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ConsulConfigDetails details = consulConfigDetailsCaptor.getValue();
        assertTrue(details.getHost().isEmpty(), "Host should be empty");
        assertEquals(0, details.getPort(), "Port should be default (0)");
        assertFalse(details.hasAclToken(), "ACL token should not be set");
        assertFalse(details.hasSelectedYappyClusterName(), "Selected cluster name should not be set");
    }

    @Test
    void setAndGetConsulConfiguration_success() throws IOException {
        when(mockConsulBusinessOpsService.isConsulAvailable()).thenReturn(Mono.just(true));

        StreamObserver<ConsulConnectionStatus> setObserver = mockStreamObserver();
        ConsulConfigDetails requestDetails = ConsulConfigDetails.newBuilder()
                .setHost("testhost.local")
                .setPort(9500)
                .setAclToken("test-token-123")
                .build();

        service.setConsulConfiguration(requestDetails, setObserver);

        verify(setObserver).onNext(consulConnectionStatusCaptor.capture());
        verify(setObserver).onCompleted();
        verify(setObserver, never()).onError(any());

        ConsulConnectionStatus status = consulConnectionStatusCaptor.getValue();
        assertTrue(status.getSuccess());
        assertTrue(status.hasCurrentConfig());
        assertEquals("testhost.local", status.getCurrentConfig().getHost());
        assertEquals(9500, status.getCurrentConfig().getPort());
        assertEquals("test-token-123", status.getCurrentConfig().getAclToken());
        assertFalse(status.getCurrentConfig().hasSelectedYappyClusterName());


        Properties props = loadTestProperties();
        assertEquals("testhost.local", props.getProperty(YAPPY_BOOTSTRAP_CONSUL_HOST));
        assertEquals("9500", props.getProperty(YAPPY_BOOTSTRAP_CONSUL_PORT));
        assertEquals("test-token-123", props.getProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN));

        StreamObserver<ConsulConfigDetails> getObserver = mockStreamObserver();
        service.getConsulConfiguration(Empty.newBuilder().build(), getObserver);

        verify(getObserver).onNext(consulConfigDetailsCaptor.capture());
        verify(getObserver).onCompleted();
        verify(getObserver, never()).onError(any());

        ConsulConfigDetails retrievedDetails = consulConfigDetailsCaptor.getValue();
        assertEquals("testhost.local", retrievedDetails.getHost());
        assertEquals(9500, retrievedDetails.getPort());
        assertEquals("test-token-123", retrievedDetails.getAclToken());
        assertFalse(retrievedDetails.hasSelectedYappyClusterName(), "Selected cluster should not be set yet");
    }

    @Test
    void setConsulConfiguration_updateExisting() throws IOException {
        when(mockConsulBusinessOpsService.isConsulAvailable()).thenReturn(Mono.just(true));

        Properties initialProps = new Properties();
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "oldhost");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "1111");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN, "old-token");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, "clusterA");
        writeTestProperties(initialProps);

        StreamObserver<ConsulConnectionStatus> setObserver = mockStreamObserver();
        ConsulConfigDetails newDetails = ConsulConfigDetails.newBuilder()
                .setHost("newhost.com")
                .setPort(2222)
                .build();
        service.setConsulConfiguration(newDetails, setObserver);

        verify(setObserver).onNext(consulConnectionStatusCaptor.capture());
        ConsulConnectionStatus status = consulConnectionStatusCaptor.getValue();
        assertTrue(status.getSuccess());
        assertTrue(status.hasCurrentConfig());
        assertEquals("newhost.com", status.getCurrentConfig().getHost());
        assertEquals(2222, status.getCurrentConfig().getPort());
        assertFalse(status.getCurrentConfig().hasAclToken(), "ACL token should have been removed");
        assertTrue(status.getCurrentConfig().hasSelectedYappyClusterName(), "Selected cluster name should be present in response");
        assertEquals("clusterA", status.getCurrentConfig().getSelectedYappyClusterName());

        Properties updatedProps = loadTestProperties();
        assertEquals("newhost.com", updatedProps.getProperty(YAPPY_BOOTSTRAP_CONSUL_HOST));
        assertEquals("2222", updatedProps.getProperty(YAPPY_BOOTSTRAP_CONSUL_PORT));
        assertNull(updatedProps.getProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN), "ACL token should be removed from props");
        assertEquals("clusterA", updatedProps.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME), "Selected cluster name should remain in props");
    }

    @Test
    void getConsulConfiguration_readsSelectedClusterName() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "somehost");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "1234");
        props.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, "mySelectedCluster");
        writeTestProperties(props);

        StreamObserver<ConsulConfigDetails> getObserver = mockStreamObserver();
        service.getConsulConfiguration(Empty.newBuilder().build(), getObserver);

        verify(getObserver).onNext(consulConfigDetailsCaptor.capture());
        ConsulConfigDetails retrievedDetails = consulConfigDetailsCaptor.getValue();
        assertEquals("somehost", retrievedDetails.getHost());
        assertEquals(1234, retrievedDetails.getPort());
        assertTrue(retrievedDetails.hasSelectedYappyClusterName());
        assertEquals("mySelectedCluster", retrievedDetails.getSelectedYappyClusterName());
    }

    @Test
    void getConsulConfiguration_invalidPortFormat_returnsDefaultPort() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "hostwithbadport");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "not-a-number");
        writeTestProperties(props);

        StreamObserver<ConsulConfigDetails> getObserver = mockStreamObserver();
        service.getConsulConfiguration(Empty.newBuilder().build(), getObserver);

        verify(getObserver).onNext(consulConfigDetailsCaptor.capture());
        ConsulConfigDetails retrievedDetails = consulConfigDetailsCaptor.getValue();
        assertEquals("hostwithbadport", retrievedDetails.getHost());
        assertEquals(0, retrievedDetails.getPort(), "Port should be default (0) or indicate error on invalid format");
    }

    @Test
    void listAvailableClusters_success() throws IOException {
        System.setProperty("yappy.consul.configured", "true");
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "testhost");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "8500");
        // YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH is set by default in writeTestProperties
        writeTestProperties(props);

        List<String> mockClusterNames = Arrays.asList("cluster1", "cluster2", "cluster3");
        lenient().when(mockConsulBusinessOpsService.listAvailableClusterNames()).thenReturn(Mono.just(mockClusterNames));
        // This mock is for determineInitialClusterStatus, which is NOT called if status is hardcoded in service
        lenient().when(mockConsulBusinessOpsService.getPipelineClusterConfig(anyString()))
                .thenReturn(Mono.just(Optional.empty()));
        // This mock is for getClusterId when consulBusinessOperationsService is NOT null
        when(mockConsulBusinessOpsService.getFullClusterKey(anyString())).thenAnswer(invocation ->
                DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/" + invocation.getArgument(0)
        );

        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(clusterListCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterList clusterList = clusterListCaptor.getValue();
        assertEquals(3, clusterList.getClustersCount());
        assertEquals("cluster1", clusterList.getClusters(0).getClusterName());
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/cluster1", clusterList.getClusters(0).getClusterId());
        assertEquals("NEEDS_VERIFICATION", clusterList.getClusters(0).getStatus()); // Service hardcodes this
        assertEquals("cluster2", clusterList.getClusters(1).getClusterName());
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/cluster2", clusterList.getClusters(1).getClusterId());
        assertEquals("NEEDS_VERIFICATION", clusterList.getClusters(1).getStatus());
        assertEquals("cluster3", clusterList.getClusters(2).getClusterName());
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/cluster3", clusterList.getClusters(2).getClusterId());
        assertEquals("NEEDS_VERIFICATION", clusterList.getClusters(2).getStatus());
    }

    @Test
    void listAvailableClusters_simulatedSuccess() throws IOException {
        System.clearProperty("yappy.consul.configured");
        Properties props = new Properties();
        // YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH is set by default in writeTestProperties
        // This is used by the local getFullClusterKey in simulation
        writeTestProperties(props);

        service = new BootstrapConfigServiceImpl(null, testBootstrapFilePath.toString(), mockApplicationContext);

        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(clusterListCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterList clusterList = clusterListCaptor.getValue();
        assertEquals(2, clusterList.getClustersCount());
        assertEquals("simulatedCluster_NoService1", clusterList.getClusters(0).getClusterName());
        // Assuming service-side fix to set "UNKNOWN" for simulation
        assertEquals("UNKNOWN", clusterList.getClusters(0).getStatus());
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/simulatedCluster_NoService1", clusterList.getClusters(0).getClusterId());
        assertEquals("simulatedCluster_NoService2", clusterList.getClusters(1).getClusterName());
        assertEquals("UNKNOWN", clusterList.getClusters(1).getStatus());
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/simulatedCluster_NoService2", clusterList.getClusters(1).getClusterId());
    }


    @Test
    void listAvailableClusters_emptyListFromService() throws IOException {
        System.setProperty("yappy.consul.configured", "true");
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "testhost");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "8500");
        writeTestProperties(props); // Will also set default base path

        lenient().when(mockConsulBusinessOpsService.listAvailableClusterNames()).thenReturn(Mono.just(Collections.emptyList()));

        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(clusterListCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterList clusterList = clusterListCaptor.getValue();
        assertEquals(0, clusterList.getClustersCount());
        verify(mockConsulBusinessOpsService).listAvailableClusterNames();
    }

    @Test
    void listAvailableClusters_serviceThrowsException() throws IOException {
        System.setProperty("yappy.consul.configured", "true");
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "testhost");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "8500");
        writeTestProperties(props); // Will also set default base path

        lenient().when(mockConsulBusinessOpsService.listAvailableClusterNames())
                .thenReturn(Mono.error(new RuntimeException("Consul connection failed")));

        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver, never()).onNext(any());
        verify(mockObserver, never()).onCompleted();
        verify(mockObserver).onError(throwableCaptor.capture());

        Throwable t = throwableCaptor.getValue();
        assertTrue(t instanceof io.grpc.StatusRuntimeException);
        assertTrue(((io.grpc.StatusRuntimeException) t).getStatus().getDescription().contains("Consul connection failed"));
    }

    @Test
    void selectExistingCluster_success_emptyFile() throws IOException {
        StreamObserver<OperationStatus> mockObserver = mockStreamObserver();
        ClusterSelection selection = ClusterSelection.newBuilder().setClusterName("testCluster1").build();

        service.selectExistingCluster(selection, mockObserver);

        verify(mockObserver).onNext(operationStatusCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        OperationStatus status = operationStatusCaptor.getValue();
        assertTrue(status.getSuccess());
        assertEquals("Successfully selected cluster 'testCluster1'.", status.getMessage());

        Properties propsFromFile = loadTestProperties();
        assertEquals("testCluster1", propsFromFile.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
    }

    @Test
    void selectExistingCluster_success_updatesExistingFile() throws IOException {
        Properties initialProps = new Properties();
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "existingHost");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "1234");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, "oldCluster");
        writeTestProperties(initialProps);

        StreamObserver<OperationStatus> mockObserver = mockStreamObserver();
        ClusterSelection selection = ClusterSelection.newBuilder().setClusterName("newSelectedCluster").build();
        service.selectExistingCluster(selection, mockObserver);

        verify(mockObserver).onNext(operationStatusCaptor.capture());
        OperationStatus status = operationStatusCaptor.getValue();
        assertTrue(status.getSuccess());
        assertEquals("Successfully selected cluster 'newSelectedCluster'.", status.getMessage());

        Properties updatedProps = loadTestProperties();
        assertEquals("newSelectedCluster", updatedProps.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
        assertEquals("existingHost", updatedProps.getProperty(YAPPY_BOOTSTRAP_CONSUL_HOST), "Other properties should be preserved");
        assertEquals("1234", updatedProps.getProperty(YAPPY_BOOTSTRAP_CONSUL_PORT), "Other properties should be preserved");
    }

    @Test
    void selectExistingCluster_emptyClusterName_returnsError() {
        StreamObserver<OperationStatus> mockObserver = mockStreamObserver();
        ClusterSelection selection = ClusterSelection.newBuilder().setClusterName("").build();

        service.selectExistingCluster(selection, mockObserver);

        verify(mockObserver, never()).onNext(any());
        verify(mockObserver, never()).onCompleted();
        verify(mockObserver).onError(throwableCaptor.capture());

        Throwable t = throwableCaptor.getValue();
        assertTrue(t instanceof io.grpc.StatusRuntimeException);
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.getCode(), ((io.grpc.StatusRuntimeException) t).getStatus().getCode());
        assertTrue(((io.grpc.StatusRuntimeException) t).getStatus().getDescription().contains("Cluster name cannot be empty"));
    }

    @Test
    void selectExistingCluster_nullClusterName_returnsError() {
        StreamObserver<OperationStatus> mockObserver = mockStreamObserver();
        ClusterSelection selection = ClusterSelection.newBuilder().build();

        service.selectExistingCluster(selection, mockObserver);
        verify(mockObserver, never()).onNext(any());
        verify(mockObserver, never()).onCompleted();
        verify(mockObserver).onError(throwableCaptor.capture());

        Throwable t = throwableCaptor.getValue();
        assertTrue(t instanceof io.grpc.StatusRuntimeException);
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.getCode(), ((io.grpc.StatusRuntimeException) t).getStatus().getCode());
        assertTrue(((io.grpc.StatusRuntimeException) t).getStatus().getDescription().contains("Cluster name cannot be empty"));
    }

    @Test
    void createNewCluster_success_minimalDetails() throws IOException {
        System.setProperty("yappy.consul.configured", "true");
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "testhost");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "8500");
        // YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH is set by writeTestProperties default
        writeTestProperties(props);

        StreamObserver<ClusterCreationStatus> mockObserver = mockStreamObserver();
        NewClusterDetails request = NewClusterDetails.newBuilder().setClusterName("newClusterMin").build();

        when(mockConsulBusinessOpsService.storeClusterConfiguration(anyString(), pipelineClusterConfigCaptor.capture()))
                .thenReturn(Mono.just(true));
        when(mockConsulBusinessOpsService.getFullClusterKey(eq("newClusterMin")))
                .thenReturn(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/newClusterMin");


        service.createNewCluster(request, mockObserver);

        verify(mockObserver).onNext(clusterCreationStatusCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterCreationStatus status = clusterCreationStatusCaptor.getValue();
        assertTrue(status.getSuccess(), "Cluster creation should be successful. Message: " + status.getMessage());
        assertEquals("newClusterMin", status.getClusterName());
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/newClusterMin", status.getSeededConfigPath());
        assertTrue(status.getMessage().contains("created successfully and configuration stored in Consul"));

        PipelineClusterConfig storedConfig = pipelineClusterConfigCaptor.getValue();
        assertEquals("newClusterMin", storedConfig.clusterName());
        assertTrue(storedConfig.defaultPipelineName() == null || storedConfig.defaultPipelineName().isEmpty(), "Default pipeline should not be set");

        assertTrue(storedConfig.pipelineGraphConfig() == null ||
                        (storedConfig.pipelineGraphConfig().pipelines() != null && storedConfig.pipelineGraphConfig().pipelines().isEmpty()),
                "Pipelines map should be empty or graph config null/empty");
        assertTrue(storedConfig.pipelineModuleMap() == null ||
                        (storedConfig.pipelineModuleMap().availableModules() != null && storedConfig.pipelineModuleMap().availableModules().isEmpty()),
                "Modules map should be empty or module map null/empty");


        Properties updatedProps = loadTestProperties();
        assertEquals("newClusterMin", updatedProps.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
    }

    @Test
    void createNewCluster_success_withPipelineAndModules() throws IOException {
        System.setProperty("yappy.consul.configured", "true");
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "testhost");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "8500");
        // YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH is set by writeTestProperties default
        writeTestProperties(props);

        StreamObserver<ClusterCreationStatus> mockObserver = mockStreamObserver();
        NewClusterDetails request = NewClusterDetails.newBuilder()
                .setClusterName("newClusterFull")
                .setFirstPipelineName("pipeline1")
                .addInitialModulesForFirstPipeline(InitialModuleConfig.newBuilder().setImplementationId("moduleA").build())
                .addInitialModulesForFirstPipeline(InitialModuleConfig.newBuilder().setImplementationId("moduleB").build())
                .build();

        when(mockConsulBusinessOpsService.storeClusterConfiguration(anyString(), pipelineClusterConfigCaptor.capture()))
                .thenReturn(Mono.just(true));
        when(mockConsulBusinessOpsService.getFullClusterKey(eq("newClusterFull")))
                .thenReturn(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/newClusterFull");


        service.createNewCluster(request, mockObserver);

        verify(mockObserver).onNext(clusterCreationStatusCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());


        ClusterCreationStatus status = clusterCreationStatusCaptor.getValue();
        assertTrue(status.getSuccess(), "Cluster creation should be successful. Message: " + status.getMessage());
        assertEquals("newClusterFull", status.getClusterName());
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/newClusterFull", status.getSeededConfigPath());


        PipelineClusterConfig storedConfig = pipelineClusterConfigCaptor.getValue();
        assertEquals("newClusterFull", storedConfig.clusterName());
        assertEquals("pipeline1", storedConfig.defaultPipelineName());

        assertNotNull(storedConfig.pipelineGraphConfig(), "PipelineGraphConfig should not be null");
        assertNotNull(storedConfig.pipelineGraphConfig().pipelines(), "Pipelines map in GraphConfig should not be null");
        PipelineConfig pipeline1Config = storedConfig.pipelineGraphConfig().pipelines().get("pipeline1");
        assertNotNull(pipeline1Config, "Pipeline 'pipeline1' should exist in graph config");
        assertEquals("pipeline1", pipeline1Config.name()); // Using .name() from PipelineConfig record

        assertNotNull(storedConfig.pipelineModuleMap(), "PipelineModuleMap should not be null");
        assertNotNull(storedConfig.pipelineModuleMap().availableModules(), "AvailableModules map in ModuleMap should not be null");

        PipelineModuleConfiguration moduleAConfig = storedConfig.pipelineModuleMap().availableModules().get("moduleA");
        assertNotNull(moduleAConfig, "Module 'moduleA' should exist"); // This was failing
        assertEquals("moduleA", moduleAConfig.implementationId());
        assertEquals("Module_moduleA", moduleAConfig.implementationName());

        PipelineModuleConfiguration moduleBConfig = storedConfig.pipelineModuleMap().availableModules().get("moduleB");
        assertNotNull(moduleBConfig, "Module 'moduleB' should exist");
        assertEquals("moduleB", moduleBConfig.implementationId());
        assertEquals("Module_moduleB", moduleBConfig.implementationName());


        Properties updatedProps = loadTestProperties();
        assertEquals("newClusterFull", updatedProps.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
    }

    @Test
    void createNewCluster_emptyClusterName_returnsError() {
        StreamObserver<ClusterCreationStatus> mockObserver = mockStreamObserver();
        NewClusterDetails request = NewClusterDetails.newBuilder().setClusterName("").build();

        service.createNewCluster(request, mockObserver);

        verify(mockObserver, never()).onNext(any());
        verify(mockObserver, never()).onCompleted();
        verify(mockObserver).onError(throwableCaptor.capture());

        Throwable t = throwableCaptor.getValue();
        assertTrue(t instanceof io.grpc.StatusRuntimeException);
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.getCode(), ((io.grpc.StatusRuntimeException) t).getStatus().getCode());
        assertTrue(((io.grpc.StatusRuntimeException) t).getStatus().getDescription().contains("Cluster name cannot be empty"));
    }

    @Test
    void createNewCluster_consulStoreThrowsException_returnsFailureStatus() throws IOException {
        System.setProperty("yappy.consul.configured", "true");
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "testhost");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "8500");
        // YAPPY_BOOTSTRAP_CONSUL_CONFIG_BASE_PATH is set by writeTestProperties default
        writeTestProperties(props);

        StreamObserver<ClusterCreationStatus> mockObserver = mockStreamObserver();
        NewClusterDetails request = NewClusterDetails.newBuilder().setClusterName("failCluster").build();

        when(mockConsulBusinessOpsService.storeClusterConfiguration(anyString(), any(PipelineClusterConfig.class)))
                .thenReturn(Mono.error(new RuntimeException("Consul save failed!")));
        // Mock getFullClusterKey because it's called in the catch block to build the response
        when(mockConsulBusinessOpsService.getFullClusterKey(eq("failCluster")))
                .thenReturn(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/failCluster");


        service.createNewCluster(request, mockObserver);

        verify(mockObserver).onNext(clusterCreationStatusCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterCreationStatus status = clusterCreationStatusCaptor.getValue();
        assertFalse(status.getSuccess());
        assertEquals("failCluster", status.getClusterName());
        assertTrue(status.getMessage().contains("Consul save failed!"));
        assertEquals(DEFAULT_TEST_CONSUL_CONFIG_BASE_PATH + "/failCluster", status.getSeededConfigPath());


        Properties updatedProps = loadTestProperties();
        assertNull(updatedProps.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME)); // Should not be selected on failure
    }
}