package com.krickert.yappy.engine.core.controller.admin;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.yappy.engine.controller.admin.dto.*;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * This controller provides compatibility for tests in the core.controller.admin package.
 * It implements the same API as AdminSetupController but with non-reactive return types
 * to match the expectations of the tests.
 */
@Validated
@Controller("/api/setup")
public class AdminSetupController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSetupController.class);
    private static final String BOOTSTRAP_PROPERTIES_FILENAME = "engine-bootstrap.properties";
    private static final String YAPPY_CLUSTER_PREFIX = "yappy/pipeline-clusters/";

    private final ConsulBusinessOperationsService consulBusinessOperationsService;

    @Inject
    public AdminSetupController(ConsulBusinessOperationsService consulBusinessOperationsService) {
        this.consulBusinessOperationsService = consulBusinessOperationsService;
    }

    @Get("/consul")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get Current Consul Configuration",
               description = "Retrieves the current Consul connection details from the engine's bootstrap configuration.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved Consul configuration.")
    @ApiResponse(responseCode = "500", description = "Error reading bootstrap configuration.")
    public ConsulConfigResponse getCurrentConsulConfiguration() {
        Properties props = loadProperties();
        String host = props.getProperty("yappy.bootstrap.consul.host");
        String port = props.getProperty("yappy.bootstrap.consul.port");
        String aclToken = props.getProperty("yappy.bootstrap.consul.aclToken");
        String selectedYappyClusterName = props.getProperty("yappy.bootstrap.cluster.selectedName");
        return new ConsulConfigResponse(host, port, aclToken, selectedYappyClusterName);
    }

    @Get("/clusters")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List Available Yappy Clusters",
               description = "Retrieves a list of available Yappy cluster configurations from Consul.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved cluster list.")
    @ApiResponse(responseCode = "500", description = "Error retrieving clusters from Consul.")
    public List<YappyClusterInfo> listAvailableYappyClusters() {
        try {
            List<String> clusterKeys = consulBusinessOperationsService.listAvailableClusterNames().block();

            if (clusterKeys == null || clusterKeys.isEmpty()) {
                return Collections.emptyList();
            }

            return clusterKeys.stream()
                .map(key -> {
                    String clusterName = extractClusterNameFromKey(key);
                    return new YappyClusterInfo(clusterName, key, "NEEDS_VERIFICATION");
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.error("Error listing available Yappy clusters from Consul", e);
            return Collections.emptyList();
        }
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
        return key;
    }

    @Post("/clusters")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create New Yappy Cluster",
               description = "Creates a new Yappy cluster configuration in Consul and selects it in the bootstrap properties.")
    @ApiResponse(responseCode = "201", description = "Yappy cluster created and selected successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload or cluster already exists.")
    @ApiResponse(responseCode = "500", description = "Error creating cluster or updating bootstrap configuration.")
    public CreateClusterResponse createNewYappyCluster(@Body @Valid CreateClusterRequest request) {
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
                request.getFirstPipelineName(),
                Collections.emptySet(),
                Collections.emptySet()
            );

            // 2. Store in Consul
            Boolean storedSuccessfully = consulBusinessOperationsService.storeClusterConfiguration(clusterName, clusterConfig).block();

            if (storedSuccessfully == null || !storedSuccessfully) {
                String message = "Failed to store cluster configuration in Consul. It might already exist or Consul is unavailable.";
                LOG.warn(message + " Cluster: {}", clusterName);
                return new CreateClusterResponse(false, message, clusterName, consulPath);
            }

            // 3. Update bootstrap properties
            Properties props = loadProperties();
            props.setProperty("yappy.bootstrap.cluster.selectedName", clusterName);
            saveProperties(props);

            String successMessage = "Yappy cluster '" + clusterName + "' created and selected successfully. Config path: " + consulPath;
            LOG.info(successMessage);
            return new CreateClusterResponse(true, successMessage, clusterName, consulPath);

        } catch (Exception e) {
            LOG.error("Error creating new Yappy cluster '{}'", clusterName, e);
            return new CreateClusterResponse(false, "An unexpected error occurred: " + e.getMessage(), clusterName, consulPath);
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
    public SelectClusterResponse selectActiveYappyCluster(@Body @Valid SelectClusterRequest request) {
        String clusterName = request.getClusterName();
        try {
            Properties props = loadProperties();
            props.setProperty("yappy.bootstrap.cluster.selectedName", clusterName);
            saveProperties(props);

            String message = "Active Yappy cluster selected successfully: " + clusterName +
                             ". A restart may be required for the change to take full effect.";
            LOG.info(message);
            return new SelectClusterResponse(true, message);

        } catch (IOException e) {
            LOG.error("Error updating bootstrap properties file for cluster selection '{}'", clusterName, e);
            return new SelectClusterResponse(false, "Error updating bootstrap properties file: " + e.getMessage());
        } catch (Exception e) {
            LOG.error("An unexpected error occurred while selecting cluster '{}'", clusterName, e);
            return new SelectClusterResponse(false, "An unexpected error occurred: " + e.getMessage());
        }
    }

    private Properties loadProperties() {
        Properties props = new Properties();
        Path yappyDir = Paths.get(System.getProperty("user.home"), ".yappy");
        Path bootstrapFile = yappyDir.resolve(BOOTSTRAP_PROPERTIES_FILENAME);

        if (Files.exists(bootstrapFile)) {
            try (InputStream input = new FileInputStream(bootstrapFile.toFile())) {
                props.load(input);
            } catch (IOException ex) {
                LOG.error("Failed to load bootstrap properties from {}", bootstrapFile, ex);
                // For now, returns empty props
            }
        }
        return props;
    }

    private void saveProperties(Properties props) throws IOException {
        Path yappyDir = Paths.get(System.getProperty("user.home"), ".yappy");
        Path bootstrapFile = yappyDir.resolve(BOOTSTRAP_PROPERTIES_FILENAME);

        if (!Files.exists(yappyDir)) {
            Files.createDirectories(yappyDir);
        }

        try (OutputStream output = new FileOutputStream(bootstrapFile.toFile())) {
            props.store(output, "Yappy Engine Bootstrap Configuration");
        }
    }
}
