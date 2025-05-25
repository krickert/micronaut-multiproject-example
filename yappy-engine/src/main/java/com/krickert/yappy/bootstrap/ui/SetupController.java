package com.krickert.yappy.bootstrap.ui;

import com.krickert.yappy.bootstrap.api.BootstrapConfigServiceGrpc;
import com.krickert.yappy.bootstrap.api.ClusterList;
import com.krickert.yappy.bootstrap.api.ClusterSelection;
import com.krickert.yappy.bootstrap.api.ConsulConfigDetails;
import com.krickert.yappy.bootstrap.api.ConsulConnectionStatus;
import com.krickert.yappy.bootstrap.api.InitialModuleConfig;
import com.krickert.yappy.bootstrap.api.NewClusterDetails;
import com.krickert.yappy.bootstrap.api.OperationStatus;
import com.krickert.yappy.bootstrap.api.ClusterCreationStatus;

import com.google.protobuf.Empty;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.views.ModelAndView;
import io.micronaut.views.ViewsRenderer; // For rendering templates

import jakarta.inject.Inject; // Ensure using jakarta.inject
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller("/setup")
public class SetupController {

    private static final Logger LOG = LoggerFactory.getLogger(SetupController.class);

    private final BootstrapConfigServiceGrpc.BootstrapConfigServiceBlockingStub bootstrapConfigClient;
    // private final ViewsRenderer viewsRenderer; // Optional: if direct rendering needed

    @Inject
    public SetupController(final BootstrapConfigServiceGrpc.BootstrapConfigServiceBlockingStub bootstrapConfigClient) {
        this.bootstrapConfigClient = bootstrapConfigClient;
        // this.viewsRenderer = viewsRenderer;
        LOG.info("SetupController instantiated with gRPC client.");
    }

    @Get("/consul")
    @Produces(MediaType.TEXT_HTML)
    public ModelAndView consulConfig(Optional<String> message, Optional<Boolean> success) {
        LOG.info("GET /setup/consul - displaying Consul configuration page.");
        Map<String, Object> model = new HashMap<>();
        try {
            ConsulConfigDetails currentConfig = bootstrapConfigClient.getConsulConfiguration(Empty.newBuilder().build());
            model.put("consulConfig", currentConfig);
            LOG.debug("Current Consul config: {}", currentConfig);
        } catch (Exception e) {
            LOG.error("Error getting Consul configuration: {}", e.getMessage(), e);
            model.put("consulConfig", ConsulConfigDetails.newBuilder().setHost("Error fetching").build());
            model.put("errorMessage", "Error fetching current Consul configuration: " + e.getMessage());
        }
        message.ifPresent(m -> model.put("message", m));
        success.ifPresent(s -> model.put("success", s));
        return new ModelAndView<>("setup/consul-config", model);
    }

