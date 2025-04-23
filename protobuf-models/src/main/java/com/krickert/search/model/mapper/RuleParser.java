// src/main/java/com/krickert/search/model/RuleParser.java
// Extracted directly from ProtoMapper.java
package com.krickert.search.model.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses mapping rule strings into MappingRule objects.
 * Logic extracted from the original ProtoMapper.
 */
public class RuleParser {

    // Regex patterns for parsing rules (Copied from ProtoMapper)
    private static final Pattern DELETE_PATTERN = Pattern.compile("^-(.+)"); // Matches "-target.path"
    // Updated ASSIGN_PATTERN to prevent matching '=' at the start of the source part
    private static final Pattern ASSIGN_PATTERN = Pattern.compile("^([^=\\s]+)\\s*=\\s*([^=\\s].*)$"); // Matches "target.path = source.path" (source cannot start with =)
    private static final Pattern APPEND_PATTERN = Pattern.compile("^([^+\\s]+)\\s*\\+=\s*(.+)"); // Matches "target.list += source.item" or "target.map += source.map"
    private static final Pattern MAP_PUT_PATTERN = Pattern.compile("^([^\\[\\s]+)\\[\"?([^\"]+)\"?\\]\\s*=\\s*(.+)"); // Matches "target.map[\"key\"] = source.path"

    /**
     * Parses a list of rule strings into MappingRule objects.
     * Deletion rules are added last.
     *
     * @param ruleStrings List of raw rule strings.
     * @return List of parsed MappingRule objects.
     * @throws MappingException If any rule has invalid syntax.
     */
    public List<MappingRule> parseRules(List<String> ruleStrings) throws MappingException {
        List<MappingRule> parsedRules = new ArrayList<>();
        List<MappingRule> deleteRules = new ArrayList<>();

        for (String ruleString : ruleStrings) {
            String trimmedRule = ruleString.trim();
            if (trimmedRule.isEmpty() || trimmedRule.startsWith("#")) {
                continue; // Skip empty lines and comments
            }

            MappingRule rule = parseSingleRule(trimmedRule);
            if (rule.getOperation() == MappingRule.Operation.DELETE) {
                deleteRules.add(rule);
            } else {
                parsedRules.add(rule);
            }
        }
        // Add delete rules at the end, preserving original order among deletions
        parsedRules.addAll(deleteRules);
        return parsedRules;
    }

    /**
     * Parses a single, non-empty, non-comment rule string.
     *
     * @param ruleString The trimmed rule string.
     * @return A MappingRule object.
     * @throws MappingException If the syntax is invalid.
     */
    public MappingRule parseSingleRule(String ruleString) throws MappingException {
        Matcher deleteMatcher = DELETE_PATTERN.matcher(ruleString);
        Matcher assignMatcher = ASSIGN_PATTERN.matcher(ruleString);
        Matcher appendMatcher = APPEND_PATTERN.matcher(ruleString);
        Matcher mapPutMatcher = MAP_PUT_PATTERN.matcher(ruleString);

        if (deleteMatcher.matches()) {
            return MappingRule.createDeleteRule(deleteMatcher.group(1).trim(), ruleString);
        } else if (mapPutMatcher.matches()) {
            String targetMapPath = mapPutMatcher.group(1).trim();
            String mapKey = mapPutMatcher.group(2).trim(); // Key as parsed (quotes removed by regex group)
            String sourcePath = mapPutMatcher.group(3).trim();
             if (sourcePath.startsWith("=")) { // Check copied from original ProtoMapper
                 throw new MappingException("Invalid assignment rule syntax: multiple '=' found", null, ruleString);
            }
            return MappingRule.createMapPutRule(targetMapPath, mapKey, sourcePath, ruleString);
        } else if (appendMatcher.matches()) {
            String targetPath = appendMatcher.group(1).trim();
            String sourcePath = appendMatcher.group(2).trim();
             if (sourcePath.startsWith("=")) { // Check copied from original ProtoMapper
                 throw new MappingException("Invalid assignment rule syntax: multiple '=' found", null, ruleString);
            }
            return MappingRule.createAppendRule(targetPath, sourcePath, ruleString);
        } else if (assignMatcher.matches()) {
            String targetPath = assignMatcher.group(1).trim();
            String sourcePath = assignMatcher.group(2).trim();
             if (sourcePath.startsWith("=")) { // Check copied from original ProtoMapper
                 throw new MappingException("Invalid assignment rule syntax: multiple '=' found", null, ruleString);
            }
            return MappingRule.createAssignRule(targetPath, sourcePath, ruleString);
        } else {
            throw new MappingException("Invalid assignment rule syntax: " + ruleString, null, ruleString);
        }
    }
}