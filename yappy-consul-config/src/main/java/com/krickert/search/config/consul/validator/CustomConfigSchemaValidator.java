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
                        PipelineModuleConfiguration moduleConfig = availableModules.get(step.pipelineImplementationId());

                        if (moduleConfig != null && step.customConfig() != null && moduleConfig.customConfigSchemaReference() != null) {
                            SchemaReference schemaRef = moduleConfig.customConfigSchemaReference();
                            Optional<String> schemaJsonOpt = schemaContentProvider.apply(schemaRef);

                            if (schemaJsonOpt.isPresent()) {
                                try {
                                    JsonSchema schema = schemaFactory.getSchema(schemaJsonOpt.get());
                                    JsonNode configNode = objectMapper.readTree(step.customConfig().jsonConfig());
                                    Set<ValidationMessage> schemaErrors = schema.validate(configNode);
                                    if (!schemaErrors.isEmpty()) {
                                        errors.add(String.format("Step '%s' custom config failed schema validation (%s): %s",
                                                step.pipelineStepId(), schemaRef,
                                                schemaErrors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "))));
                                    }
                                } catch (Exception e) {
                                    errors.add(String.format("Error validating custom config for step '%s' against schema %s: %s",
                                            step.pipelineStepId(), schemaRef, e.getMessage()));
                                }
                            } else {
                                // This error is more about schema availability, which DynamicConfigurationManager also checks,
                                // but good to note if a schema is referenced but not provided.
                                errors.add(String.format("Schema content for %s (step '%s') not found by provider.",
                                        schemaRef, step.pipelineStepId()));
                            }
                        }
                    }
                }
            }
        }
        return errors;
    }
}