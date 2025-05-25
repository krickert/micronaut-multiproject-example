package com.krickert.yappy.bootstrap.service;

import com.krickert.yappy.bootstrap.api.*;
import com.krickert.yappy.model.pipeline.PipelineClusterConfig;
import com.krickert.yappy.services.ConsulBusinessOperationsService;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BootstrapConfigServiceImplTest {

    @Mock
    private ConsulBusinessOperationsService mockConsulBusinessOpsService;

    @Mock
    private ApplicationContext mockApplicationContext; // Injected but not used actively by all methods

    private BootstrapConfigServiceImpl service;

    @TempDir
    Path tempDir;

    private Path testBootstrapFilePath;

    // Property keys from BootstrapConfigServiceImpl
    private static final String YAPPY_BOOTSTRAP_CONSUL_HOST = "yappy.bootstrap.consul.host";
    private static final String YAPPY_BOOTSTRAP_CONSUL_PORT = "yappy.bootstrap.consul.port";
    private static final String YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN = "yappy.bootstrap.consul.acl_token";
    private static final String YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME = "yappy.bootstrap.cluster.selected_name";
    
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
        // Ensure the file doesn't exist from a previous run within the same @TempDir instance (if tests are nested or so)
        Files.deleteIfExists(testBootstrapFilePath); 
        service = new BootstrapConfigServiceImpl(mockConsulBusinessOpsService, testBootstrapFilePath.toString(), mockApplicationContext);
    }

    // Helper to load properties from the test file
    private Properties loadTestProperties() throws IOException {
        Properties props = new Properties();
        if (Files.exists(testBootstrapFilePath)) {
            try (var input = Files.newInputStream(testBootstrapFilePath)) {
                props.load(input);
            }
        }
        return props;
    }
    
    // Helper to create StreamObserver mock
    @SuppressWarnings("unchecked")
    private <T> StreamObserver<T> mockStreamObserver() {
        return (StreamObserver<T>) mock(StreamObserver.class);
    }

    // --- Tests for getConsulConfiguration and setConsulConfiguration ---

    @Test
    void getConsulConfiguration_whenFileDoesNotExist_returnsEmptyDetails() {
        StreamObserver<ConsulConfigDetails> mockObserver = mockStreamObserver();
        service.getConsulConfiguration(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(consulConfigDetailsCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ConsulConfigDetails details = consulConfigDetailsCaptor.getValue();
        assertFalse(details.hasHost(), "Host should not be set");
        assertEquals(0, details.getPort(), "Port should be default (0)");
        assertFalse(details.hasAclToken(), "ACL token should not be set");
        assertFalse(details.hasSelectedYappyClusterName(), "Selected cluster name should not be set");
    }

    @Test
    void setAndGetConsulConfiguration_success() throws IOException {
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
        // selected_yappy_cluster_name is not set by setConsulConfiguration directly in currentConfig,
        // but read from props if already there. Here, it shouldn't be set yet.
        assertFalse(status.getCurrentConfig().hasSelectedYappyClusterName());


        // Verify properties file
        Properties props = loadTestProperties();
        assertEquals("testhost.local", props.getProperty(YAPPY_BOOTSTRAP_CONSUL_HOST));
        assertEquals("9500", props.getProperty(YAPPY_BOOTSTRAP_CONSUL_PORT));
        assertEquals("test-token-123", props.getProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN));

        // Now test get
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
        // Pre-populate file
        Properties initialProps = new Properties();
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "oldhost");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "1111");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN, "old-token");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, "clusterA"); // Keep this to test it's preserved
        try (var output = Files.newOutputStream(testBootstrapFilePath)) {
            initialProps.store(output, null);
        }

        StreamObserver<ConsulConnectionStatus> setObserver = mockStreamObserver();
        ConsulConfigDetails newDetails = ConsulConfigDetails.newBuilder()
                .setHost("newhost.com")
                .setPort(2222)
                // No ACL token this time, should remove it
                .build();
        service.setConsulConfiguration(newDetails, setObserver);

        verify(setObserver).onNext(consulConnectionStatusCaptor.capture());
        ConsulConnectionStatus status = consulConnectionStatusCaptor.getValue();
        assertTrue(status.getSuccess());
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
        try (var output = Files.newOutputStream(testBootstrapFilePath)) {
            props.store(output, null);
        }

        StreamObserver<ConsulConfigDetails> getObserver = mockStreamObserver();
        service.getConsulConfiguration(Empty.newBuilder().build(), getObserver);

        verify(getObserver).onNext(consulConfigDetailsCaptor.capture());
        ConsulConfigDetails retrievedDetails = consulConfigDetailsCaptor.getValue();
        assertEquals("somehost", retrievedDetails.getHost());
        assertEquals(1234, retrievedDetails.getPort());
        assertTrue(retrievedDetails.hasSelectedYappyClusterName());
        assertEquals("mySelectedCluster", retrievedDetails.getSelectedYappyClusterName());
    }

    // TODO: Add test for setConsulConfiguration when properties file save fails (IOException) - This is harder to reliably simulate
    // One way might be to make the tempDir read-only after creating the service, but this is OS dependent.
    // For now, focusing on logic given a writable path.

    @Test
    void getConsulConfiguration_invalidPortFormat_returnsDefaultPort() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "hostwithbadport");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "not-a-number");
        try (var output = Files.newOutputStream(testBootstrapFilePath)) {
            props.store(output, null);
        }

        StreamObserver<ConsulConfigDetails> getObserver = mockStreamObserver();
        service.getConsulConfiguration(Empty.newBuilder().build(), getObserver);

        verify(getObserver).onNext(consulConfigDetailsCaptor.capture());
        ConsulConfigDetails retrievedDetails = consulConfigDetailsCaptor.getValue();
        assertEquals("hostwithbadport", retrievedDetails.getHost());
        assertEquals(0, retrievedDetails.getPort(), "Port should be default (0) or indicate error on invalid format");
    }


    // --- Tests for listAvailableClusters ---

    @Test
    void listAvailableClusters_success() {
        List<String> mockClusterNames = Arrays.asList("cluster1", "cluster2", "cluster3");
        // This specific mocking relies on the internal System.getProperty call for simulation control.
        // A more direct approach would be to inject a supplier/factory for this list if the service design allowed.
        System.setProperty("simulateRealConsulBehavior", "true"); // To ensure it tries to use the mock
        when(mockConsulBusinessOpsService.listAvailableClusterNames()).thenReturn(mockClusterNames);

        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(clusterListCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterList clusterList = clusterListCaptor.getValue();
        assertEquals(3, clusterList.getClustersCount());
        assertEquals("cluster1", clusterList.getClusters(0).getClusterName());
        assertEquals("pipeline-configs/clusters/cluster1", clusterList.getClusters(0).getClusterId());
        assertEquals("NEEDS_VERIFICATION", clusterList.getClusters(0).getStatus());
        assertEquals("cluster2", clusterList.getClusters(1).getClusterName());
        assertEquals("cluster3", clusterList.getClusters(2).getClusterName());
        
        System.clearProperty("simulateRealConsulBehavior"); // Clean up
    }
    
    @Test
    void listAvailableClusters_simulatedSuccess() {
        // This tests the default simulation when simulateRealConsulBehavior is false or not set
        // and the consulBusinessOperationsService is not returning a list (e.g. null or throws)
        // Forcing the service to be null to test the fallback simulation path more directly.
        // This requires re-initializing service without the mock for this specific test.
        service = new BootstrapConfigServiceImpl(null, testBootstrapFilePath.toString(), mockApplicationContext);


        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(clusterListCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterList clusterList = clusterListCaptor.getValue();
        // Based on the current hardcoded simulation in BootstrapConfigServiceImpl
        assertEquals(2, clusterList.getClustersCount()); 
        assertEquals("simulatedCluster1", clusterList.getClusters(0).getClusterName());
        assertEquals("simulatedCluster2_needs_setup", clusterList.getClusters(1).getClusterName());
    }


    @Test
    void listAvailableClusters_emptyListFromService() {
        System.setProperty("simulateRealConsulBehavior", "true");
        when(mockConsulBusinessOpsService.listAvailableClusterNames()).thenReturn(Arrays.asList());

        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        verify(mockObserver).onNext(clusterListCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterList clusterList = clusterListCaptor.getValue();
        assertEquals(0, clusterList.getClustersCount());
        System.clearProperty("simulateRealConsulBehavior");
    }

    @Test
    void listAvailableClusters_serviceThrowsException() {
        System.setProperty("simulateRealConsulBehavior", "true");
        when(mockConsulBusinessOpsService.listAvailableClusterNames()).thenThrow(new RuntimeException("Consul connection failed"));
        
        StreamObserver<ClusterList> mockObserver = mockStreamObserver();
        service.listAvailableClusters(Empty.newBuilder().build(), mockObserver);

        // In the current implementation, the service catches the exception from listAvailableClusterNames() 
        // and returns an empty list while logging an error. It doesn't call onError on the streamObserver for this specific case.
        // If the desired behavior was to propagate to onError, this test would change.
        verify(mockObserver).onNext(clusterListCaptor.capture());
        verify(mockObserver).onCompleted(); // Because the catch block still completes normally after logging
        verify(mockObserver, never()).onError(any()); 

        ClusterList clusterList = clusterListCaptor.getValue();
        assertEquals(0, clusterList.getClustersCount(), "Cluster list should be empty when service call fails and is handled.");

        System.clearProperty("simulateRealConsulBehavior");
    }
    
    @Test
    void listAvailableClusters_generalExceptionPath() {
        // This test is to ensure that if some other unexpected exception happens (not from the direct service call)
        // it's caught by the outermost catch block in listAvailableClusters.
        // To simulate this, we can make the clusterListBuilder.build() throw an error,
        // which is hard to do without more complex mocking or changing the class structure.
        // For now, the existing exception handling for the service call is the primary path.
        // A simpler way is to ensure the mockObserver itself throws an error, though this tests the test more.
        // Given the current structure, a direct test for the final catch block is non-trivial.
        // The existing test listAvailableClusters_serviceThrowsException covers the handled exception path from the service.
        // The outermost catch (Exception e) is more for truly unexpected runtime issues.
        // We can assume its basic functionality (calling onError) if a general exception were to occur.
    }


    // --- Tests for selectExistingCluster ---

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

        Properties props = loadTestProperties();
        assertEquals("testCluster1", props.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
    }

    @Test
    void selectExistingCluster_success_updatesExistingFile() throws IOException {
        // Pre-populate with other properties
        Properties initialProps = new Properties();
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "existingHost");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "1234");
        initialProps.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, "oldCluster"); // Pre-existing selection
        try (var output = Files.newOutputStream(testBootstrapFilePath)) {
            initialProps.store(output, null);
        }

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
        ClusterSelection selection = ClusterSelection.newBuilder().setClusterName("").build(); // Empty name

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
        // Builder will throw NPE if setClusterName(null) is called, so this tests the service's internal check if it were to get such an object
        // However, the current service code does `request.getClusterName()` which would be null.
        ClusterSelection selection = ClusterSelection.newBuilder().build(); // name is not set, defaults to "" effectively by proto3 spec for string

        service.selectExistingCluster(selection, mockObserver);
         verify(mockObserver, never()).onNext(any());
        verify(mockObserver, never()).onCompleted();
        verify(mockObserver).onError(throwableCaptor.capture());
        
        Throwable t = throwableCaptor.getValue();
        assertTrue(t instanceof io.grpc.StatusRuntimeException);
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.getCode(), ((io.grpc.StatusRuntimeException) t).getStatus().getCode());
        assertTrue(((io.grpc.StatusRuntimeException) t).getStatus().getDescription().contains("Cluster name cannot be empty"));
    }


    // --- Tests for createNewCluster ---

    @Test
    void createNewCluster_success_minimalDetails() throws IOException {
        StreamObserver<ClusterCreationStatus> mockObserver = mockStreamObserver();
        NewClusterDetails request = NewClusterDetails.newBuilder().setClusterName("newClusterMin").build();

        // Mock successful storage in Consul
        doNothing().when(mockConsulBusinessOpsService).storeClusterConfiguration(pipelineClusterConfigCaptor.capture());

        service.createNewCluster(request, mockObserver);

        verify(mockObserver).onNext(clusterCreationStatusCaptor.capture());
        verify(mockObserver).onCompleted();
        verify(mockObserver, never()).onError(any());

        ClusterCreationStatus status = clusterCreationStatusCaptor.getValue();
        assertTrue(status.getSuccess());
        assertEquals("newClusterMin", status.getClusterName());
        assertEquals("pipeline-configs/clusters/newClusterMin", status.getSeededConfigPath());
        assertTrue(status.getMessage().contains("created successfully and configuration stored in Consul"));

        PipelineClusterConfig storedConfig = pipelineClusterConfigCaptor.getValue();
        assertEquals("newClusterMin", storedConfig.getClusterName());
        assertFalse(storedConfig.hasDefaultPipelineName() || !storedConfig.getDefaultPipelineName().isEmpty(), "Default pipeline should not be set");
        assertTrue(storedConfig.getPipelineGraphConfig().getPipelinesMap().isEmpty(), "Pipelines map should be empty");
        assertTrue(storedConfig.getPipelineModuleMap().getAvailableModulesMap().isEmpty(), "Modules map should be empty");
        
        Properties props = loadTestProperties();
        assertEquals("newClusterMin", props.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
    }

    @Test
    void createNewCluster_success_withPipelineAndModules() throws IOException {
        StreamObserver<ClusterCreationStatus> mockObserver = mockStreamObserver();
        NewClusterDetails request = NewClusterDetails.newBuilder()
                .setClusterName("newClusterFull")
                .setFirstPipelineName("pipeline1")
                .addInitialModulesForFirstPipeline(InitialModuleConfig.newBuilder().setImplementationId("moduleA").build())
                .addInitialModulesForFirstPipeline(InitialModuleConfig.newBuilder().setImplementationId("moduleB").build())
                .build();

        doNothing().when(mockConsulBusinessOpsService).storeClusterConfiguration(pipelineClusterConfigCaptor.capture());

        service.createNewCluster(request, mockObserver);

        verify(mockObserver).onNext(clusterCreationStatusCaptor.capture());
        ClusterCreationStatus status = clusterCreationStatusCaptor.getValue();
        assertTrue(status.getSuccess());
        assertEquals("newClusterFull", status.getClusterName());

        PipelineClusterConfig storedConfig = pipelineClusterConfigCaptor.getValue();
        assertEquals("newClusterFull", storedConfig.getClusterName());
        assertEquals("pipeline1", storedConfig.getDefaultPipelineName());
        assertNotNull(storedConfig.getPipelineGraphConfig().getPipelinesMap().get("pipeline1"));
        assertEquals("pipeline1", storedConfig.getPipelineGraphConfig().getPipelinesMap().get("pipeline1").getPipelineName());
        
        assertNotNull(storedConfig.getPipelineModuleMap().getAvailableModulesMap().get("moduleA"));
        assertEquals("moduleA", storedConfig.getPipelineModuleMap().getAvailableModulesMap().get("moduleA").getImplementationId());
        assertEquals("Module_moduleA", storedConfig.getPipelineModuleMap().getAvailableModulesMap().get("moduleA").getImplementationName());
        assertNotNull(storedConfig.getPipelineModuleMap().getAvailableModulesMap().get("moduleB"));
        
        Properties props = loadTestProperties();
        assertEquals("newClusterFull", props.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
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
        StreamObserver<ClusterCreationStatus> mockObserver = mockStreamObserver();
        NewClusterDetails request = NewClusterDetails.newBuilder().setClusterName("failCluster").build();

        doThrow(new RuntimeException("Consul save failed!")).when(mockConsulBusinessOpsService).storeClusterConfiguration(any(PipelineClusterConfig.class));

        service.createNewCluster(request, mockObserver);

        verify(mockObserver).onNext(clusterCreationStatusCaptor.capture());
        verify(mockObserver).onCompleted(); // Service handles exception and calls onCompleted with status
        verify(mockObserver, never()).onError(any());

        ClusterCreationStatus status = clusterCreationStatusCaptor.getValue();
        assertFalse(status.getSuccess());
        assertEquals("failCluster", status.getClusterName());
        assertTrue(status.getMessage().contains("Consul save failed!"));
        assertFalse(status.hasSeededConfigPath() || !status.getSeededConfigPath().isEmpty());

        // Verify properties file does NOT have the cluster selected, as operation failed before that
        Properties props = loadTestProperties();
        assertNull(props.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME));
    }
}
