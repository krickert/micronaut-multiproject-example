// src/main/java/com/krickert/search/model/MappingExecutor.java
// Extracts execution logic from ProtoMapper.java
package com.krickert.search.model.mapper;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Executes the mapping process using parsed rules and helper components.
 * Logic extracted from the original ProtoMapper.
 */
public class MappingExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(MappingExecutor.class);

    private final RuleParser ruleParser;
    private final PathResolver pathResolver;
    private final ValueHandler valueHandler;

    public MappingExecutor(RuleParser ruleParser, PathResolver pathResolver, ValueHandler valueHandler) {
        this.ruleParser = Objects.requireNonNull(ruleParser);
        this.pathResolver = Objects.requireNonNull(pathResolver);
        this.valueHandler = Objects.requireNonNull(valueHandler);
    }


    /**
     * Internal helper to apply rules to a given builder.
     * Logic extracted from original ProtoMapper.applyRulesToBuilder.
     */
    public void applyRulesToBuilder(Message sourceMessage, Message.Builder targetBuilder, List<String> mappingRuleStrings) throws MappingException {
        List<MappingRule> rules = ruleParser.parseRules(mappingRuleStrings); // Parse rules first

        // Original logic processed assignments/appends first, then deletions.
        // RuleParser now orders them this way.
        for (MappingRule rule : rules) {
            String ruleString = rule.getOriginalRuleString(); // Use original for errors
            LOG.debug("Executing rule: {}", ruleString); // Added logging
            try {
                switch (rule.getOperation()) {
                    case ASSIGN:
                    case APPEND:
                        executeAssignmentOrAppend(sourceMessage, targetBuilder, rule);
                        break;
                    case MAP_PUT:
                        // Treat Map Put as a specific type of assignment for execution logic
                        executeMapPut(sourceMessage, targetBuilder, rule);
                        break;
                    case DELETE:
                        executeDeletion(targetBuilder, rule);
                        break;
                    default:
                        // Should not happen if parser is correct
                        throw new MappingException("Unknown rule operation: " + rule.getOperation(), ruleString);
                }
            } catch (MappingException e) {
                // Log detailed error before re-throwing
                LOG.error("Mapping failed for rule '{}': {}", ruleString, e.getMessage(), e);
                // Ensure the failed rule is attached if not already
                if (e.getFailedRule() == null || e.getFailedRule().isEmpty()) {
                    throw new MappingException(e.getMessage(), e.getCause(), ruleString);
                } else {
                    throw e; // Re-throw if rule is already attached
                }
            } catch (Exception e) {
                LOG.error("Unexpected error executing rule '{}': {}", ruleString, e.getMessage(), e);
                throw new MappingException("Unexpected error executing rule", e, ruleString);
            }
        }
    }

    /**
     * Executes a deletion rule (-target.path).
     * Logic extracted from original ProtoMapper.executeDeletion.
     */
    private void executeDeletion(Message.Builder targetBuilder, MappingRule rule) throws MappingException {
        // --- Start of code adapted from ProtoMapper.executeDeletion ---
        String targetPath = rule.getTargetPath();
        String ruleString = rule.getOriginalRuleString();
        try {
            // Resolve path for setting (deletion modifies)
            PathResolverResult targetRes = pathResolver.resolvePath(targetBuilder, targetPath, true, ruleString);

            if (targetRes.isStructKey()) {
                // Delete key from Struct
                // Get parent builder holding the struct field
                Object grandParentBuilderObj = targetRes.getGrandparentBuilder();
                Message.Builder structParentBuilder;
                if (grandParentBuilderObj == null && targetRes.getParentBuilder() instanceof Message.Builder) {
                    structParentBuilder = (Message.Builder) targetRes.getParentBuilder();
                } else if (grandParentBuilderObj instanceof Message.Builder) {
                    structParentBuilder = (Message.Builder) grandParentBuilderObj;
                } else {
                    throw new MappingException("Could not find valid parent builder for Struct field: " + targetPath, null, ruleString);
                }

                FieldDescriptor actualStructField = targetRes.getParentField();
                if (actualStructField == null || !actualStructField.getMessageType().getFullName().equals(com.google.protobuf.Struct.getDescriptor().getFullName())) {
                    throw new MappingException("Resolved parent field is not a Struct for deletion: " + targetPath, null, ruleString);
                }

                // Get Struct.Builder, remove key, set back
                // Use ValueHandler's helper which handles merging/creation
                com.google.protobuf.Struct.Builder structBuilder = valueHandler.getStructBuilder(structParentBuilder, actualStructField, ruleString);
                // Check if key exists before removing? Optional, removeFields is idempotent.
                structBuilder.removeFields(targetRes.getFinalPathPart());
                structParentBuilder.setField(actualStructField, structBuilder.build());

            } else if (targetRes.getTargetField() != null) {
                // Clear regular field
                Object parentBuilderObj = targetRes.getParentBuilder();
                if (!(parentBuilderObj instanceof Message.Builder parentMsgBuilder)) {
                    throw new MappingException("Parent object is not a Message.Builder when clearing field for path: " + targetPath, null, ruleString);
                }
                parentMsgBuilder.clearField(targetRes.getTargetField());
            } else {
                // Path resolved to something unexpected for deletion (e.g., map key - original didn't support map key deletion)
                if (targetRes.isMapKey()) {
                    LOG.warn("Map key deletion using '-' syntax is not supported by original logic. Rule ignored: {}", ruleString);
                } else {
                    throw new MappingException("Cannot resolve path to a deletable field or struct key: " + targetPath, null, ruleString);
                }
            }
        } catch (MappingException e) {
            // Original logic for ignoring field not found errors during deletion
            if (e.getMessage() != null && (e.getMessage().contains("Field not found") || e.getMessage().contains("Cannot resolve key") || e.getMessage().contains("is not set"))) {
                LOG.warn("Warning: Field/key not found or not set for deletion rule: {}", ruleString); // Use WARN
            } else {
                if (e.getFailedRule() == null) throw new MappingException(e.getMessage(), e.getCause(), ruleString);
                else throw e; // Re-throw other specific mapping exceptions
            }
        } catch (Exception e) {
            LOG.error("Error executing deletion rule: {}", ruleString, e);
            throw new MappingException("Error executing deletion rule: " + ruleString, e, ruleString);
        }
        // --- End of code adapted from ProtoMapper.executeDeletion ---
    }

    /**
     * Executes an assignment or append rule (target = source, target += source).
     * Logic extracted from original ProtoMapper.executeAssignment.
     */
    private void executeAssignmentOrAppend(Message sourceMessage, Message.Builder targetBuilder, MappingRule rule) throws MappingException {
        // --- Start of code adapted from ProtoMapper.executeAssignment ---
        String ruleString = rule.getOriginalRuleString();
        String operator = rule.getOperation() == MappingRule.Operation.ASSIGN ? "=" : "+=";
        String targetSpec = rule.getTargetPath(); // For = or +=, target path is the field itself
        String sourceSpec = rule.getSourcePath();

        // 1. Resolve source value
        Object sourceValue = valueHandler.getValue(sourceMessage, sourceSpec, ruleString); // Use ValueHandler

        // 2. Resolve target path and set value (using ValueHandler)
        valueHandler.setValue(targetBuilder, targetSpec, sourceValue, operator, ruleString);
        // --- End of code adapted from ProtoMapper.executeAssignment ---
    }

    /**
     * Executes a map put rule (target["key"] = source).
     * Logic extracted from original ProtoMapper.executeAssignment.
     */
    private void executeMapPut(Message sourceMessage, Message.Builder targetBuilder, MappingRule rule) throws MappingException {
        // --- Start of code adapted from ProtoMapper.executeAssignment for map put ---
        String ruleString = rule.getOriginalRuleString();
        String operator = "[]="; // Special operator used internally by ValueHandler.setValue
        String targetSpec = rule.getFullTargetPathSpecification(); // e.g., mapField["key"]
        String sourceSpec = rule.getSourcePath();

        // 1. Resolve source value
        Object sourceValue = valueHandler.getValue(sourceMessage, sourceSpec, ruleString); // Use ValueHandler

        // 2. Resolve target path and set value (using ValueHandler)
        // ValueHandler.setValue needs to handle the map key setting based on targetSpec format and operator.
        valueHandler.setValue(targetBuilder, targetSpec, sourceValue, operator, ruleString);
        // --- End of code adapted from ProtoMapper.executeAssignment for map put ---
    }
}