    @Post(value = "/consul", consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> setConsulConfig(@Body Map<String, String> form) {
        String host = form.get("host");
        String portStr = form.get("port");
        String aclToken = form.get("aclToken");
        LOG.info("POST /setup/consul - attempting to set Consul config with host={}, port={}, aclToken provided: {}", host, portStr, (aclToken != null && !aclToken.isEmpty()));

        ConsulConfigDetails.Builder detailsBuilder = ConsulConfigDetails.newBuilder();
        detailsBuilder.setHost(host);
        try {
            detailsBuilder.setPort(Integer.parseInt(portStr));
        } catch (NumberFormatException e) {
            LOG.warn("Invalid port format: {}", portStr, e);
            return HttpResponse.seeOther(UriBuilder.of("/setup/consul").queryParam("message", "Invalid port format.").queryParam("success", false).build());
        }
        if (aclToken != null && !aclToken.isEmpty()) {
            detailsBuilder.setAclToken(aclToken);
        }

        try {
            ConsulConnectionStatus status = bootstrapConfigClient.setConsulConfiguration(detailsBuilder.build());
            LOG.info("Set Consul configuration status: success={}, message={}", status.getSuccess(), status.getMessage());
            if (status.getSuccess()) {
                return HttpResponse.seeOther(UriBuilder.of("/setup/cluster").queryParam("message", status.getMessage()).queryParam("success", true).build());
            } else {
                return HttpResponse.seeOther(UriBuilder.of("/setup/consul").queryParam("message", status.getMessage()).queryParam("success", false).build());
            }
        } catch (Exception e) {
            LOG.error("Error setting Consul configuration via gRPC: {}", e.getMessage(), e);
            return HttpResponse.seeOther(UriBuilder.of("/setup/consul").queryParam("message", "gRPC call failed: " + e.getMessage()).queryParam("success", false).build());
        }
    }

    @Get("/cluster")
    @Produces(MediaType.TEXT_HTML)
    public ModelAndView clusterManagement(Optional<String> message, Optional<Boolean> success) {
        LOG.info("GET /setup/cluster - displaying cluster management page.");
        Map<String, Object> model = new HashMap<>();
        message.ifPresent(m -> model.put("message", m));
        success.ifPresent(s -> model.put("success", s));

        try {
            ConsulConfigDetails consulConfig = bootstrapConfigClient.getConsulConfiguration(Empty.newBuilder().build());
            if (consulConfig == null || consulConfig.getHost().isEmpty() || consulConfig.getPort() == 0) {
                LOG.info("Consul not configured, redirecting to /setup/consul");
                 // This is a server-side redirect, client won't see it as a separate step.
                 // For user feedback, it's better to show the cluster page with a message.
                model.put("consulNotConfigured", true);
                model.put("errorMessage", "Consul is not configured. Please configure Consul connection first.");
                return new ModelAndView<>("setup/cluster-management", model);
            }
            model.put("consulConfigured", true);

            ClusterList clusterList = bootstrapConfigClient.listAvailableClusters(Empty.newBuilder().build());
            model.put("clusters", clusterList.getClustersList());
            LOG.debug("Available clusters: {}", clusterList.getClustersList());

            if (consulConfig.hasSelectedYappyClusterName() && !consulConfig.getSelectedYappyClusterName().isEmpty()) {
                model.put("currentSelectedCluster", consulConfig.getSelectedYappyClusterName());
                LOG.debug("Currently selected cluster from ConsulConfigDetails: {}", consulConfig.getSelectedYappyClusterName());
            } else {
                model.put("currentSelectedCluster", null); // Or a specific message like "None selected"
                LOG.debug("No cluster currently selected in ConsulConfigDetails.");
            }

        } catch (Exception e) {
            LOG.error("Error in clusterManagement: {}", e.getMessage(), e);
            model.put("errorMessage", "Error fetching cluster data: " + e.getMessage());
            model.put("clusters", Collections.emptyList());
        }
        return new ModelAndView<>("setup/cluster-management", model);
    }

    @Post(value = "/cluster/select", consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> selectCluster(@Body Map<String, String> form) {
        String clusterName = form.get("clusterName");
        LOG.info("POST /setup/cluster/select - attempting to select cluster: {}", clusterName);

        if (clusterName == null || clusterName.trim().isEmpty()) {
            return HttpResponse.seeOther(UriBuilder.of("/setup/cluster").queryParam("message", "Cluster name cannot be empty.").queryParam("success", false).build());
        }
        try {
            OperationStatus status = bootstrapConfigClient.selectExistingCluster(
                ClusterSelection.newBuilder().setClusterName(clusterName).build()
            );
            LOG.info("Select cluster status: success={}, message={}", status.getSuccess(), status.getMessage());
            return HttpResponse.seeOther(UriBuilder.of("/setup/cluster").queryParam("message", status.getMessage()).queryParam("success", status.getSuccess()).build());
        } catch (Exception e) {
            LOG.error("Error selecting cluster via gRPC: {}", e.getMessage(), e);
            return HttpResponse.seeOther(UriBuilder.of("/setup/cluster").queryParam("message", "gRPC call failed: " + e.getMessage()).queryParam("success", false).build());
        }
    }

    @Post(value = "/cluster/create", consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> createCluster(@Body Map<String, String> form) {
        String clusterName = form.get("clusterName");
        String firstPipelineName = form.get("firstPipelineName"); // Optional
        String initialModulesStr = form.get("initialModules"); // Optional, comma-separated
        LOG.info("POST /setup/cluster/create - attempting to create cluster: {}, pipeline: {}, modules: '{}'", clusterName, firstPipelineName, initialModulesStr);

        if (clusterName == null || clusterName.trim().isEmpty()) {
            return HttpResponse.seeOther(UriBuilder.of("/setup/cluster").queryParam("message", "Cluster name cannot be empty for creation.").queryParam("success", false).build());
        }

        NewClusterDetails.Builder detailsBuilder = NewClusterDetails.newBuilder().setClusterName(clusterName);
        if (firstPipelineName != null && !firstPipelineName.trim().isEmpty()) {
            detailsBuilder.setFirstPipelineName(firstPipelineName);
        }

        if (initialModulesStr != null && !initialModulesStr.trim().isEmpty()) {
            List<InitialModuleConfig> moduleConfigs = Arrays.stream(initialModulesStr.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> InitialModuleConfig.newBuilder().setImplementationId(id).build())
                .collect(Collectors.toList());
            detailsBuilder.addAllInitialModulesForFirstPipeline(moduleConfigs);
            LOG.debug("Parsed initial modules: {}", moduleConfigs);
        }

        try {
            ClusterCreationStatus status = bootstrapConfigClient.createNewCluster(detailsBuilder.build());
            LOG.info("Create cluster status: success={}, message={}, path={}", status.getSuccess(), status.getMessage(), status.getSeededConfigPath());
            return HttpResponse.seeOther(UriBuilder.of("/setup/cluster").queryParam("message", status.getMessage()).queryParam("success", status.getSuccess()).build());
        } catch (Exception e) {
            LOG.error("Error creating cluster via gRPC: {}", e.getMessage(), e);
            return HttpResponse.seeOther(UriBuilder.of("/setup/cluster").queryParam("message", "gRPC call failed: " + e.getMessage()).queryParam("success", false).build());
        }
    }
}
