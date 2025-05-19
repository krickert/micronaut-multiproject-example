package com.krickert.search.config.consul;

import com.krickert.search.config.consul.validator.ClusterValidationRule;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class DefaultConfigurationValidator implements ConfigurationValidator {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultConfigurationValidator.class);
    private final List<ClusterValidationRule> validationRules;

    @Inject
    public DefaultConfigurationValidator(List<ClusterValidationRule> validationRules) {
        // Micronaut will inject all beans implementing ClusterValidationRule
        // You might want to sort them if execution order matters, or define order using @Order
        this.validationRules = (validationRules == null) ? Collections.emptyList() : validationRules;
        LOG.info("DefaultConfigurationValidator initialized with {} validation rules.", this.validationRules.size());
        this.validationRules.forEach(rule -> LOG.debug("Registered validation rule: {}", rule.getClass().getSimpleName()));
    }

    @Override
    public ValidationResult validate(
            PipelineClusterConfig configToValidate,
            Function<SchemaReference, Optional<String>> schemaContentProvider) {

        if (configToValidate == null) {
            // This check could also be a dedicated "NullConfigValidator" rule if you want to be extremely modular.
            return ValidationResult.invalid("PipelineClusterConfig cannot be null.");
        }

        // Cluster name validation can also be part of a rule or basic check here.
        if (configToValidate.clusterName() == null || configToValidate.clusterName().isBlank()) {
            return ValidationResult.invalid("PipelineClusterConfig clusterName cannot be null or blank.");
        }


        LOG.info("Starting comprehensive validation for cluster: {}", configToValidate.clusterName());
        List<String> allErrors = new ArrayList<>();

        if (validationRules.isEmpty()) {
            LOG.warn("No validation rules configured for DefaultConfigurationValidator for cluster: {}. Consider this a passthrough.", configToValidate.clusterName());
            return ValidationResult.valid(); // Or an error/warning based on policy if no rules is bad
        }

        for (ClusterValidationRule rule : validationRules) {
            String ruleName = rule.getClass().getSimpleName();
            LOG.debug("Applying validation rule: {} for cluster: {}", ruleName, configToValidate.clusterName());
            try {
                List<String> ruleErrors = rule.validate(configToValidate, schemaContentProvider);
                if (ruleErrors != null && !ruleErrors.isEmpty()) {
                    allErrors.addAll(ruleErrors);
                    LOG.warn("Validation rule {} found {} error(s) for cluster {}: First error: '{}'",
                            ruleName, ruleErrors.size(), configToValidate.clusterName(), ruleErrors.get(0));
                }
            } catch (Exception e) {
                String errorMessage = String.format("Exception while applying validation rule %s to cluster %s: %s",
                        ruleName, configToValidate.clusterName(), e.getMessage());
                LOG.error(errorMessage, e);
                allErrors.add(errorMessage);
            }
        }

        if (allErrors.isEmpty()) {
            LOG.info("Comprehensive validation successful for cluster: {}", configToValidate.clusterName());
            return ValidationResult.valid();
        } else {
            LOG.warn("Comprehensive validation failed for cluster: {}. Total errors found: {}. First few errors: {}",
                    configToValidate.clusterName(),
                    allErrors.size(),
                    allErrors.stream().limit(3).collect(Collectors.toList()) // Log first few errors
            );
            return ValidationResult.invalid(allErrors);
        }
    }
}