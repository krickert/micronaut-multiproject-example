package com.krickert.search.config.consul;

import com.fasterxml.jackson.databind.ObjectMapper; // Keep for CustomConfigSchemaValidator if it's injected here
import com.krickert.search.config.consul.validator.ClusterValidationRule; // The new rule interface
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.networknt.schema.JsonSchemaFactory; // Keep for CustomConfigSchemaValidator
import com.networknt.schema.SpecVersion;    // Keep for CustomConfigSchemaValidator


import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class DefaultConfigurationValidator implements ConfigurationValidator { // Implements the original interface

    private static final Logger LOG = LoggerFactory.getLogger(DefaultConfigurationValidator.class);
    private final List<ClusterValidationRule> validationRules;
    // ObjectMapper and JsonSchemaFactory might be injected into specific rules now,
    // or passed to them if they are not beans themselves.
    // For simplicity, let's assume they are injected into rules that need them.

    @Inject
    public DefaultConfigurationValidator(List<ClusterValidationRule> validationRules) {
        this.validationRules = validationRules;
        LOG.info("DefaultConfigurationValidator initialized with {} validation rules.", validationRules.size());
        validationRules.forEach(rule -> LOG.debug("Registered validation rule: {}", rule.getClass().getSimpleName()));
    }

    @Override
    public ValidationResult validate(
            PipelineClusterConfig configToValidate,
            Function<SchemaReference, Optional<String>> schemaContentProvider) {

        if (configToValidate == null) {
            return ValidationResult.invalid("PipelineClusterConfig cannot be null.");
        }

        LOG.info("Starting comprehensive validation for cluster: {}", configToValidate.clusterName());
        List<String> allErrors = new ArrayList<>();

        for (ClusterValidationRule rule : validationRules) {
            LOG.debug("Applying validation rule: {}", rule.getClass().getSimpleName());
            try {
                List<String> ruleErrors = rule.validate(configToValidate, schemaContentProvider);
                if (ruleErrors != null && !ruleErrors.isEmpty()) {
                    allErrors.addAll(ruleErrors);
                    LOG.warn("Validation rule {} found errors for cluster {}: {}",
                            rule.getClass().getSimpleName(), configToValidate.clusterName(), ruleErrors.size());
                }
            } catch (Exception e) {
                String errorMessage = String.format("Exception while applying validation rule %s to cluster %s: %s",
                        rule.getClass().getSimpleName(), configToValidate.clusterName(), e.getMessage());
                LOG.error(errorMessage, e);
                allErrors.add(errorMessage);
            }
        }

        if (allErrors.isEmpty()) {
            LOG.info("Comprehensive validation successful for cluster: {}", configToValidate.clusterName());
            return ValidationResult.valid();
        } else {
            LOG.warn("Comprehensive validation failed for cluster: {}. Total errors: {}", configToValidate.clusterName(), allErrors.size());
            // Logging each error individually might be too verbose here if already logged by rule,
            // but can be useful for a summary.
            // allErrors.forEach(error -> LOG.warn("Validation Error: {}", error));
            return ValidationResult.invalid(allErrors);
        }
    }
}