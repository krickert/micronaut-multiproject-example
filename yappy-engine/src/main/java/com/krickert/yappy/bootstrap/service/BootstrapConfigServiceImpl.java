package com.krickert.yappy.bootstrap.service;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.yappy.bootstrap.api.*;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value; // Micronaut @Value
import io.micronaut.grpc.annotation.GrpcService; // Micronaut GrpcService
import io.micronaut.context.ApplicationContext; // For ApplicationContext injection

// Import for Google's Empty proto
import com.google.protobuf.Empty;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Yappy pipeline models - assuming these exist in this package structure
// Potentially for JSON logging if chosen later:
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.fasterxml.jackson.databind.SerializationFeature;

@Singleton
@GrpcService // Using Micronaut's GrpcService
public class BootstrapConfigServiceImpl extends BootstrapConfigServiceGrpc.BootstrapConfigServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapConfigServiceImpl.class);
    private static final String YAPPY_BOOTSTRAP_CONSUL_HOST = "yappy.bootstrap.consul.host";
    private static final String YAPPY_BOOTSTRAP_CONSUL_PORT = "yappy.bootstrap.consul.port";
    private static final String YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN = "yappy.bootstrap.consul.acl_token";
    private static final String YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME = "yappy.bootstrap.cluster.selected_name";

    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final String bootstrapFilePath;
    private final Path resolvedBootstrapPath;


    @Inject
    public BootstrapConfigServiceImpl(
            ConsulBusinessOperationsService consulBusinessOperationsService,
            @Value("${yappy.engine.bootstrap-file.path:~/.yappy/engine-bootstrap.properties}") String bootstrapFilePath) {
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.bootstrapFilePath = bootstrapFilePath;
        this.resolvedBootstrapPath = Paths.get(this.bootstrapFilePath.replace("~", System.getProperty("user.home")));
        LOG.info("BootstrapConfigServiceImpl instantiated. ConsulBusinessOperationsService is {}", (this.consulBusinessOperationsService == null ? "null" : "available"));
        LOG.info("Bootstrap file path configured to: {}, resolved to: {}", this.bootstrapFilePath, this.resolvedBootstrapPath);
    }

    private Properties loadProperties(Path path) throws IOException {
        Properties props = new Properties();
        if (Files.exists(path)) {
            try (InputStream input = new FileInputStream(path.toFile())) {
                props.load(input);
            }
        }
        return props;
    }

    private void saveProperties(Path path, Properties props) throws IOException {
        Path parentDir = path.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
            LOG.info("Created directory: {}", parentDir);
        }
        try (OutputStream output = new FileOutputStream(path.toFile())) {
            props.store(output, "Yappy Engine Bootstrap Configuration");
        }
    }

    @Override
    public void setConsulConfiguration(ConsulConfigDetails request, StreamObserver<ConsulConnectionStatus> responseObserver) {
        LOG.info("setConsulConfiguration called with host: {}, port: {}, ACL token present: {}, resolved path: {}",
                request.getHost(), request.getPort(), request.hasAclToken(), resolvedBootstrapPath);
        try {
            Properties props = loadProperties(resolvedBootstrapPath);

            props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, request.getHost());
            props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, String.valueOf(request.getPort()));
            if (request.hasAclToken() && !request.getAclToken().isEmpty()) {
                props.setProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN, request.getAclToken());
            } else {
                props.remove(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN);
            }

            saveProperties(resolvedBootstrapPath, props);

            // Set system properties first, so ConsulBusinessOperationsService can use them if it's re-initialized
            // or if its internal Consul client relies on these properties at runtime.
            System.setProperty("consul.client.host", request.getHost());
            System.setProperty("consul.client.port", String.valueOf(request.getPort()));
            System.setProperty("consul.client.registration.enabled", "true");
            System.setProperty("consul.client.config.enabled", "true");
            System.setProperty("micronaut.config-client.enabled", "true");
            System.setProperty("yappy.consul.configured", "true");
            if (request.hasAclToken() && !request.getAclToken().isEmpty()) {
                System.setProperty("consul.client.acl-token", request.getAclToken());
            } else {
                System.clearProperty("consul.client.acl-token");
            }
            LOG.info("System properties updated for Consul client. Host: {}, Port: {}", request.getHost(), request.getPort());


            ConsulConfigDetails.Builder currentConfigBuilder = request.toBuilder();
            String selectedCluster = props.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME);
            if (selectedCluster != null && !selectedCluster.isEmpty()) {
                currentConfigBuilder.setSelectedYappyClusterName(selectedCluster);
            }

            if (this.consulBusinessOperationsService != null) {
                LOG.info("Attempting to check Consul availability after saving properties and setting system properties...");
                // Use reactive approach
                this.consulBusinessOperationsService.isConsulAvailable()
                    .subscribe(
                        consulPingSuccess -> {
                            handleConsulConfigurationResponse(consulPingSuccess, currentConfigBuilder, responseObserver);
                        },
                        error -> {
                            LOG.warn("Error checking Consul availability: {}", error.getMessage(), error);
                            handleConsulConfigurationResponse(false, currentConfigBuilder, responseObserver);
                        }
                    );
            } else {
                LOG.warn("ConsulBusinessOperationsService is null, cannot check Consul availability. Assuming ping failure for safety, though properties were saved.");
                handleConsulConfigurationResponse(false, currentConfigBuilder, responseObserver);
            }

        } catch (IOException e) {
            LOG.error("Error saving Consul configuration to {}: {}", resolvedBootstrapPath, e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to save Consul configuration: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            LOG.error("Unexpected error in setConsulConfiguration: {}", e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Unexpected error setting Consul configuration: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    // In com.krickert.yappy.bootstrap.service.BootstrapConfigServiceImpl.java
    @Override
    public void listAvailableClusters(Empty request, StreamObserver<ClusterList> responseObserver) {
        LOG.info("listAvailableClusters called");
        
        if (consulBusinessOperationsService != null && Boolean.parseBoolean(System.getProperty("yappy.consul.configured", "false"))) {
            LOG.info("Consul is configured, attempting to list clusters from ConsulBusinessOperationsService.");
            // Use reactive approach instead of blocking
            consulBusinessOperationsService.listAvailableClusterNames()
                .subscribe(
                    clusterNames -> {
                        LOG.info("Retrieved {} cluster names from ConsulBusinessOperationsService: {}", clusterNames.size(), clusterNames);
                        buildAndSendResponse(clusterNames, false, responseObserver);
                    },
                    error -> {
                        LOG.error("Error retrieving cluster names from Consul: {}", error.getMessage(), error);
                        responseObserver.onError(io.grpc.Status.INTERNAL
                                .withDescription("Failed to retrieve cluster names from Consul: " + error.getMessage())
                                .asRuntimeException());
                    }
                );
        } else if (consulBusinessOperationsService == null) {
            LOG.warn("ConsulBusinessOperationsService is null. Cannot list clusters from Consul. Simulating with hardcoded list for dev/test purposes.");
            List<String> simulatedClusters = Arrays.asList("simulatedCluster_NoService1", "simulatedCluster_NoService2");
            buildAndSendResponse(simulatedClusters, true, responseObserver);
        } else {
            LOG.warn("Consul is not marked as configured (yappy.consul.configured is not true). Not listing clusters from Consul.");
            buildAndSendResponse(Collections.emptyList(), false, responseObserver);
        }
    }

    private void handleConsulConfigurationResponse(boolean consulPingSuccess, ConsulConfigDetails.Builder currentConfigBuilder, StreamObserver<ConsulConnectionStatus> responseObserver) {
        ConsulConnectionStatus.Builder responseBuilder = ConsulConnectionStatus.newBuilder();
        if (consulPingSuccess) {
            LOG.info("Consul configuration updated, saved, and Consul is available. RESTART RECOMMENDED.");
            LOG.warn("**************************************************************************************");
            LOG.warn("* Consul has been configured. Key Consul settings have been updated programmatically. *");
            LOG.warn("* A RESTART of the YAPPY Engine is STRONGLY RECOMMENDED to ensure all components     *");
            LOG.warn("* initialize correctly with the new configuration.                                 *");
            LOG.warn("**************************************************************************************");

            responseBuilder.setSuccess(true)
                    .setMessage("Consul configuration updated and saved to " + resolvedBootstrapPath.toString() + ". Consul is available. RESTART RECOMMENDED.");
            responseBuilder.setCurrentConfig(currentConfigBuilder.build());
        } else {
            LOG.warn("Consul configuration saved to {}, but FAILED TO CONFIRM CONSUL AVAILABILITY. Please verify settings and Consul status. RESTART RECOMMENDED once Consul is reachable.", resolvedBootstrapPath);
            responseBuilder.setSuccess(false) // Indicate ping/connection failure
                    .setMessage("Consul configuration saved to " + resolvedBootstrapPath.toString() + ", but FAILED TO CONFIRM CONSUL AVAILABILITY. Verify settings. RESTART RECOMMENDED.");
            responseBuilder.setCurrentConfig(currentConfigBuilder.build()); // Still return the saved config
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    private void handleSuccessfulClusterCreation(String clusterName, NewClusterDetails request, StreamObserver<ClusterCreationStatus> responseObserver) {
        try {
            String consulPath = consulBusinessOperationsService.getFullClusterKey(clusterName);
            LOG.info("Successfully stored minimal configuration for cluster '{}' at Consul path '{}'", clusterName, consulPath);

            // Update bootstrap properties to select this new cluster
            Properties props = loadProperties(resolvedBootstrapPath);
            props.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, clusterName);
            saveProperties(resolvedBootstrapPath, props);
            LOG.info("Set newly created cluster '{}' as selected in bootstrap properties file {}", clusterName, resolvedBootstrapPath);

            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                    .setSuccess(true)
                    .setMessage("Cluster '" + clusterName + "' created and selected successfully. Configuration stored in Consul.")
                    .setClusterName(clusterName)
                    .setSeededConfigPath(consulPath)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Error updating bootstrap properties for cluster '{}': {}", clusterName, e.getMessage(), e);
            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Cluster '" + clusterName + "' was stored in Consul but failed to update bootstrap properties: " + e.getMessage())
                    .setClusterName(clusterName)
                    .setSeededConfigPath(consulBusinessOperationsService.getFullClusterKey(clusterName))
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private void buildAndSendResponse(List<String> clusterNames, boolean isSimulation, StreamObserver<ClusterList> responseObserver) {
        try {
            ClusterList.Builder clusterListBuilder = ClusterList.newBuilder();
            if (clusterNames != null) {
                for (String name : clusterNames) {
                    String currentStatus;
                    String clusterId;

                    if (isSimulation) {
                        currentStatus = "UNKNOWN"; // Status for simulated clusters
                        clusterId = getFullClusterKey(name); // Use local robust getFullClusterKey
                    } else {
                        // This path assumes consulBusinessOperationsService is not null
                        currentStatus = "NEEDS_VERIFICATION"; // Or determine dynamically if needed in future
                        clusterId = consulBusinessOperationsService.getFullClusterKey(name);
                    }

                    ClusterInfo info = ClusterInfo.newBuilder()
                            .setClusterName(name)
                            .setClusterId(clusterId)
                            .setStatus(currentStatus)
                            .build();
                    clusterListBuilder.addClusters(info);
                }
            }
            LOG.info("Responding with {} clusters: {}", clusterListBuilder.getClustersCount(), clusterNames);
            responseObserver.onNext(clusterListBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Failed to build cluster response: {}", e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to build cluster response: " + e.getMessage())
                    .asRuntimeException());
        }
    }



    @Override
    public void createNewCluster(NewClusterDetails request, StreamObserver<ClusterCreationStatus> responseObserver) {
        String clusterName = request.getClusterName();
        LOG.info("createNewCluster called for: {}", clusterName);

        if (clusterName == null || clusterName.trim().isEmpty()) {
            LOG.warn("createNewCluster called with empty cluster name.");
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("Cluster name cannot be empty for new cluster creation.")
                    .asRuntimeException());
            return;
        }

        if (consulBusinessOperationsService == null) {
            LOG.error("ConsulBusinessOperationsService is null. Cannot create new cluster '{}' in Consul.", clusterName);
            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to create new cluster '" + clusterName + "': Consul interaction service is not available.")
                    .setClusterName(clusterName)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }
        if (!Boolean.parseBoolean(System.getProperty("yappy.consul.configured", "false"))) {
            LOG.error("Consul is not configured (yappy.consul.configured is not true). Cannot create new cluster '{}' in Consul.", clusterName);
            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to create new cluster '" + clusterName + "': Consul is not configured in the system.")
                    .setClusterName(clusterName)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }


        try {
            PipelineClusterConfig minimalConfig = createMinimalClusterConfig(request);
            LOG.debug("Generated minimal PipelineClusterConfig for cluster name: {}", clusterName);

            // Store in Consul using reactive approach
            consulBusinessOperationsService.storeClusterConfiguration(clusterName, minimalConfig)
                    .subscribe(
                        storedSuccessfully -> {
                            if (storedSuccessfully) {
                                handleSuccessfulClusterCreation(clusterName, request, responseObserver);
                            } else {
                                LOG.error("Failed to store minimal configuration for cluster '{}' in Consul.", clusterName);
                                ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                                        .setSuccess(false)
                                        .setMessage("Failed to store new cluster configuration for '" + clusterName + "' in Consul.")
                                        .setClusterName(clusterName)
                                        .build();
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            }
                        },
                        error -> {
                            LOG.error("Error storing cluster configuration for '{}': {}", clusterName, error.getMessage(), error);
                            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                                    .setSuccess(false)
                                    .setMessage("Failed to create new cluster '" + clusterName + "': " + error.getMessage())
                                    .setClusterName(clusterName)
                                    .setSeededConfigPath(getFullClusterKey(clusterName))
                                    .build();
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        }
                    );

        } catch (Exception e) {
            LOG.error("Failed to create or store new cluster configuration for '{}': {}", clusterName, e.getMessage(), e);
            // Determine a path for the response, even if storage failed.
            // It's better to use the path that *would have been* used.
            String pathForResponse = "unknown/error"; // Default error path
            if (consulBusinessOperationsService != null) {
                pathForResponse = consulBusinessOperationsService.getFullClusterKey(clusterName);
            } else {
                // Fallback if service is null, though this case is handled earlier
                pathForResponse = getFullClusterKey(clusterName); // Local robust version
            }

            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to create new cluster '" + clusterName + "': " + e.getMessage())
                    .setClusterName(clusterName)
                    .setSeededConfigPath(pathForResponse) // Always set a non-null path
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    // In BootstrapConfigServiceImpl.java
    private static final String DEFAULT_CONSUL_CONFIG_BASE_PATH = "pipeline-configs/clusters"; // Define a constant
    // In com.krickert.yappy.bootstrap.service.BootstrapConfigServiceImpl.java
    private String getConsulConfigPathFromProperties() {
        try {
            Properties props = loadProperties(resolvedBootstrapPath);
            String basePath = props.getProperty("yappy.bootstrap.consul.config.base_path");
            if (basePath == null || basePath.trim().isEmpty()) {
                LOG.warn("Property 'yappy.bootstrap.consul.config.base_path' not found or is empty in {}.",
                        resolvedBootstrapPath);
                return null;
            }
            return basePath;
        } catch (IOException e) {
            LOG.error("Error loading properties from {} to get base path: {}", resolvedBootstrapPath, e.getMessage(), e);
            return null;
        }
    }
    private String getFullClusterKey(String clusterName) {
        String basePath = getConsulConfigPathFromProperties();
        if (basePath == null || basePath.isBlank()) {
            LOG.warn("Consul base path (yappy.bootstrap.consul.config.base_path) is not configured in bootstrap properties. Using default '{}' for key construction for cluster '{}'.", DEFAULT_CONSUL_CONFIG_BASE_PATH, clusterName);
            basePath = DEFAULT_CONSUL_CONFIG_BASE_PATH; // Provide a default
        }
        // Ensure clusterName is not null or blank (already validated in public methods, but good for a private util)
        if (clusterName == null || clusterName.isBlank()) {
            LOG.error("Cluster name is null or blank in getFullClusterKey. This should not happen. Using UNKNOWN_CLUSTER.");
            clusterName = "UNKNOWN_CLUSTER"; // Fallback
        }
        String fullPath = basePath.endsWith("/") ? basePath + clusterName : basePath + "/" + clusterName;
        return fullPath; // Now guaranteed non-null
    }

    @Override
    public void getConsulConfiguration(Empty request, StreamObserver<ConsulConfigDetails> responseObserver) {
        LOG.info("getConsulConfiguration called, resolved path: {}", resolvedBootstrapPath);
        try {
            Properties props = loadProperties(resolvedBootstrapPath);
            ConsulConfigDetails.Builder detailsBuilder = ConsulConfigDetails.newBuilder();

            String host = props.getProperty(YAPPY_BOOTSTRAP_CONSUL_HOST);
            String portStr = props.getProperty(YAPPY_BOOTSTRAP_CONSUL_PORT);
            String aclToken = props.getProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN);
            String selectedClusterName = props.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME);

            if (host != null && !host.isEmpty() && portStr != null && !portStr.isEmpty()) {
                detailsBuilder.setHost(host);
                try {
                    detailsBuilder.setPort(Integer.parseInt(portStr));
                } catch (NumberFormatException e) {
                    LOG.warn("Invalid port format in properties file {}: {}", resolvedBootstrapPath, portStr, e);
                    // Potentially return error or default, here returning what's available + default port
                    detailsBuilder.setPort(0); // Indicate invalid/default
                }
                if (aclToken != null && !aclToken.isEmpty()) {
                    detailsBuilder.setAclToken(aclToken);
                }
                if (selectedClusterName != null && !selectedClusterName.isEmpty()) {
                    detailsBuilder.setSelectedYappyClusterName(selectedClusterName);
                }
                 LOG.info("Loaded Consul config from {}: Host={}, Port={}, ACL token present: {}, Selected Cluster: {}",
                     resolvedBootstrapPath, host, portStr, (aclToken != null && !aclToken.isEmpty()), selectedClusterName != null ? selectedClusterName : "N/A");
            } else {
                LOG.info("No Consul configuration found in {} or properties are incomplete. Returning defaults/empty.", resolvedBootstrapPath);
                // Optionally set default values or leave them empty as per proto definition
                // For example:
                // detailsBuilder.setHost("localhost");
                // detailsBuilder.setPort(8500);
                // If basic config isn't there, selected cluster is also unlikely / irrelevant to set here
            }

            responseObserver.onNext(detailsBuilder.build());
            responseObserver.onCompleted();

        } catch (IOException e) {
            LOG.warn("Error loading Consul configuration from {}: {}. Returning empty config.", resolvedBootstrapPath, e.getMessage(), e);
            // If file cannot be read, it's like not having the config. Return empty/default.
             ConsulConfigDetails.Builder detailsBuilder = ConsulConfigDetails.newBuilder();
            // Optionally set default values or leave them empty
            responseObserver.onNext(detailsBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Unexpected error in getConsulConfiguration: {}", e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Unexpected error getting Consul configuration: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void selectExistingCluster(ClusterSelection request, StreamObserver<OperationStatus> responseObserver) {
        String clusterName = request.getClusterName();
        LOG.info("selectExistingCluster called for: {}, resolved path: {}", clusterName, resolvedBootstrapPath);

        if (clusterName == null || clusterName.trim().isEmpty()) {
            LOG.warn("selectExistingCluster called with empty cluster name.");
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("Cluster name cannot be empty.")
                    .asRuntimeException());
            return;
        }

        try {
            Properties props = loadProperties(resolvedBootstrapPath);
            props.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, clusterName);
            saveProperties(resolvedBootstrapPath, props);

            LOG.info("Successfully selected cluster '{}' and saved to {}", clusterName, resolvedBootstrapPath);
            OperationStatus response = OperationStatus.newBuilder()
                    .setSuccess(true)
                    .setMessage("Cluster '" + clusterName + "' selected successfully.")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IOException e) {
            LOG.error("Error selecting cluster '{}' in properties file {}: {}", clusterName, resolvedBootstrapPath, e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to save selected cluster: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            LOG.error("Unexpected error in selectExistingCluster for cluster '{}': {}", clusterName, e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Unexpected error selecting cluster: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    // In com.krickert.yappy.bootstrap.service.BootstrapConfigServiceImpl.java
    private PipelineClusterConfig createMinimalClusterConfig(NewClusterDetails details) {
        LOG.debug("Creating minimal cluster config for cluster name: {}", details.getClusterName());
        PipelineClusterConfig.PipelineClusterConfigBuilder clusterConfigBuilder = PipelineClusterConfig.builder();
        clusterConfigBuilder.clusterName(details.getClusterName());

        PipelineGraphConfig.PipelineGraphConfigBuilder graphConfigBuilder = PipelineGraphConfig.builder();
        PipelineModuleMap.PipelineModuleMapBuilder moduleMapBuilder = PipelineModuleMap.builder();

        if (details.hasFirstPipelineName() && !details.getFirstPipelineName().isEmpty()) {
            String firstPipelineName = details.getFirstPipelineName();
            clusterConfigBuilder.defaultPipelineName(firstPipelineName);

            PipelineConfig.PipelineConfigBuilder pipelineBuilder = PipelineConfig.builder();
            pipelineBuilder.name(firstPipelineName);
            // Ensure pipelineSteps is initialized, even if empty, to satisfy the PipelineConfig record constructor
            pipelineBuilder.pipelineSteps(Collections.emptyMap());
            Map<String, PipelineConfig> configs = new TreeMap<>();
            configs.put(firstPipelineName, pipelineBuilder.build());
            graphConfigBuilder.pipelines(configs);
        } else {
            graphConfigBuilder.pipelines(Collections.emptyMap()); // Ensure pipelines map is initialized
        }

        clusterConfigBuilder.pipelineGraphConfig(graphConfigBuilder.build());

        // --- CORRECTED LOGIC FOR MODULES ---
        Map<String, PipelineModuleConfiguration> availableModulesMap = new TreeMap<>(); // Declare map BEFORE the loop
        if (details.getInitialModulesForFirstPipelineList() != null && !details.getInitialModulesForFirstPipelineList().isEmpty()) {
            for (InitialModuleConfig initialModule : details.getInitialModulesForFirstPipelineList()) {
                PipelineModuleConfiguration.PipelineModuleConfigurationBuilder moduleConfigBuilder = PipelineModuleConfiguration.builder();
                moduleConfigBuilder.implementationId(initialModule.getImplementationId());
                moduleConfigBuilder.implementationName("Module_" + initialModule.getImplementationId()); // Placeholder name
                // Ensure customConfig is initialized to an empty map if not otherwise specified
                moduleConfigBuilder.customConfig(Collections.emptyMap());
                availableModulesMap.put(initialModule.getImplementationId(), moduleConfigBuilder.build());
            }
        }
        moduleMapBuilder.availableModules(availableModulesMap); // Set the builder's map AFTER the loop
        // --- END CORRECTED LOGIC ---

        clusterConfigBuilder.pipelineModuleMap(moduleMapBuilder.build());
        clusterConfigBuilder.allowedKafkaTopics(Collections.emptySet());
        clusterConfigBuilder.allowedGrpcServices(Collections.emptySet());

        return clusterConfigBuilder.build();
    }




}
