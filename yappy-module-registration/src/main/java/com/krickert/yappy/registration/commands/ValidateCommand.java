package com.krickert.yappy.registration.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.ValidationResult;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.krickert.search.config.pipeline.model.*;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.krickert.yappy.registration.YappyRegistrationCli;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Command to validate configuration files using yappy-consul-config validation.
 */
@Slf4j
@Command(
    name = "validate",
    description = "Validate configuration files and JSON schemas",
    mixinStandardHelpOptions = true
)
public class ValidateCommand implements Callable<Integer> {
    
    @ParentCommand
    private YappyRegistrationCli parent;
    
    @Parameters(
        index = "0",
        description = "Configuration file to validate"
    )
    private File configFile;
    
    @Option(
        names = {"-t", "--type"},
        description = "Configuration type: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})",
        defaultValue = "CLUSTER"
    )
    private ConfigType configType;
    
    @Option(
        names = {"--schema-id"},
        description = "Schema ID for validating custom JSON config in step configurations"
    )
    private String schemaId;
    
    @Inject
    private ApplicationContext applicationContext;
    
    @Inject
    private ObjectMapper objectMapper;
    
    public enum ConfigType {
        CLUSTER,
        PIPELINE,
        STEP,
        MODULE,
        SCHEMA
    }
    
    @Override
    public Integer call() {
        try {
            if (!configFile.exists() || !configFile.isFile()) {
                log.error("Configuration file does not exist: {}", configFile.getAbsolutePath());
                return 1;
            }
            
            String content = Files.readString(configFile.toPath());
            
            // Get the centralized validation service from yappy-consul-config
            SchemaValidationService validationService = applicationContext.getBean(SchemaValidationService.class);
            
            switch (configType) {
                case CLUSTER:
                    return validateClusterConfig(content, validationService);
                    
                case PIPELINE:
                    return validatePipelineConfig(content);
                    
                case STEP:
                    return validateStepConfig(content, validationService);
                    
                case MODULE:
                    return validateModuleConfig(content);
                    
                case SCHEMA:
                    return validateJsonSchema(content, validationService);
                    
                default:
                    log.error("Unknown configuration type: {}", configType);
                    return 1;
            }
            
        } catch (Exception e) {
            log.error("Failed to validate file: {}", e.getMessage());
            if (parent.verbose) {
                log.error("Stack trace:", e);
            }
            return 1;
        }
    }
    
    private Integer validateClusterConfig(String content, SchemaValidationService validationService) {
        try {
            // Validate cluster name is provided
            if (parent.clusterName == null || parent.clusterName.isBlank()) {
                log.error("Cluster name is required for cluster validation. Use -c or --cluster option.");
                return 1;
            }
            
            log.info("Validating cluster configuration for cluster '{}'...", parent.clusterName);
            
            PipelineClusterConfig clusterConfig = objectMapper.readValue(content, PipelineClusterConfig.class);
            
            // Use the centralized validation service
            // Validate cluster configuration as JSON
            ValidationResult result = validationService.validatePipelineClusterConfig(content).block();
            
            if (result.isValid()) {
                log.info("✓ Cluster configuration is valid!");
                
                // Show some details about the configuration
                if (parent.verbose) {
                    log.info("Cluster details:");
                    log.info("  - Cluster name: {}", clusterConfig.clusterName());
                    if (clusterConfig.pipelineGraphConfig() != null && 
                        clusterConfig.pipelineGraphConfig().pipelines() != null) {
                        log.info("  - Pipelines: {}", clusterConfig.pipelineGraphConfig().pipelines().size());
                    }
                    if (clusterConfig.pipelineModuleMap() != null && 
                        clusterConfig.pipelineModuleMap().availableModules() != null) {
                        log.info("  - Available modules: {}", clusterConfig.pipelineModuleMap().availableModules().size());
                    }
                }
                
                return 0;
            } else {
                log.error("✗ Cluster configuration validation failed:");
                result.errors().forEach(error -> log.error("  - {}", error));
                return 1;
            }
            
        } catch (Exception e) {
            log.error("Failed to parse cluster configuration: {}", e.getMessage());
            return 1;
        }
    }
    
    private Integer validatePipelineConfig(String content) {
        try {
            log.info("Validating pipeline configuration...");
            
            PipelineConfig pipelineConfig = objectMapper.readValue(content, PipelineConfig.class);
            
            // Basic structural validation
            if (pipelineConfig.name() == null || pipelineConfig.name().isBlank()) {
                log.error("Pipeline name is required");
                return 1;
            }
            
            log.info("✓ Pipeline configuration is structurally valid!");
            log.info("  - Pipeline name: {}", pipelineConfig.name());
            
            if (pipelineConfig.pipelineSteps() != null) {
                log.info("  - Steps: {}", pipelineConfig.pipelineSteps().size());
                
                if (parent.verbose) {
                    pipelineConfig.pipelineSteps().forEach((stepId, step) -> {
                        log.info("    - Step '{}': {}", stepId, step.stepType());
                    });
                }
            }
            
            return 0;
            
        } catch (Exception e) {
            log.error("Failed to parse pipeline configuration: {}", e.getMessage());
            return 1;
        }
    }
    
