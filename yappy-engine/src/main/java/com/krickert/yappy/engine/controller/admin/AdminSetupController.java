package com.krickert.yappy.engine.controller.admin;

import com.krickert.yappy.engine.controller.admin.dto.*;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

// Imports for commented out createNewYappyCluster method
// import io.yappy.engine.controller.admin.dto.CreateClusterRequest;
// import io.yappy.engine.controller.admin.dto.CreateClusterResponse;
// Corrected imports for models from com.krickert.search.config.pipeline.model
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig; // Added import
import com.krickert.search.config.pipeline.model.PipelineModuleMap; // Added import
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;


@Controller("/api/setup")
@io.micronaut.core.annotation.Introspected
public class AdminSetupController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSetupController.class);
    private static final String BOOTSTRAP_PROPERTIES_FILENAME = "engine-bootstrap.properties";
    private static final String YAPPY_CLUSTER_PREFIX = "yappy/pipeline-clusters/";

    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    
    private Path getYappyDirPath() {
        return Paths.get(System.getProperty("user.home"), ".yappy");
    }
    
    private Path getBootstrapPropertiesPath() {
        return getYappyDirPath().resolve(BOOTSTRAP_PROPERTIES_FILENAME);
    }

    @Inject
    public AdminSetupController(ConsulBusinessOperationsService consulBusinessOperationsService) {
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        LOG.info("AdminSetupController initialized with hash: {}", this.hashCode());
        LOG.info("AdminSetupController class: {}", this.getClass().getName());
        LOG.info("AdminSetupController annotations: {}", java.util.Arrays.toString(this.getClass().getAnnotations()));
    }
    
    @Get("/test")
    @Produces(MediaType.TEXT_PLAIN)
    public String test() {
        LOG.info("test() method called");
        return "Controller is working";
    }
    
    @Get("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        LOG.info("ping() method called");
        return "pong";
    }
    
    @Get("/ping-reactive")
    @Produces(MediaType.TEXT_PLAIN)
    public Mono<String> pingReactive() {
        LOG.info("pingReactive() method called");
        return Mono.just("pong reactive");
    }

    @Get("/consul-config")
    @Produces(MediaType.APPLICATION_JSON)
    public ConsulConfigResponse getCurrentConsulConfiguration() {
        LOG.info("getCurrentConsulConfiguration called");
        Properties props = loadProperties();
        return new ConsulConfigResponse(
            props.getProperty("yappy.bootstrap.consul.host"),
            props.getProperty("yappy.bootstrap.consul.port"),
            props.getProperty("yappy.bootstrap.consul.aclToken"),
            props.getProperty("yappy.bootstrap.cluster.selectedName")
        );
    }

    @Get("/yappy-clusters")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List Available Yappy Clusters",
               description = "Retrieves a list of available Yappy cluster configurations from Consul.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved cluster list.")
    @ApiResponse(responseCode = "500", description = "Error retrieving clusters from Consul.")
    public Mono<List<YappyClusterInfo>> listAvailableYappyClusters() {
        return consulBusinessOperationsService.listAvailableClusterNames()
            .map(clusterKeys -> {
                if (clusterKeys == null || clusterKeys.isEmpty()) {
                    return Collections.<YappyClusterInfo>emptyList();
                }

                return clusterKeys.stream()
                    .map(key -> {
                        String clusterName = extractClusterNameFromKey(key);
                        // Status determination is complex and deferred as per task description.
                        return new YappyClusterInfo(clusterName, key, "NEEDS_VERIFICATION");
                    })
                    .collect(Collectors.toList());
            })
            .onErrorResume(e -> {
                LOG.error("Error listing available Yappy clusters from Consul", e);
                // Return empty list on error
                return Mono.just(Collections.emptyList());
            });
    }

    private String extractClusterNameFromKey(String key) {
        if (key == null) {
            return "unknown";
        }
        if (key.startsWith(YAPPY_CLUSTER_PREFIX) && key.length() > YAPPY_CLUSTER_PREFIX.length()) {
            String namePart = key.substring(YAPPY_CLUSTER_PREFIX.length());
            // Remove trailing slash if present
            if (namePart.endsWith("/")) {
                namePart = namePart.substring(0, namePart.length() - 1);
            }
            return namePart;
        }
        // Fallback if key format is unexpected
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < key.length() - 1) {
            return key.substring(lastSlash + 1);
        }
        return key; // or some default like "unknown-format"
    }

    // Method from Task 2.2, now uncommented with corrected model imports
    @Post("/yappy-clusters")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create New Yappy Cluster",
               description = "Creates a new Yappy cluster configuration in Consul and selects it in the bootstrap properties.")
    @ApiResponse(responseCode = "201", description = "Yappy cluster created and selected successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload or cluster already exists.")
    @ApiResponse(responseCode = "500", description = "Error creating cluster or updating bootstrap configuration.")
    public Mono<CreateClusterResponse> createNewYappyCluster(@Body @Valid CreateClusterRequest request) {
        String clusterName = request.getClusterName();
        String consulPath = YAPPY_CLUSTER_PREFIX + clusterName;

        try {
            // 1. Create PipelineClusterConfig
            Map<String, PipelineConfig> pipelinesMap = new HashMap<>();
            if (request.getFirstPipelineName() != null && !request.getFirstPipelineName().isEmpty()) {
                pipelinesMap.put(request.getFirstPipelineName(),
                    new PipelineConfig(request.getFirstPipelineName(), Collections.emptyMap()));
            }
            PipelineGraphConfig pipelineGraphConfig = new PipelineGraphConfig(pipelinesMap);

            Map<String, PipelineModuleConfiguration> availableModulesMap = new HashMap<>();
            if (request.getInitialModules() != null && !request.getInitialModules().isEmpty()) {
                for (PipelineModuleInput moduleInput : request.getInitialModules()) {
                    availableModulesMap.put(moduleInput.getImplementationId(),
                        new PipelineModuleConfiguration(moduleInput.getImplementationName(), moduleInput.getImplementationId(), null, Collections.emptyMap()));
                }
            }
            PipelineModuleMap pipelineModuleMap = new PipelineModuleMap(availableModulesMap);

            // Construct PipelineClusterConfig using its canonical constructor
            PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                clusterName,
                pipelineGraphConfig,
                pipelineModuleMap,
                request.getFirstPipelineName(), // defaultPipelineName
                Collections.emptySet(),         // allowedKafkaTopics
                Collections.emptySet()          // allowedGrpcServices
            );

            // 2. Store in Consul
            // The method storeClusterConfiguration expects PipelineClusterConfig, not String.
            // Corrected call: uses clusterName as first arg, and clusterConfig (as Object) as second. No boolean flag.
            return consulBusinessOperationsService.storeClusterConfiguration(clusterName, clusterConfig)
                .flatMap(storedSuccessfully -> {
                    if (storedSuccessfully == null || !storedSuccessfully) {
                        String message = "Failed to store cluster configuration in Consul. It might already exist or Consul is unavailable.";
                        LOG.warn(message + " Cluster: {}", clusterName);
                        return Mono.just(new CreateClusterResponse(false, message, clusterName, consulPath));
                    }

                    try {
                        // 3. Update bootstrap properties
                        Properties props = loadProperties();
                        props.setProperty("yappy.bootstrap.cluster.selectedName", clusterName);
                        saveProperties(props);

                        String successMessage = "Yappy cluster '" + clusterName + "' created and selected successfully. Config path: " + consulPath;
                        LOG.info(successMessage);
                        return Mono.just(new CreateClusterResponse(true, successMessage, clusterName, consulPath));
                    } catch (IOException e) {
                        LOG.error("Error updating bootstrap properties for cluster '{}'", clusterName, e);
                        return Mono.just(new CreateClusterResponse(false, "Error updating bootstrap properties: " + e.getMessage(), clusterName, consulPath));
                    }
                })
                .onErrorResume(e -> {
                    LOG.error("Error creating new Yappy cluster '{}'", clusterName, e);
                    return Mono.just(new CreateClusterResponse(false, "An unexpected error occurred: " + e.getMessage(), clusterName, consulPath));
                });

        } catch (Exception e) {
            LOG.error("Error creating new Yappy cluster '{}'", clusterName, e);
            return Mono.just(new CreateClusterResponse(false, "An unexpected error occurred: " + e.getMessage(), clusterName, consulPath));
        }
    }

    @Post("/cluster/select")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Select Active Yappy Cluster",
               description = "Updates the bootstrap configuration to point to the selected Yappy cluster.")
    @ApiResponse(responseCode = "200", description = "Active Yappy cluster selected successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload.")
    @ApiResponse(responseCode = "500", description = "Error updating bootstrap properties file.")
    public Mono<SelectClusterResponse> selectActiveYappyCluster(@Body @Valid SelectClusterRequest request) {
        String clusterName = request.getClusterName();
        try {
            // Ensure .yappy directory exists, though loadProperties/saveProperties might implicitly handle some aspects.
            // It's good practice if creating the file for the first time.
            Path yappyDir = getYappyDirPath();
            if (!Files.exists(yappyDir)) {
                Files.createDirectories(yappyDir);
            }

            Properties props = loadProperties();
            props.setProperty("yappy.bootstrap.cluster.selectedName", clusterName);
            saveProperties(props);

            // Optionally, verify if the cluster actually exists in Consul here.
            // For now, just updating the bootstrap file as per minimal requirement.
            // return listAvailableYappyClusters()
            //     .flatMap(availableClusters -> {
            //         boolean clusterExists = availableClusters.stream().anyMatch(c -> clusterName.equals(c.getClusterName()));
            //         if (!clusterExists) {
            //             LOG.warn("Selected cluster '{}' does not appear to exist in Consul. Bootstrap file updated anyway.", clusterName);
            //             return Mono.just(new SelectClusterResponse(false, "Selected cluster '" + clusterName + "' does not exist in Consul. Bootstrap updated, but this may lead to issues."));
            //         }
            //         String message = "Active Yappy cluster selected successfully: " + clusterName +
            //                          ". A restart may be required for the change to take full effect.";
            //         LOG.info(message);
            //         return Mono.just(new SelectClusterResponse(true, message));
            //     });

            String message = "Active Yappy cluster selected successfully: " + clusterName +
                             ". A restart may be required for the change to take full effect.";
            LOG.info(message);
            return Mono.just(new SelectClusterResponse(true, message));

        } catch (IOException e) {
            LOG.error("Error updating bootstrap properties file for cluster selection '{}'", clusterName, e);
            return Mono.just(new SelectClusterResponse(false, "Error updating bootstrap properties file: " + e.getMessage()));
        } catch (Exception e) {
            LOG.error("An unexpected error occurred while selecting cluster '{}'", clusterName, e);
            return Mono.just(new SelectClusterResponse(false, "An unexpected error occurred: " + e.getMessage()));
        }
    }

    @Post("/consul-config")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Set Consul Configuration",
               description = "Updates the Consul connection details in the bootstrap properties file.")
    @ApiResponse(responseCode = "200", description = "Consul configuration updated successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload.")
    @ApiResponse(responseCode = "500", description = "Error updating bootstrap properties file.")
    public Mono<SetConsulConfigResponse> setConsulConfiguration(@Body @Valid SetConsulConfigRequest request) {
        return Mono.fromCallable(() -> {
            try {
                // Ensure .yappy directory exists
                Path yappyDir = getYappyDirPath();
                if (!Files.exists(yappyDir)) {
                    Files.createDirectories(yappyDir);
                }

                Properties props = loadProperties();
                
                // Update properties
                if (request.getHost() != null && !request.getHost().trim().isEmpty()) {
                    props.setProperty("yappy.bootstrap.consul.host", request.getHost());
                }
                // Port is an int, so it's always present (can't be null)
                props.setProperty("yappy.bootstrap.consul.port", String.valueOf(request.getPort()));
                
                if (request.getAclToken() != null) {
                    if (request.getAclToken().trim().isEmpty()) {
                        // Remove ACL token if empty string provided
                        props.remove("yappy.bootstrap.consul.aclToken");
                    } else {
                        props.setProperty("yappy.bootstrap.consul.aclToken", request.getAclToken());
                    }
                }
                
                saveProperties(props);
                
                // TODO: Optionally verify connection to Consul with new settings
                
                String message = "Consul configuration updated successfully. A restart may be required for changes to take effect.";
                LOG.info(message);
                
                // Create ConsulConfigResponse to include in the response
                ConsulConfigResponse currentConfig = new ConsulConfigResponse(
                    props.getProperty("yappy.bootstrap.consul.host"),
                    props.getProperty("yappy.bootstrap.consul.port"),
                    props.getProperty("yappy.bootstrap.consul.aclToken"),
                    props.getProperty("yappy.bootstrap.cluster.selectedName")
                );
                
                return new SetConsulConfigResponse(true, message, currentConfig);
                
            } catch (IOException e) {
                LOG.error("Error updating Consul configuration", e);
                return new SetConsulConfigResponse(false, "Error updating configuration: " + e.getMessage(), null);
            } catch (Exception e) {
                LOG.error("Unexpected error updating Consul configuration", e);
                return new SetConsulConfigResponse(false, "Unexpected error: " + e.getMessage(), null);
            }
        });
    }

    private Properties loadProperties() {
        Properties props = new Properties();
        Path bootstrapPath = getBootstrapPropertiesPath();
        if (Files.exists(bootstrapPath)) {
            try (InputStream input = new FileInputStream(bootstrapPath.toFile())) {
                props.load(input);
            } catch (IOException ex) {
                LOG.error("Failed to load bootstrap properties from {}", bootstrapPath, ex);
                // For now, returns empty props
            }
        }
        return props;
    }

    private void saveProperties(Properties props) throws IOException {
        Path bootstrapPath = getBootstrapPropertiesPath();
        try (OutputStream output = new FileOutputStream(bootstrapPath.toFile())) {
            props.store(output, "Yappy Engine Bootstrap Configuration");
        }
    }
}
