// File: yappy-consul-config/src/main/java/com/krickert/search/config/consul/validator/CustomConfigSchemaValidator.java
package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.*;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
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
    private final JsonSchemaFactory schemaFactory;

    @Inject
    public CustomConfigSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();
        if (clusterConfig == null) {
            LOG.warn("PipelineClusterConfig is null, skipping custom config schema validation.");
            return errors;
        }

        LOG.debug("Performing custom config JSON schema validation for cluster: {} using provided schema provider.", clusterConfig.clusterName());

        Map<String, PipelineModuleConfiguration> availableModules =
                (clusterConfig.pipelineModuleMap() != null && clusterConfig.pipelineModuleMap().availableModules() != null) ?
                        clusterConfig.pipelineModuleMap().availableModules() : Collections.emptyMap();

        if (clusterConfig.pipelineGraphConfig() != null && clusterConfig.pipelineGraphConfig().pipelines() != null) {
            for (PipelineConfig pipeline : clusterConfig.pipelineGraphConfig().pipelines().values()) {
                if (pipeline.pipelineSteps() != null) {
                    for (PipelineStepConfig step : pipeline.pipelineSteps().values()) {
                        validateStepConfigWithProvider(step, availableModules, errors, schemaContentProvider);
                    }
                }
            }
        }
        return errors;
    }

    private void validateStepConfigWithProvider(PipelineStepConfig step,
                                                Map<String, PipelineModuleConfiguration> availableModules,
                                                List<String> errors,
                                                Function<SchemaReference, Optional<String>> schemaContentProvider) {
        String implementationKey = getImplementationKey(step);
        PipelineModuleConfiguration moduleConfig = (implementationKey != null) ? availableModules.get(implementationKey) : null;

        SchemaReference schemaRefToUse = null;
        String schemaSourceDescription = "";

        // Determine the SchemaReference to use (from step or module)
        if (step.customConfigSchemaId() != null && !step.customConfigSchemaId().isBlank()) {
            String rawSchemaId = step.customConfigSchemaId();
            String[] parts = rawSchemaId.split(":", 2);
            String subject = parts[0];
            schemaSourceDescription = "step-defined schemaId '" + rawSchemaId + "'";
            if (parts.length == 2 && !parts[1].isBlank()) {
                try {
                    schemaRefToUse = new SchemaReference(subject, Integer.parseInt(parts[1]));
                    LOG.debug("Step '{}' uses step-defined schema: {}", step.stepName(), schemaRefToUse.toIdentifier());
                } catch (IllegalArgumentException e) {
                    errors.add(String.format("Step '%s' has invalid customConfigSchemaId '%s': %s", step.stepName(), rawSchemaId, e.getMessage()));
                    return;
                }
            } else {
                errors.add(String.format("Step '%s' has customConfigSchemaId '%s' which is missing a version or is improperly formatted for direct SchemaReference use.", step.stepName(), rawSchemaId));
                return;
            }
        } else if (moduleConfig != null && moduleConfig.customConfigSchemaReference() != null) {
            schemaRefToUse = moduleConfig.customConfigSchemaReference();
            schemaSourceDescription = "module-defined schemaRef '" + schemaRefToUse.toIdentifier() + "'";
            LOG.debug("Step '{}' (module/processor key: '{}') uses module-defined schema: {}", step.stepName(), implementationKey, schemaRefToUse.toIdentifier());
        }

        // Determine the JsonNode to validate
        JsonNode configNodeToValidate;
        if (step.customConfig() == null) {
            // No customConfig object at all for the step
            if (schemaRefToUse != null) {
                 LOG.debug("Step '{}' has an applicable schema {} but no customConfig object. Skipping schema validation for this step.",
                        step.stepName(), schemaSourceDescription);
            }
            return; // No customConfig, so nothing for this validator to do.
        } else if (step.customConfig().jsonConfig() == null || step.customConfig().jsonConfig().isNull()) {
            // customConfig object exists, but its jsonConfig field is null or a JSON null.
            // Treat as an empty JSON object for validation if a schema is applicable.
            if (schemaRefToUse != null) {
                configNodeToValidate = objectMapper.createObjectNode(); // Default to empty object {}
                LOG.debug("Step '{}' has null/JSON null jsonConfig; defaulting to empty object {{}} for schema validation against {}.",
                        step.stepName(), schemaSourceDescription);
            } else {
                // No schema applicable, and jsonConfig is effectively absent. Nothing to do.
                return;
            }
        } else {
            // Actual JsonNode provided by the step.
            configNodeToValidate = step.customConfig().jsonConfig();
        }


        if (schemaRefToUse != null) {
            // At this point, configNodeToValidate is guaranteed to be non-null if schemaRefToUse is non-null
            // (because if configNodeToValidate was going to be null due to step.customConfig() == null, we would have returned already).
            LOG.debug("Validating custom config for step '{}' (data: {}) using {} from provider",
                    step.stepName(), configNodeToValidate.toString(), schemaSourceDescription);

            Optional<String> schemaStringOpt = schemaContentProvider.apply(schemaRefToUse);

            if (schemaStringOpt.isEmpty()) {
                errors.add(String.format("Schema content for %s (referenced by step '%s') not found via provider. Cannot validate configuration.",
                        schemaSourceDescription, step.stepName()));
                LOG.warn("Schema content for {} (step '{}') not found via provider.", schemaSourceDescription, step.stepName());
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
        } else if (!configNodeToValidate.isEmpty()) { // configNodeToValidate could be an empty object from the default
            // Only warn if there's actual custom config data but no schema was found/defined for it.
            // If configNodeToValidate is an empty object (defaulted), this warning won't trigger.
            LOG.warn("Step '{}' (module/processor key: '{}') has customConfig data but no schema reference was found (either on step or module). Config will not be schema-validated by CustomConfigSchemaValidator.",
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
        return null;
    }
}