    private Integer validateStepConfig(String content, SchemaValidationService validationService) {
        try {
            log.info("Validating step configuration...");
            
            PipelineStepConfig stepConfig = objectMapper.readValue(content, PipelineStepConfig.class);
            
            // Basic structural validation
            if (stepConfig.stepName() == null || stepConfig.stepName().isBlank()) {
                log.error("Step ID is required");
                return 1;
            }
            
            log.info("✓ Step configuration is structurally valid!");
            log.info("  - Step Name: {}", stepConfig.stepName());
            log.info("  - Type: {}", stepConfig.stepType());
            
            // If custom config exists and schema ID is provided, validate it
            if (stepConfig.customConfig() != null && schemaId != null) {
                log.info("Validating custom configuration against schema '{}'...", schemaId);
                
                // Validate custom config against schema
                boolean customConfigValid = true;
                
                try {
                    // Get the schema from a file or registry
                    File schemaFile = new File(schemaId);
                    if (schemaFile.exists()) {
                        String schemaContent = Files.readString(schemaFile.toPath());
                        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
                        JsonSchema schema = factory.getSchema(objectMapper.readTree(schemaContent));
                        
                        // Convert config params to JSON for validation
                        JsonNode configNode = objectMapper.valueToTree(stepConfig.customConfig().configParams());
                        Set<ValidationMessage> validationErrors = schema.validate(configNode);
                        
                        customConfigValid = validationErrors.isEmpty();
                        if (!customConfigValid) {
                            log.error("Custom config validation errors:");
                            validationErrors.forEach(error -> log.error("  - {}", error.getMessage()));
                        }
                    } else {
                        log.warn("Schema file not found: {}", schemaId);
                    }
                } catch (Exception e) {
                    log.error("Error validating custom config: {}", e.getMessage());
                    customConfigValid = false;
                }
                
                if (customConfigValid) {
                    log.info("✓ Custom configuration validates against schema!");
                } else {
                    log.error("✗ Custom configuration validation failed against schema");
                    return 1;
                }
            } else if (stepConfig.customConfig() != null && schemaId == null) {
                log.warn("Step has custom configuration but no schema ID provided for validation");
            }
            
            return 0;
            
        } catch (Exception e) {
            log.error("Failed to parse step configuration: {}", e.getMessage());
            return 1;
        }
    }
    
    private Integer validateModuleConfig(String content) {
        try {
            log.info("Validating module configuration...");
            
            PipelineModuleConfiguration moduleConfig = objectMapper.readValue(content, PipelineModuleConfiguration.class);
            
            // Validate required fields
            if (moduleConfig.implementationId() == null || moduleConfig.implementationId().isBlank()) {
                log.error("Implementation ID is required");
                return 1;
            }
            
            log.info("✓ Module configuration is valid!");
            log.info("  - Implementation ID: {}", moduleConfig.implementationId());
            log.info("  - Module name: {}", moduleConfig.implementationName());
            
            // Module instances are not part of the current model
            
            // Validate JSON schema if present
            if (moduleConfig.customConfigSchemaReference() != null) {
                log.info("Module has custom config schema reference: {}", moduleConfig.customConfigSchemaReference());
                // TODO: Implement schema validation via schema registry
            }
            
            return 0;
            
        } catch (Exception e) {
            log.error("Failed to parse module configuration: {}", e.getMessage());
            return 1;
        }
    }
    
    private Integer validateJsonSchema(String content, SchemaValidationService validationService) {
        try {
            log.info("Validating JSON schema...");
            
            // Use the centralized validation service to check if it's valid JSON
            // For schema validation, we need to parse it as JSON and validate its structure
            Boolean isValid = validationService.isValidJson(content).block();
            
            if (!isValid) {
                log.error("✗ Invalid JSON syntax");
                return 1;
            }
            
            // Try to parse as a JSON schema
            try {
                JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
                JsonSchema schema = factory.getSchema(objectMapper.readTree(content));
                log.info("✓ JSON schema is valid!");
                
                // Parse and show some details
                if (parent.verbose) {
                    try {
                        var schemaNode = objectMapper.readTree(content);
                        if (schemaNode.has("title")) {
                            log.info("  - Title: {}", schemaNode.get("title").asText());
                        }
                        if (schemaNode.has("description")) {
                            log.info("  - Description: {}", schemaNode.get("description").asText());
                        }
                        if (schemaNode.has("$schema")) {
                            log.info("  - Schema version: {}", schemaNode.get("$schema").asText());
                        }
                    } catch (Exception e) {
                        // Ignore parsing errors for details
                    }
                }
                
                return 0;
            } catch (Exception e) {
                log.error("✗ Invalid JSON Schema: {}", e.getMessage());
                return 1;
            }
            
        } catch (Exception e) {
            log.error("Failed to validate JSON schema: {}", e.getMessage());
            return 1;
        }
    }
}