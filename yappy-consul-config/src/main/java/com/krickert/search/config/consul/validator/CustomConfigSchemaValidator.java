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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class CustomConfigSchemaValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(CustomConfigSchemaValidator.class);
    private final ObjectMapper objectMapper; // objectMapper might still be needed if schemaJsonOpt.get() is a string to be parsed, or for other reasons.
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

        LOG.debug("Performing custom config JSON schema validation for cluster: {}", clusterConfig.clusterName());

        Map<String, PipelineModuleConfiguration> availableModules =
                (clusterConfig.pipelineModuleMap() != null && clusterConfig.pipelineModuleMap().availableModules() != null) ?
                clusterConfig.pipelineModuleMap().availableModules() : Collections.emptyMap();

        if (clusterConfig.pipelineGraphConfig() != null && clusterConfig.pipelineGraphConfig().pipelines() != null) {
            for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
                PipelineConfig pipeline = pipelineEntry.getValue();
                if (pipeline.pipelineSteps() != null) {
                    for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                        PipelineStepConfig step = stepEntry.getValue();

                        // Determine the implementation key from ProcessorInfo
                        String implementationKey = null;
                        if (step.processorInfo() != null) { // processorInfo is @NotNull
                            if (step.processorInfo().grpcServiceName() != null && !step.processorInfo().grpcServiceName().isBlank()) {
                                implementationKey = step.processorInfo().grpcServiceName();
                            } else if (step.processorInfo().internalProcessorBeanName() != null && !step.processorInfo().internalProcessorBeanName().isBlank()) {
                                implementationKey = step.processorInfo().internalProcessorBeanName();
                            }
                        }

                        PipelineModuleConfiguration moduleConfig = (implementationKey != null) ? availableModules.get(implementationKey) : null;

                        if (moduleConfig != null && step.customConfig() != null && moduleConfig.customConfigSchemaReference() != null) {
                            SchemaReference schemaRef = moduleConfig.customConfigSchemaReference();
                            Optional<String> schemaJsonOpt = schemaContentProvider.apply(schemaRef);

                            if (schemaJsonOpt.isPresent()) {
                                try {
                                    JsonSchema schema = schemaFactory.getSchema(schemaJsonOpt.get());
                                    
                                    // Corrected: step.customConfig().jsonConfig() now returns JsonNode directly
                                    // Ensure customConfig and its jsonConfig are not null
                                    JsonNode configNode = null;
                                    if (step.customConfig() != null && step.customConfig().jsonConfig() != null) {
                                        configNode = step.customConfig().jsonConfig();
                                    } else if (step.customConfig() != null && step.customConfig().jsonConfig() == null && moduleConfig.customConfigSchemaReference() != null) {
                                         // If customConfig object exists but jsonConfig node is null, and a schema is expected,
                                         // it might imply an empty config against a schema.
                                         // We can treat it as an empty JSON object for validation purposes if that's the desired behavior.
                                         // Or, if jsonConfig() can't be null per JsonConfigOptions record, this branch is less likely.
                                         // Given `JsonConfigOptions(@JsonProperty("jsonConfig") JsonNode jsonConfig, ...)`
                                         // jsonConfig can be null if not provided in JSON.
                                         // Let's assume if it's null, it's like an empty object for schema validation.
                                         // Or, if schema requires fields, this will fail validation appropriately.
                                         // An explicit empty node might be `objectMapper.createObjectNode()` or `MissingNode.getInstance()`
                                         // For now, if it's null and a schema expects something, it will (correctly) fail.
                                         // If a schema allows an empty document (e.g. {}), a null might not validate correctly against it.
                                         // Most JSON schema validators would expect a JsonNode, even if it's an empty ObjectNode.
                                         // Let's default to an empty ObjectNode if customConfig.jsonConfig() is null but customConfig itself is not.
                                         LOG.debug("Custom config for step '{}' is present but its jsonConfig is null. " +
                                                   "Validating against schema with an empty JSON object.", step.stepName());
                                         configNode = objectMapper.createObjectNode();
                                    }


                                    if (configNode != null) {
                                        Set<ValidationMessage> schemaErrors = schema.validate(configNode);
                                        if (!schemaErrors.isEmpty()) {
                                            errors.add(String.format("Step '%s' custom config failed schema validation (%s): %s",
                                                    step.stepName(), // Changed from pipelineStepId()
                                                    schemaRef,
                                                    schemaErrors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "))));
                                        }
                                    } else if (moduleConfig.customConfigSchemaReference() != null) {
                                        // If module expects a schema but configNode is null (e.g. customConfig itself was null)
                                        // This might be an error if the schema doesn't allow a "null" configuration.
                                        // For simplicity, let's assume this scenario means the config is effectively missing.
                                        // The schema.validate(null) behavior might vary.
                                        // It might be better to add an error if customConfig is null but a schema is defined.
                                        errors.add(String.format("Step '%s' has a customConfigSchemaReference (%s) but its customConfig or jsonConfig is missing/null.",
                                                step.stepName(), schemaRef));
                                    }
                                } catch (Exception e) {
                                    errors.add(String.format("Error validating custom config for step '%s' against schema %s: %s",
                                            step.stepName(), // Changed from pipelineStepId()
                                            schemaRef, e.getMessage()));
                                }
                            } else {
                                errors.add(String.format("Schema content for %s (step '%s') not found by provider.",
                                        schemaRef, step.stepName())); // Changed from pipelineStepId()
                            }
                        } else if (step.customConfig() != null && step.customConfig().jsonConfig() != null && moduleConfig == null) {
                            // Case: Custom config exists, but no moduleConfig found to provide a schema.
                            // This could be an error if all custom configs are expected to have schemas.
                            LOG.warn("Step '{}' has customConfig but no corresponding PipelineModuleConfiguration (key: '{}') was found to provide a schema.",
                                    step.stepName(), implementationKey);
                            // Optionally add an error:
                            // errors.add(String.format("Step '%s' has customConfig but no schema is defined for its module/implementation '%s'.",
                            // step.stepName(), implementationKey));
                        } else if (step.customConfig() != null && step.customConfig().jsonConfig() != null && moduleConfig.customConfigSchemaReference() == null) {
                            // Case: Custom config exists, moduleConfig exists, but it doesn't reference a schema.
                            LOG.warn("Step '{}' (module '{}') has customConfig but the module does not define a customConfigSchemaReference.",
                                    step.stepName(), implementationKey);
                            // Optionally add an error:
                            // errors.add(String.format("Step '%s' (module '%s') has customConfig but its module does not define a customConfigSchemaReference.",
                            // step.stepName(), implementationKey));
                        }
                    }
                }
            }
        }
        return errors;
    }
}