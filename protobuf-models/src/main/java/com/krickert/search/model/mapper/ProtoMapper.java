// src/main/java/com/krickert/search/model/ProtoMapper.java (Refactored Entry Point)
package com.krickert.search.model.mapper;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.Descriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Dynamically maps fields between Protocol Buffer messages based on string rules.
 * Acts as an entry point, delegating logic to helper classes.
 */
public class ProtoMapper {
    private static final Logger LOG = LoggerFactory.getLogger(ProtoMapper.class);

    private final RuleParser ruleParser;
    private final PathResolver pathResolver;
    private final ValueHandler valueHandler;
    private final MappingExecutor mappingExecutor;


    public ProtoMapper() {
        // Instantiate default components
        this.ruleParser = new RuleParser();
        this.pathResolver = new PathResolver();
         // ValueHandler needs PathResolver for some helpers, but PathResolver doesn't strictly need ValueHandler in this structure.
        this.valueHandler = new ValueHandler(this.pathResolver); // Pass dependency if needed by ValueHandler internally
        this.mappingExecutor = new MappingExecutor(this.ruleParser, this.pathResolver, this.valueHandler);
    }

    // Constructor for injecting dependencies (useful for testing components if needed later)
    public ProtoMapper(RuleParser ruleParser, PathResolver pathResolver, ValueHandler valueHandler, MappingExecutor mappingExecutor) {
        this.ruleParser = Objects.requireNonNull(ruleParser);
        this.pathResolver = Objects.requireNonNull(pathResolver);
        this.valueHandler = Objects.requireNonNull(valueHandler);
        this.mappingExecutor = Objects.requireNonNull(mappingExecutor);
    }

    /**
     * Applies mapping rules to transform a source message into a *new* target message type.
     *
     * @param sourceMessage The source protobuf message instance.
     * @param targetDescriptor The Descriptor for the target message type.
     * @param mappingRuleStrings A list of strings defining the mapping operations.
     * @return A new Message instance of the target type, populated according to the rules.
     * @throws MappingException If any error occurs during parsing or mapping.
     */
    public Message map(Message sourceMessage,
                       Descriptor targetDescriptor,
                       List<String> mappingRuleStrings) throws MappingException {

        DynamicMessage.Builder targetBuilder = DynamicMessage.newBuilder(targetDescriptor);
        // Delegate execution to MappingExecutor
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, mappingRuleStrings);

        try {
            return targetBuilder.build();
        } catch (UninitializedMessageException e) {
             // Original error handling
            throw new MappingException("Error building final target message: Required fields missing. " + e.getMessage(), e, null);
        } catch (Exception e) {
             if (e instanceof MappingException) throw (MappingException)e;
            throw new MappingException("Error building final target message", e, null);
        }
    }

    /**
     * Applies mapping rules from a source message onto an *existing* target message builder.
     *
     * @param sourceMessage The source protobuf message instance.
     * @param targetBuilder The builder for the target message, potentially pre-populated.
     * @param mappingRuleStrings A list of strings defining the mapping operations.
     * @return The same targetBuilder instance, modified according to the rules.
     * @throws MappingException If any error occurs during parsing or mapping.
     */
    public Message.Builder mapOnto(Message sourceMessage,
                                   Message.Builder targetBuilder,
                                   List<String> mappingRuleStrings) throws MappingException {
         // Delegate execution to MappingExecutor
        mappingExecutor.applyRulesToBuilder(sourceMessage, targetBuilder, mappingRuleStrings);
        return targetBuilder;
    }
}