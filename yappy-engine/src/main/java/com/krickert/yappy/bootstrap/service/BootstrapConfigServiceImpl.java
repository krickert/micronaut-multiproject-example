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
    private final ApplicationContext applicationContext;


    @Inject
    public BootstrapConfigServiceImpl(
            ConsulBusinessOperationsService consulBusinessOperationsService,
            @Value("${yappy.engine.bootstrap-file.path:~/.yappy/engine-bootstrap.properties}") String bootstrapFilePath,
            ApplicationContext applicationContext) {
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.bootstrapFilePath = bootstrapFilePath;
        this.resolvedBootstrapPath = Paths.get(this.bootstrapFilePath.replace("~", System.getProperty("user.home")));
        this.applicationContext = applicationContext;
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
                props.remove(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN); // Remove if empty or not present
            }

            saveProperties(resolvedBootstrapPath, props);

            // TODO: Implement actual Consul ping/health check
            // For now, assume success if write succeeds.
            boolean consulPingSuccess = true; 

            ConsulConfigDetails.Builder currentConfigBuilder = request.toBuilder();
            String selectedCluster = props.getProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME);
            if (selectedCluster != null && !selectedCluster.isEmpty()) {
                currentConfigBuilder.setSelectedYappyClusterName(selectedCluster);
            }

            ConsulConnectionStatus.Builder responseBuilder = ConsulConnectionStatus.newBuilder();
            if (consulPingSuccess) {
                // Set system properties to reflect the new configuration
                System.setProperty("consul.client.host", request.getHost());
                System.setProperty("consul.client.port", String.valueOf(request.getPort()));
                // System.setProperty("consul.client.defaultZone", request.getHost() + ":" + request.getPort()); // Alternative
                System.setProperty("consul.client.registration.enabled", "true");
                System.setProperty("consul.client.config.enabled", "true");
                System.setProperty("micronaut.config-client.enabled", "true");
                System.setProperty("yappy.consul.configured", "true");
                if (request.hasAclToken() && !request.getAclToken().isEmpty()) {
                    System.setProperty("consul.client.acl-token", request.getAclToken());
                } else {
                    System.clearProperty("consul.client.acl-token");
                }

                LOG.info("System properties updated for Consul client. Host: {}, Port: {}, Registration: true, Config: true, Yappy Configured: true, Micronaut Config Client: true", request.getHost(), request.getPort());
                LOG.warn("**************************************************************************************");
                LOG.warn("* Consul has been configured. Key Consul settings have been updated programmatically. *");
                LOG.warn("* A RESTART of the YAPPY Engine is STRONGLY RECOMMENDED to ensure all components     *");
                LOG.warn("* initialize correctly with the new configuration.                                 *");
                LOG.warn("**************************************************************************************");
                
                responseBuilder.setSuccess(true)
                               .setMessage("Consul configuration updated and saved successfully to " + resolvedBootstrapPath.toString() + ". RESTART RECOMMENDED.");
                responseBuilder.setCurrentConfig(currentConfigBuilder.build());
            } else {
                // This part is currently unreachable due to consulPingSuccess being true
                responseBuilder.setSuccess(false)
                               .setMessage("Consul configuration saved, but failed to ping Consul (not implemented).");
                responseBuilder.setCurrentConfig(currentConfigBuilder.build());
            }
            
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

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
    public void listAvailableClusters(Empty request, StreamObserver<ClusterList> responseObserver) {
        LOG.info("listAvailableClusters called");
        try {
            // TODO: Implement actual Consul key listing in ConsulBusinessOperationsService
            // The yappy_service_registration.md document implies that cluster configurations are stored under "pipeline-configs/clusters/".
            // If ConsulBusinessOperationsService has a generic listKeys(String prefix) method, use it with the prefix "pipeline-configs/clusters/".
            // The returned keys would be the full paths, so you'd need to extract the cluster name part.
            // Example: key = "pipeline-configs/clusters/my-cluster-name/config.json", extracted name = "my-cluster-name"
            // For now, simulating with a hardcoded list.
            
            List<String> clusterNames;
            try {
                 // clusterNames = consulBusinessOperationsService.listAvailableClusterNames(); // Ideal
                 // OR
                 // List<String> allKeys = consulBusinessOperationsService.listKeys("pipeline-configs/clusters/");
                 // clusterNames = allKeys.stream().map(key -> key.replaceFirst("^pipeline-configs/clusters/", "").split("/")[0]).distinct().collect(Collectors.toList());
                
                // Simulating for now:
                if (consulBusinessOperationsService != null && System.getProperty("simulateRealConsulBehavior", "false").equals("true") ) { 
                    // This condition allows for future testing if a mock or real service is injected
                    // and we want to test its actual (potentially empty) return.
                    // For now, this path will likely not be taken in typical unit tests unless simulateRealConsulBehavior is set.
                    clusterNames = consulBusinessOperationsService.listAvailableClusterNames(); 
                } else {
                    LOG.warn("ConsulBusinessOperationsService.listAvailableClusterNames() or listKeys() is not yet fully implemented or available for BootstrapConfigService. Simulating with a hardcoded list.");
                    clusterNames = Arrays.asList("simulatedCluster1", "simulatedCluster2_needs_setup");
                }

            } catch (Exception e) { // Catching generic exception as the actual method signature is unknown
                LOG.error("Error calling ConsulBusinessOperationsService to list cluster names (or simulated call failed): {}. Returning empty list.", e.getMessage(), e);
                clusterNames = Collections.emptyList();
            }


            ClusterList.Builder clusterListBuilder = ClusterList.newBuilder();
            if (clusterNames != null) {
                for (String name : clusterNames) {
                    ClusterInfo info = ClusterInfo.newBuilder()
                            .setClusterName(name)
                            .setClusterId("pipeline-configs/clusters/" + name) // Example path
                            .setStatus("NEEDS_VERIFICATION") // Placeholder status, actual status would require deeper check
                            .build();
                    clusterListBuilder.addClusters(info);
                }
            }
            LOG.info("Found {} clusters: {}", clusterNames != null ? clusterNames.size() : 0, clusterNames);
            responseObserver.onNext(clusterListBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Failed to list available clusters: {}", e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to list available clusters: " + e.getMessage())
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
                    .setMessage("Successfully selected cluster '" + clusterName + "'.")
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
            pipelineBuilder.pipelineSteps(new HashMap<>()); // Empty steps map
            Map<String, PipelineConfig> configs = new TreeMap<>();
            configs.put(firstPipelineName, pipelineBuilder.build());
            graphConfigBuilder.pipelines(configs);
        } else {
            // No default pipeline if none specified
            // graphConfigBuilder.putAllPipelines(new HashMap<>()); // Empty pipelines map
        }
        
        clusterConfigBuilder.pipelineGraphConfig(graphConfigBuilder.build());

        if (details.getInitialModulesForFirstPipelineList() != null && !details.getInitialModulesForFirstPipelineList().isEmpty()) {
            for (InitialModuleConfig initialModule : details.getInitialModulesForFirstPipelineList()) {
                PipelineModuleConfiguration.PipelineModuleConfigurationBuilder moduleConfigBuilder = PipelineModuleConfiguration.builder();
                moduleConfigBuilder.implementationId(initialModule.getImplementationId());
                moduleConfigBuilder.implementationName("Module_" + initialModule.getImplementationId()); // Placeholder name
                // moduleConfigBuilder.setCustomConfigSchemaReference(""); // Optional: can be empty or null
                // moduleConfigBuilder.putAllDefaultConfig(new HashMap<>()); // Optional: empty default config
                Map<String, PipelineModuleConfiguration> configurationMap = new TreeMap<>();
                configurationMap.put(initialModule.getImplementationId(), moduleConfigBuilder.build());
                moduleMapBuilder.availableModules(configurationMap);
            }
        } else {
            // moduleMapBuilder.putAllAvailableModules(new HashMap<>()); // Empty modules map
        }
        clusterConfigBuilder.pipelineModuleMap(moduleMapBuilder.build());
        
        // Set other fields to defaults or empty lists as per subtask
        // clusterConfigBuilder.addAllAllowedKafkaTopics(new ArrayList<>());
        // clusterConfigBuilder.addAllAllowedGrpcServices(new ArrayList<>());
        // clusterConfigBuilder.setKafkaBootstrapServers("localhost:9092"); // Example default
        // clusterConfigBuilder.setSchemaRegistryUrl("http://localhost:8081"); // Example default

        return clusterConfigBuilder.build();
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

        try {
            PipelineClusterConfig minimalConfig = createMinimalClusterConfig(request);
            LOG.debug("Generated minimal PipelineClusterConfig for {}: {}", clusterName, minimalConfig.toString()); // Using toString, consider JSON if too verbose

            // Store in Consul
            String consulPath = "pipeline-configs/clusters/" + clusterName;
            // Assuming storeClusterConfiguration returns void or throws exception on failure.
            // Adjust if it returns a boolean status.
            consulBusinessOperationsService.storeClusterConfiguration(clusterName, minimalConfig);
            LOG.info("Successfully stored minimal configuration for cluster '{}' at Consul path '{}'", clusterName, consulPath);

            // Update bootstrap properties to select this new cluster
            Properties props = loadProperties(resolvedBootstrapPath);
            props.setProperty(YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME, clusterName);
            saveProperties(resolvedBootstrapPath, props);
            LOG.info("Set newly created cluster '{}' as selected in bootstrap properties file {}", clusterName, resolvedBootstrapPath);

            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                    .setSuccess(true)
                    .setMessage("Cluster '" + clusterName + "' created successfully and configuration stored in Consul.")
                    .setClusterName(clusterName)
                    .setSeededConfigPath(consulPath) 
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) { // Catching a broad exception, specific exceptions from storeClusterConfiguration should be handled if known
            LOG.error("Failed to create or store new cluster configuration for '{}': {}", clusterName, e.getMessage(), e);
            ClusterCreationStatus response = ClusterCreationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to create new cluster '" + clusterName + "': " + e.getMessage())
                    .setClusterName(clusterName)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted(); // For gRPC server-side, onCompleted must be called even after errors for some patterns, or use onError.
                                            // Given we are sending a status object, onNext + onCompleted is appropriate.
                                            // If we wanted to signal a gRPC level error, use responseObserver.onError()
        }
    }

    /**
     * @deprecated Replaced by {@link #createMinimalClusterConfig(NewClusterDetails)} which returns the actual object.
     * This method is kept for potential backward compatibility during transition but should be removed.
     */
    @Deprecated
    private String generateMinimalPipelineClusterConfig(NewClusterDetails details) {
        LOG.warn("DEPRECATED generateMinimalPipelineClusterConfig(NewClusterDetails) called. Should use createMinimalClusterConfig returning the object.");
        PipelineClusterConfig config = createMinimalClusterConfig(details);
        return config.toString(); 
    }
}
