// src/main/java/com/krickert/search/model/mapper/RuleParser.java (Attempt 5)
package com.krickert.search.model.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses mapping rule strings into MappingRule objects.
 */
public class RuleParser {

    // Regex patterns for parsing rules (Refined again)
    // Use non-capturing groups (?:...) where possible if groups aren't needed, though not strictly necessary here.
    // Make source groups explicitly capture non-whitespace start for assign/map_put/append if possible.
    private static final Pattern DELETE_PATTERN = Pattern.compile("^-\\s*(\\S.+?)\\s*$"); // Target must have non-whitespace chars
    private static final Pattern MAP_PUT_PATTERN = Pattern.compile("^([^\\[\\s]+)\\s*\\[\\s*\"?([^\"]+)\"?\\s*\\]\\s*=\\s*(\\S.*)$"); // Source must have non-whitespace chars
    private static final Pattern APPEND_PATTERN = Pattern.compile("^([^+\\s\\[]+)\\s*\\+=\\s*(\\S.*)$"); // Source must have non-whitespace chars
    private static final Pattern ASSIGN_PATTERN = Pattern.compile("^([^=\\s\\[+]+)\\s*=\\s*(\\S.*)$"); // Source must have non-whitespace chars

    /**
     * Parses a list of rule strings into MappingRule objects.
     */
    public List<MappingRule> parseRules(List<String> ruleStrings) throws MappingException {
        List<MappingRule> parsedRules = new ArrayList<>();
        List<MappingRule> deleteRules = new ArrayList<>();

        if (ruleStrings == null) {
            return parsedRules;
        }

        for (String ruleString : ruleStrings) {
             if (ruleString == null) continue;
            String trimmedRule = ruleString.trim();
            if (trimmedRule.isEmpty() || trimmedRule.startsWith("#")) {
                continue;
            }
            String originalTrimmedRule = trimmedRule;
            try {
                MappingRule rule = parseSingleRule(originalTrimmedRule);
                if (rule.getOperation() == MappingRule.Operation.DELETE) {
                    deleteRules.add(rule);
                } else {
                    parsedRules.add(rule);
                }
            } catch (MappingException e) {
                 if (e.getFailedRule() == null || e.getFailedRule().isEmpty()) {
                     throw new MappingException(e.getMessage(), e.getCause(), originalTrimmedRule);
                 } else {
                      throw e;
                 }
            } catch (Exception e) {
                 throw new MappingException("Unexpected parsing error for rule: " + originalTrimmedRule, e, originalTrimmedRule);
            }
        }
        parsedRules.addAll(deleteRules);
        return parsedRules;
    }

    /**
     * Parses a single, non-empty, non-comment rule string.
     */
    public MappingRule parseSingleRule(String ruleString) throws MappingException {
        // Try matching each pattern in order of specificity
        Matcher deleteMatcher = DELETE_PATTERN.matcher(ruleString);
        if (deleteMatcher.matches()) {
            String targetPath = deleteMatcher.group(1).trim(); // Trim captured target
             // Check if the trimmed target still contains invalid operators
             // (This implies the original raw path had them, as '-' rules shouldn't)
            if (targetPath.contains("=") || targetPath.contains("+")) {
                 throw new MappingException("Invalid delete rule syntax: target path cannot contain '+' or '='", ruleString);
            }
            // Regex already ensures targetPath is not empty due to \\S
            return MappingRule.createDeleteRule(targetPath, ruleString);
        }

        Matcher mapPutMatcher = MAP_PUT_PATTERN.matcher(ruleString);
        if (mapPutMatcher.matches()) {
            String targetMapPath = mapPutMatcher.group(1).trim();
            String mapKey = mapPutMatcher.group(2).trim();
            String sourcePath = mapPutMatcher.group(3).trim(); // Regex \\S.* ensures not empty
             // Check for double equals edge case explicitly
             if (mapPutMatcher.group(3).trim().startsWith("=")) {
                 throw new MappingException("Invalid map put rule syntax: source path starts with '='", ruleString);
            }
            return MappingRule.createMapPutRule(targetMapPath, mapKey, sourcePath, ruleString);
        }

        Matcher appendMatcher = APPEND_PATTERN.matcher(ruleString);
        if (appendMatcher.matches()) {
            String targetPath = appendMatcher.group(1).trim();
            String sourcePath = appendMatcher.group(2).trim(); // Regex \\S.* ensures not empty
            // Check for double equals edge case explicitly
            if (appendMatcher.group(2).trim().startsWith("=")) {
                 throw new MappingException("Invalid append rule syntax: source path starts with '='", ruleString);
            }
            return MappingRule.createAppendRule(targetPath, sourcePath, ruleString);
        }

        Matcher assignMatcher = ASSIGN_PATTERN.matcher(ruleString);
        if (assignMatcher.matches()) {
            String targetPath = assignMatcher.group(1).trim();
            String sourcePath = assignMatcher.group(2).trim(); // Regex \\S.* ensures not empty
            // Check for double equals edge case explicitly
             if (assignMatcher.group(2).trim().startsWith("=")) {
                 throw new MappingException("Invalid assign rule syntax: source path starts with '='", ruleString);
            }
            return MappingRule.createAssignRule(targetPath, sourcePath, ruleString);
        }

        // If none of the specific patterns matched, it's invalid syntax.
        // This should now correctly catch "target +=", "-target =", etc.
        throw new MappingException("Invalid assignment rule syntax: " + ruleString, ruleString);
    }
}