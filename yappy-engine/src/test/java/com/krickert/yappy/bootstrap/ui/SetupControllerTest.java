package com.krickert.yappy.bootstrap.ui;

import com.krickert.yappy.bootstrap.api.*;
import com.google.protobuf.Empty;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.views.ModelAndView;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetupControllerTest {

    @Mock
    private BootstrapConfigServiceGrpc.BootstrapConfigServiceBlockingStub mockBootstrapConfigClient;

    private SetupController controller;

    @Captor
    private ArgumentCaptor<ConsulConfigDetails> consulConfigDetailsCaptor;
    @Captor
    private ArgumentCaptor<Empty> emptyCaptor;
    @Captor
    private ArgumentCaptor<ClusterSelection> clusterSelectionCaptor;
    @Captor
    private ArgumentCaptor<NewClusterDetails> newClusterDetailsCaptor;

    @BeforeEach
    void setUp() {
        controller = new SetupController(mockBootstrapConfigClient);
    }

    // --- Test GET /setup/consul ---

    @Test
    void getConsulConfig_success() {
        ConsulConfigDetails mockDetails = ConsulConfigDetails.newBuilder()
                .setHost("localhost")
                .setPort(8500)
                .setAclToken("test-token")
                .setSelectedYappyClusterName("clusterA")
                .build();
        when(mockBootstrapConfigClient.getConsulConfiguration(any(Empty.class))).thenReturn(mockDetails);

        // Corrected line:
        ModelAndView<?> modelAndView = controller.consulConfig(Optional.empty(), Optional.empty());

        assertNotNull(modelAndView);
        assertEquals("setup/consul-config", modelAndView.getView().orElse(null));
        assertTrue(modelAndView.getModel().isPresent());
        // Since the model is a Map<String, Object>, you might need to cast if you use specific map methods
        @SuppressWarnings("unchecked") // Suppress if you are sure about the model type
        Map<String, Object> model = (Map<String, Object>) modelAndView.getModel().get();
        assertEquals(mockDetails, model.get("consulConfig"));
        assertNull(model.get("errorMessage")); // No error expected

        verify(mockBootstrapConfigClient).getConsulConfiguration(any(Empty.class));
    }

    @Test
    void getConsulConfig_grpcClientThrowsException() {
        when(mockBootstrapConfigClient.getConsulConfiguration(any(Empty.class))).thenThrow(new RuntimeException("gRPC unavailable"));

        // Corrected line:
        ModelAndView<?> modelAndView = controller.consulConfig(Optional.empty(), Optional.empty());

        assertNotNull(modelAndView);
        assertEquals("setup/consul-config", modelAndView.getView().orElse(null));
        assertTrue(modelAndView.getModel().isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) modelAndView.getModel().get();
        assertNotNull(model.get("errorMessage"));
        assertTrue(((String)model.get("errorMessage")).contains("gRPC unavailable"));
        assertEquals(ConsulConfigDetails.newBuilder().setHost("Error fetching").build(), model.get("consulConfig"));
    }

    @Test
    void getConsulConfig_withMessageAndSuccessParams() {
        when(mockBootstrapConfigClient.getConsulConfiguration(any(Empty.class))).thenReturn(ConsulConfigDetails.newBuilder().build());

        // Corrected line:
        ModelAndView<?> modelAndView = controller.consulConfig(Optional.of("Test Message"), Optional.of(true));
        assertTrue(modelAndView.getModel().isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) modelAndView.getModel().get();
        assertEquals("Test Message", model.get("message"));
        assertEquals(true, model.get("success"));
    }


    // --- Test POST /setup/consul ---

    @Test
    void setConsulConfig_success_withAcl() {
        Map<String, String> formData = new HashMap<>();
        formData.put("host", "consul.test");
        formData.put("port", "8501");
        formData.put("aclToken", "secure-token");

        ConsulConnectionStatus mockStatus = ConsulConnectionStatus.newBuilder().setSuccess(true).setMessage("Configured!").build();
        when(mockBootstrapConfigClient.setConsulConfiguration(any(ConsulConfigDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.setConsulConfig(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Configured%21&success=true", response.getHeaders().get("Location"));

        verify(mockBootstrapConfigClient).setConsulConfiguration(consulConfigDetailsCaptor.capture());
        ConsulConfigDetails capturedDetails = consulConfigDetailsCaptor.getValue();
        assertEquals("consul.test", capturedDetails.getHost());
        assertEquals(8501, capturedDetails.getPort());
        assertEquals("secure-token", capturedDetails.getAclToken());
    }

    @Test
    void setConsulConfig_success_noAcl() {
        Map<String, String> formData = new HashMap<>();
        formData.put("host", "consul.noacl");
        formData.put("port", "8500");
        formData.put("aclToken", ""); // Empty ACL token

        ConsulConnectionStatus mockStatus = ConsulConnectionStatus.newBuilder().setSuccess(true).setMessage("Configured without ACL.").build();
        when(mockBootstrapConfigClient.setConsulConfiguration(any(ConsulConfigDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.setConsulConfig(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Configured+without+ACL.&success=true", response.getHeaders().get("Location"));

        verify(mockBootstrapConfigClient).setConsulConfiguration(consulConfigDetailsCaptor.capture());
        ConsulConfigDetails capturedDetails = consulConfigDetailsCaptor.getValue();
        assertEquals("consul.noacl", capturedDetails.getHost());
        assertEquals(8500, capturedDetails.getPort());
        assertFalse(capturedDetails.hasAclToken() || !capturedDetails.getAclToken().isEmpty());
    }

    @Test
    void setConsulConfig_grpcReturnsFailure() {
        Map<String, String> formData = new HashMap<>();
        formData.put("host", "consul.fail");
        formData.put("port", "8500");

        ConsulConnectionStatus mockStatus = ConsulConnectionStatus.newBuilder().setSuccess(false).setMessage("Connection failed.").build();
        when(mockBootstrapConfigClient.setConsulConfiguration(any(ConsulConfigDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.setConsulConfig(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/consul?message=Connection+failed.&success=false", response.getHeaders().get("Location"));
    }

    @Test
    void setConsulConfig_invalidPortFormat() {
        Map<String, String> formData = new HashMap<>();
        formData.put("host", "consul.badport");
        formData.put("port", "not-a-port");

        HttpResponse<?> response = controller.setConsulConfig(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/consul?message=Invalid+port+format.&success=false", response.getHeaders().get("Location"));
        verify(mockBootstrapConfigClient, never()).setConsulConfiguration(any());
    }

    @Test
    void setConsulConfig_grpcClientThrowsException() {
        Map<String, String> formData = new HashMap<>();
        formData.put("host", "consul.exception");
        formData.put("port", "8500");

        when(mockBootstrapConfigClient.setConsulConfiguration(any(ConsulConfigDetails.class))).thenThrow(new RuntimeException("gRPC unavailable"));

        HttpResponse<?> response = controller.setConsulConfig(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertTrue(response.getHeaders().get("Location").startsWith("/setup/consul?message=gRPC+call+failed"));
        assertTrue(response.getHeaders().get("Location").contains("&success=false"));
    }


    // --- Test GET /setup/cluster ---

    @Test
    void getClusterManagement_consulNotConfigured_showsConsulNotConfiguredMessage() {
        // Simulate Consul not configured (e.g. host is empty or port is 0)
        when(mockBootstrapConfigClient.getConsulConfiguration(any(Empty.class)))
                .thenReturn(ConsulConfigDetails.newBuilder().setHost("").setPort(0).build());

        // Corrected line:
        ModelAndView<?> modelAndView = controller.clusterManagement(Optional.empty(), Optional.empty());

        assertEquals("setup/cluster-management", modelAndView.getView().orElse(null));
        assertTrue(modelAndView.getModel().isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) modelAndView.getModel().get();
        assertTrue((Boolean) model.get("consulNotConfigured"));
        assertEquals("Consul is not configured. Please configure Consul connection first.", model.get("errorMessage"));
        verify(mockBootstrapConfigClient, never()).listAvailableClusters(any()); // Should not list clusters
    }

    @Test
    void getClusterManagement_consulConfigured_success() {
        ConsulConfigDetails consulDetails = ConsulConfigDetails.newBuilder()
                .setHost("localhost")
                .setPort(8500)
                .setSelectedYappyClusterName("clusterAlpha")
                .build();
        ClusterList clusterList = ClusterList.newBuilder()
                .addClusters(ClusterInfo.newBuilder().setClusterName("clusterAlpha").setStatus("OPERATIONAL").build())
                .addClusters(ClusterInfo.newBuilder().setClusterName("clusterBeta").setStatus("NEEDS_SETUP").build())
                .build();

        when(mockBootstrapConfigClient.getConsulConfiguration(any(Empty.class))).thenReturn(consulDetails);
        when(mockBootstrapConfigClient.listAvailableClusters(any(Empty.class))).thenReturn(clusterList);

        // Corrected line:
        ModelAndView<?> modelAndView = controller.clusterManagement(Optional.of("Test Message"), Optional.of(true));

        assertEquals("setup/cluster-management", modelAndView.getView().orElse(null));
        assertTrue(modelAndView.getModel().isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) modelAndView.getModel().get();

        assertTrue((Boolean) model.get("consulConfigured"));
        assertFalse(model.containsKey("consulNotConfigured"));
        assertEquals(clusterList.getClustersList(), model.get("clusters"));
        assertEquals("clusterAlpha", model.get("currentSelectedCluster"));
        assertEquals("Test Message", model.get("message"));
        assertEquals(true, model.get("success"));
    }

    @Test
    void getClusterManagement_consulConfigured_noSelectedCluster() {
        ConsulConfigDetails consulDetails = ConsulConfigDetails.newBuilder()
                .setHost("localhost")
                .setPort(8500)
                // No selected cluster name
                .build();
        ClusterList clusterList = ClusterList.newBuilder().build(); // Empty list

        when(mockBootstrapConfigClient.getConsulConfiguration(any(Empty.class))).thenReturn(consulDetails);
        when(mockBootstrapConfigClient.listAvailableClusters(any(Empty.class))).thenReturn(clusterList);

        // Corrected line:
        ModelAndView<?> modelAndView = controller.clusterManagement(Optional.empty(), Optional.empty());
        assertTrue(modelAndView.getModel().isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) modelAndView.getModel().get();

        assertTrue((Boolean) model.get("consulConfigured"));
        assertNull(model.get("currentSelectedCluster"));
        assertEquals(0, ((List<?>)model.get("clusters")).size());
    }


    @Test
    void getClusterManagement_listAvailableClusters_grpcException() {
        ConsulConfigDetails consulDetails = ConsulConfigDetails.newBuilder()
                .setHost("localhost")
                .setPort(8500)
                .build();
        when(mockBootstrapConfigClient.getConsulConfiguration(any(Empty.class))).thenReturn(consulDetails);
        when(mockBootstrapConfigClient.listAvailableClusters(any(Empty.class))).thenThrow(new RuntimeException("List clusters failed"));

        // Corrected line:
        ModelAndView<?> modelAndView = controller.clusterManagement(Optional.empty(), Optional.empty());

        assertEquals("setup/cluster-management", modelAndView.getView().orElse(null));
        assertTrue(modelAndView.getModel().isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) modelAndView.getModel().get();
        assertTrue(((String)model.get("errorMessage")).contains("List clusters failed"));
        assertEquals(Collections.emptyList(), model.get("clusters"));
    }


    // --- Test POST /setup/cluster/select ---

    @Test
    void selectCluster_success() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "myCluster");

        OperationStatus mockStatus = OperationStatus.newBuilder().setSuccess(true).setMessage("Selected!").build();
        when(mockBootstrapConfigClient.selectExistingCluster(any(ClusterSelection.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.selectCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Selected%21&success=true", response.getHeaders().get("Location"));

        verify(mockBootstrapConfigClient).selectExistingCluster(clusterSelectionCaptor.capture());
        assertEquals("myCluster", clusterSelectionCaptor.getValue().getClusterName());
    }

    @Test
    void selectCluster_grpcReturnsFailure() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "myClusterFail");

        OperationStatus mockStatus = OperationStatus.newBuilder().setSuccess(false).setMessage("Selection failed.").build();
        when(mockBootstrapConfigClient.selectExistingCluster(any(ClusterSelection.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.selectCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Selection+failed.&success=false", response.getHeaders().get("Location"));
    }

    @Test
    void selectCluster_emptyClusterName() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", ""); // Empty cluster name

        HttpResponse<?> response = controller.selectCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Cluster+name+cannot+be+empty.&success=false", response.getHeaders().get("Location"));
        verify(mockBootstrapConfigClient, never()).selectExistingCluster(any());
    }

    @Test
    void selectCluster_nullClusterNameInForm() {
        Map<String, String> formData = new HashMap<>();
        // formData.put("clusterName", null); // This would be equivalent to not providing the field

        HttpResponse<?> response = controller.selectCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Cluster+name+cannot+be+empty.&success=false", response.getHeaders().get("Location"));
        verify(mockBootstrapConfigClient, never()).selectExistingCluster(any());
    }


    @Test
    void selectCluster_grpcClientThrowsException() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "myClusterException");

        when(mockBootstrapConfigClient.selectExistingCluster(any(ClusterSelection.class))).thenThrow(new RuntimeException("gRPC unavailable for select"));

        HttpResponse<?> response = controller.selectCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertTrue(response.getHeaders().get("Location").startsWith("/setup/cluster?message=gRPC+call+failed"));
        assertTrue(response.getHeaders().get("Location").contains("&success=false"));
    }

    // --- Test POST /setup/cluster/create ---

    @Test
    void createCluster_success_minimalDetails() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "newMinCluster");

        ClusterCreationStatus mockStatus = ClusterCreationStatus.newBuilder().setSuccess(true).setMessage("Created Min!").setClusterName("newMinCluster").build();
        when(mockBootstrapConfigClient.createNewCluster(any(NewClusterDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Created+Min%21&success=true", response.getHeaders().get("Location"));

        verify(mockBootstrapConfigClient).createNewCluster(newClusterDetailsCaptor.capture());
        NewClusterDetails capturedDetails = newClusterDetailsCaptor.getValue();
        assertEquals("newMinCluster", capturedDetails.getClusterName());
        assertFalse(capturedDetails.hasFirstPipelineName() || !capturedDetails.getFirstPipelineName().isEmpty());
        assertTrue(capturedDetails.getInitialModulesForFirstPipelineList().isEmpty());
    }

    @Test
    void createCluster_success_withPipelineName() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "newPipeCluster");
        formData.put("firstPipelineName", "myFirstPipe");

        ClusterCreationStatus mockStatus = ClusterCreationStatus.newBuilder().setSuccess(true).setMessage("Created Pipe!").setClusterName("newPipeCluster").build();
        when(mockBootstrapConfigClient.createNewCluster(any(NewClusterDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        verify(mockBootstrapConfigClient).createNewCluster(newClusterDetailsCaptor.capture());
        NewClusterDetails capturedDetails = newClusterDetailsCaptor.getValue();
        assertEquals("newPipeCluster", capturedDetails.getClusterName());
        assertEquals("myFirstPipe", capturedDetails.getFirstPipelineName());
        assertTrue(capturedDetails.getInitialModulesForFirstPipelineList().isEmpty());
    }

    @Test
    void createCluster_success_withPipelineAndModules() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "newFullCluster");
        formData.put("firstPipelineName", "fullPipe");
        formData.put("initialModules", "module1, module2 , module3"); // Test with spaces

        ClusterCreationStatus mockStatus = ClusterCreationStatus.newBuilder().setSuccess(true).setMessage("Created Full!").setClusterName("newFullCluster").build();
        when(mockBootstrapConfigClient.createNewCluster(any(NewClusterDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        verify(mockBootstrapConfigClient).createNewCluster(newClusterDetailsCaptor.capture());
        NewClusterDetails capturedDetails = newClusterDetailsCaptor.getValue();
        assertEquals("newFullCluster", capturedDetails.getClusterName());
        assertEquals("fullPipe", capturedDetails.getFirstPipelineName());
        assertEquals(3, capturedDetails.getInitialModulesForFirstPipelineCount());
        assertEquals("module1", capturedDetails.getInitialModulesForFirstPipeline(0).getImplementationId());
        assertEquals("module2", capturedDetails.getInitialModulesForFirstPipeline(1).getImplementationId());
        assertEquals("module3", capturedDetails.getInitialModulesForFirstPipeline(2).getImplementationId());
    }

    @Test
    void createCluster_success_withEmptyModulesString() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "clusterWithEmptyModules");
        formData.put("firstPipelineName", "pipeForEmptyModules");
        formData.put("initialModules", ""); // Empty string

        ClusterCreationStatus mockStatus = ClusterCreationStatus.newBuilder().setSuccess(true).setMessage("Created with empty modules!").setClusterName("clusterWithEmptyModules").build();
        when(mockBootstrapConfigClient.createNewCluster(any(NewClusterDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        verify(mockBootstrapConfigClient).createNewCluster(newClusterDetailsCaptor.capture());
        NewClusterDetails capturedDetails = newClusterDetailsCaptor.getValue();
        assertEquals("clusterWithEmptyModules", capturedDetails.getClusterName());
        assertEquals("pipeForEmptyModules", capturedDetails.getFirstPipelineName());
        assertTrue(capturedDetails.getInitialModulesForFirstPipelineList().isEmpty(), "Initial modules list should be empty for empty string input");
    }


    @Test
    void createCluster_grpcReturnsFailure() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "clusterFailCreate");

        ClusterCreationStatus mockStatus = ClusterCreationStatus.newBuilder().setSuccess(false).setMessage("Creation failed internally.").build();
        when(mockBootstrapConfigClient.createNewCluster(any(NewClusterDetails.class))).thenReturn(mockStatus);

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Creation+failed+internally.&success=false", response.getHeaders().get("Location"));
    }

    @Test
    void createCluster_emptyClusterName() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", ""); // Empty cluster name

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Cluster+name+cannot+be+empty+for+creation.&success=false", response.getHeaders().get("Location"));
        verify(mockBootstrapConfigClient, never()).createNewCluster(any());
    }

    @Test
    void createCluster_nullClusterNameInForm() {
        Map<String, String> formData = new HashMap<>();
        // formData.put("clusterName", null); // Equivalent to not providing

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertEquals("/setup/cluster?message=Cluster+name+cannot+be+empty+for+creation.&success=false", response.getHeaders().get("Location"));
        verify(mockBootstrapConfigClient, never()).createNewCluster(any());
    }

    @Test
    void createCluster_grpcClientThrowsException() {
        Map<String, String> formData = new HashMap<>();
        formData.put("clusterName", "clusterExceptionCreate");

        when(mockBootstrapConfigClient.createNewCluster(any(NewClusterDetails.class))).thenThrow(new RuntimeException("gRPC unavailable for create"));

        HttpResponse<?> response = controller.createCluster(formData);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertTrue(response.getHeaders().get("Location").startsWith("/setup/cluster?message=gRPC+call+failed"));
        assertTrue(response.getHeaders().get("Location").contains("&success=false"));
    }
}
