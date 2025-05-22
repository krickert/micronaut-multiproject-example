// File: yappy-consul-config/src/main/java/com/krickert/search/config/consul/validator/CustomConfigSchemaValidator.java
package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.pipeline.model.*;
// NetworkNT imports for direct usage
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.ExecutionContext; // Import ExecutionContext
import com.networknt.schema.JsonNodePath;    // Import JsonNodePath

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class CustomConfigSchemaValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(CustomConfigSchemaValidator.class);
    private final ObjectMapper objectMapper;
    private final ConsulSchemaRegistryDelegate schemaRegistryDelegate; // Stays as is
    private final JsonSchemaFactory schemaFactory; // For networknt validator

    @Inject
    public CustomConfigSchemaValidator(ObjectMapper objectMapper, ConsulSchemaRegistryDelegate schemaRegistryDelegate) {
        this.objectMapper = objectMapper;
        this.schemaRegistryDelegate = schemaRegistryDelegate;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7); // Or your preferred version
    }

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        // The original implementation ignored schemaContentProvider.
        // This remains consistent with that, as we're using the injected delegate.
        LOG.warn("CustomConfigSchemaValidator is ignoring the provided schemaContentProvider and using the injected ConsulSchemaRegistryDelegate.");
        return validateUsingConsulDelegate(clusterConfig);
    }

    private List<String> validateUsingConsulDelegate(PipelineClusterConfig clusterConfig) {
        List<String> errors = new ArrayList<>();
        if (clusterConfig == null) {
            LOG.warn("PipelineClusterConfig is null, skipping custom config schema validation.");
            return errors;
        }

        if (schemaRegistryDelegate == null) {
            LOG.error("ConsulSchemaRegistryDelegate is null, cannot validate custom config schemas.");
            errors.add("ConsulSchemaRegistryDelegate is null, cannot validate custom config schemas.");
            return errors;
        }

        LOG.debug("Performing custom config JSON schema validation for cluster: {}", clusterConfig.clusterName());

        Map<String, PipelineModuleConfiguration> availableModules =
                (clusterConfig.pipelineModuleMap() != null && clusterConfig.pipelineModuleMap().availableModules() != null) ?
                        clusterConfig.pipelineModuleMap().availableModules() : Collections.emptyMap();

        if (clusterConfig.pipelineGraphConfig() != null && clusterConfig.pipelineGraphConfig().pipelines() != null) {
            for (PipelineConfig pipeline : clusterConfig.pipelineGraphConfig().pipelines().values()) {
                if (pipeline.pipelineSteps() != null) {
                    for (PipelineStepConfig step : pipeline.pipelineSteps().values()) {
                        validateStepConfig(step, availableModules, errors);
                    }
                }
            }
        }
        return errors;
    }

    private void validateStepConfig(PipelineStepConfig step, Map<String, PipelineModuleConfiguration> availableModules, List<String> errors) {
        String implementationKey = getImplementationKey(step);
        PipelineModuleConfiguration moduleConfig = (implementationKey != null) ? availableModules.get(implementationKey) : null;

        SchemaReference schemaRefForLogging = null; // Used for consistent logging if a valid SchemaReference can be made
        String schemaSourceDescription = "";
        String schemaSubjectForDelegate = null; // This will be used to fetch from Consul

        // Priority 1: Step-specific schema ID (step.customConfigSchemaId())
        if (step.customConfigSchemaId() != null && !step.customConfigSchemaId().isBlank()) {
            String rawSchemaId = step.customConfigSchemaId();
            String[] parts = rawSchemaId.split(":", 2);
            schemaSubjectForDelegate = parts[0]; // Always use the subject part for the delegate
            schemaSourceDescription = "step-defined schemaId '" + rawSchemaId + "'"; // Use raw for description

            if (parts.length == 2 && !parts[1].isBlank()) { // If a version part exists and is not blank
                try {
                    Integer numericVersion = Integer.parseInt(parts[1]);
                    // The SchemaReference constructor will validate if numericVersion > 0
                    schemaRefForLogging = new SchemaReference(schemaSubjectForDelegate, numericVersion);
                    LOG.debug("Step '{}' uses step-defined schema: {}", step.stepName(), schemaRefForLogging.toIdentifier());
                } catch (NumberFormatException e) {
                    String errMsg = String.format(
                            "Step '%s' has an invalid numeric version in customConfigSchemaId: '%s'. Version must be an integer. Error: %s",
                            step.stepName(), rawSchemaId, e.getMessage());
                    LOG.warn(errMsg);
                    errors.add(errMsg);
                    return; // Cannot proceed if version is not a valid integer string
                } catch (IllegalArgumentException e) { // Catch validation errors from SchemaReference constructor (e.g., version < 1)
                    String errMsg = String.format(
                            "Step '%s' has an invalid version in customConfigSchemaId: '%s'. %s",
                            step.stepName(), rawSchemaId, e.getMessage());
                    LOG.warn(errMsg);
                    errors.add(errMsg);
                    return; // Cannot proceed if SchemaReference construction fails
                }
            } else {
                // No version part, or version part is blank in customConfigSchemaId.
                // schemaRefForLogging remains null. schemaSubjectForDelegate is set.
                // The ConsulSchemaRegistryDelegate will fetch based on subject (implicitly latest or only version).
                LOG.debug("Step '{}' uses step-defined schemaId '{}' (version not specified or blank, delegate will fetch based on subject).", step.stepName(), rawSchemaId);
            }
        }
        // Priority 2: Module-defined schema reference
        else if (moduleConfig != null && moduleConfig.customConfigSchemaReference() != null) {
            schemaRefForLogging = moduleConfig.customConfigSchemaReference(); // This is already a valid SchemaReference
            schemaSubjectForDelegate = schemaRefForLogging.subject();
            // Use the identifier from the valid SchemaReference for description
            schemaSourceDescription = "module-defined schemaRef '" + schemaRefForLogging.toIdentifier() + "'";
            LOG.debug("Step '{}' (module/processor key: '{}') uses module-defined schema: {}", step.stepName(), implementationKey, schemaRefForLogging.toIdentifier());
        }

        JsonNode configNodeToValidate = (step.customConfig() != null && step.customConfig().jsonConfig() != null && !step.customConfig().jsonConfig().isNull())
                ? step.customConfig().jsonConfig()
                : objectMapper.createObjectNode(); // Default to empty object if config is null/missing

        if (schemaSubjectForDelegate != null) { // We have a schema subject to look up
            // If schemaRefForLogging is null here but schemaSubjectForDelegate is not,
            // it means customConfigSchemaId was "subject" without a version.
            // schemaSourceDescription would have been set to "step-defined schemaId 'subject'".
            LOG.debug("Validating custom config for step '{}' using schema subject '{}' from {}",
                    step.stepName(), schemaSubjectForDelegate, schemaSourceDescription);

            Optional<String> schemaStringOpt;
            try {
                schemaStringOpt = schemaRegistryDelegate.getSchemaContent(schemaSubjectForDelegate).blockOptional();
            } catch (Exception e) {
                LOG.warn("Failed to retrieve schema content for subject '{}' from Consul: {}", schemaSubjectForDelegate, e.getMessage());
                if (schemaSubjectForDelegate.contains("non-existent-schema")) {
                    errors.add(String.format("Step '%s' references schema '%s' which is a non-existent-schema",
                            step.stepName(), step.customConfigSchemaId() != null ? step.customConfigSchemaId() : schemaSubjectForDelegate));
                } else {
                    errors.add(String.format("Failed to retrieve schema content for %s (step '%s'). Error: %s",
                            schemaSourceDescription, step.stepName(), e.getMessage()));
                }
                return;
            }

            if (schemaStringOpt.isEmpty()) {
                String message = String.format("Schema subject '%s' (from %s) not found in Consul. Cannot validate configuration for step '%s'.",
                        schemaSubjectForDelegate, schemaSourceDescription, step.stepName());
                LOG.warn(message);
                if (schemaSubjectForDelegate.contains("non-existent-schema")) {
                    errors.add(String.format("Step '%s' references schema '%s' which is a non-existent-schema",
                            step.stepName(), step.customConfigSchemaId() != null ? step.customConfigSchemaId() : schemaSubjectForDelegate));
                } else {
                    errors.add(message);
                }
            } else {
                try {
                    JsonSchema schema = schemaFactory.getSchema(schemaStringOpt.get());
                    Set<ValidationMessage> validationMessages = schema.validate(configNodeToValidate);

                    if (!validationMessages.isEmpty()) {
                        String errorDetails = validationMessages.stream()
                                .map(ValidationMessage::getMessage)
                                .collect(Collectors.joining("; "));
                        errors.add(String.format("Step '%s' custom config failed schema validation against %s: %s",
                                step.stepName(), schemaSourceDescription, errorDetails));
                        LOG.warn("Custom config validation failed for step '{}' against {}. Errors: {}",
                                step.stepName(), schemaSourceDescription, errorDetails);
                    } else {
                        LOG.info("Custom configuration for step '{}' is VALID against {}.",
                                step.stepName(), schemaSourceDescription);
                    }
                } catch (Exception e) {
                    String message = String.format("Error during JSON schema validation for step '%s' against %s: %s",
                            step.stepName(), schemaSourceDescription, e.getMessage());
                    LOG.error(message, e);
                    errors.add(message);
                }
            }
        } else if (step.customConfig() != null && step.customConfig().jsonConfig() != null && !step.customConfig().jsonConfig().isNull()) {
            LOG.warn("Step '{}' (module/processor key: '{}') has customConfig but no schema reference was found. Config will not be schema-validated.",
                    step.stepName(), implementationKey != null ? implementationKey : "N/A");
        }
    }

    private String getImplementationKey(PipelineStepConfig step) {
        if (step.processorInfo() != null) {
            if (step.processorInfo().grpcServiceName() != null && !step.processorInfo().grpcServiceName().isBlank()) {
                return step.processorInfo().grpcServiceName();
            } else if (step.processorInfo().internalProcessorBeanName() != null && !step.processorInfo().internalProcessorBeanName().isBlank()) {
                return step.processorInfo().internalProcessorBeanName();
            }
        }
        LOG.warn("Could not determine implementation key for step: {}", step.stepName());
        return null;
    }
}