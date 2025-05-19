package com.krickert.search.model.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleParserTest {

    private RuleParser ruleParser;

    @BeforeEach
    void setUp() {
        ruleParser = new RuleParser();
    }

    // ... (Passing tests remain unchanged) ...

    @Test
    void parseSingleAssignRule() throws MappingException {
        String ruleString = "target.field = source.field";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.ASSIGN, rule.getOperation());
        assertEquals("target.field", rule.getTargetPath());
        assertEquals("source.field", rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(ruleString, rule.getOriginalRuleString());
    }

    @Test
    void parseSingleAssignRule_WithSpaces() throws MappingException {
        String ruleString = "  target.field   =  \t source.field  ";
        MappingRule rule = ruleParser.parseSingleRule(ruleString.trim()); // Parser expects trimmed
        assertEquals(MappingRule.Operation.ASSIGN, rule.getOperation());
        assertEquals("target.field", rule.getTargetPath());
        assertEquals("source.field", rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(ruleString.trim(), rule.getOriginalRuleString());
    }

    @Test
    void parseSingleAppendRule() throws MappingException {
        String ruleString = "target.list += source.item";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.APPEND, rule.getOperation());
        assertEquals("target.list", rule.getTargetPath());
        assertEquals("source.item", rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(ruleString, rule.getOriginalRuleString());
    }

    @Test
    void parseSingleAppendRule_WithSpaces() throws MappingException {
        String ruleString = " target.list\t+=   source.item ";
        MappingRule rule = ruleParser.parseSingleRule(ruleString.trim());
        assertEquals(MappingRule.Operation.APPEND, rule.getOperation());
        assertEquals("target.list", rule.getTargetPath());
        assertEquals("source.item", rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(ruleString.trim(), rule.getOriginalRuleString());
    }

    @Test
    void parseSingleMapPutRule_QuotedKey() throws MappingException {
        String ruleString = "target.map[\"myKey\"] = source.value";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.MAP_PUT, rule.getOperation());
        assertEquals("target.map", rule.getTargetPath()); // Path to the map field itself
        assertEquals("myKey", rule.getMapKey());
        assertEquals("source.value", rule.getSourcePath());
        assertEquals(ruleString, rule.getOriginalRuleString());
        assertEquals("target.map[\"myKey\"]", rule.getFullTargetPathSpecification());
    }

    @Test
    void parseSingleMapPutRule_UnquotedKey() throws MappingException {
        // Based on regex, unquoted keys are parsed if they don't contain spaces/quotes within
        String ruleString = "target.map[myKey] = source.value";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.MAP_PUT, rule.getOperation());
        assertEquals("target.map", rule.getTargetPath());
        assertEquals("myKey", rule.getMapKey()); // Key is parsed without quotes
        assertEquals("source.value", rule.getSourcePath());
        assertEquals(ruleString, rule.getOriginalRuleString());
        assertEquals("target.map[\"myKey\"]", rule.getFullTargetPathSpecification()); // Still adds quotes for spec
    }

    @Test
    void parseSingleMapPutRule_WithSpaces() throws MappingException {
        String ruleString = " target.map [ \"myKey\" ]  =  source.value ";
        MappingRule rule = ruleParser.parseSingleRule(ruleString.trim());
        assertEquals(MappingRule.Operation.MAP_PUT, rule.getOperation());
        assertEquals("target.map", rule.getTargetPath());
        assertEquals("myKey", rule.getMapKey());
        assertEquals("source.value", rule.getSourcePath());
        assertEquals(ruleString.trim(), rule.getOriginalRuleString());
        assertEquals("target.map[\"myKey\"]", rule.getFullTargetPathSpecification());
    }

    @Test
    void parseSingleDeleteRule() throws MappingException {
        String ruleString = "-target.field.to.delete";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.DELETE, rule.getOperation());
        assertEquals("target.field.to.delete", rule.getTargetPath());
        assertNull(rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(ruleString, rule.getOriginalRuleString());
    }

    @Test
    void parseSingleDeleteRule_WithSpaces() throws MappingException {
        String ruleString = " -  target.field.to.delete ";
        MappingRule rule = ruleParser.parseSingleRule(ruleString.trim());
        assertEquals(MappingRule.Operation.DELETE, rule.getOperation());
        assertEquals("target.field.to.delete", rule.getTargetPath()); // Assumes target path itself has no leading/trailing spaces
        assertNull(rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(ruleString.trim(), rule.getOriginalRuleString());
    }

    @Test
    void parseSingleAssignRule_LiteralStringSource() throws MappingException {
        String ruleString = "target.field = \"a literal string\"";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.ASSIGN, rule.getOperation());
        assertEquals("target.field", rule.getTargetPath());
        assertEquals("\"a literal string\"", rule.getSourcePath()); // Literal kept as is
        assertEquals(ruleString, rule.getOriginalRuleString());
    }

    @Test
    void parseSingleAssignRule_LiteralNumberSource() throws MappingException {
        String ruleString = "target.field = 123.45";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.ASSIGN, rule.getOperation());
        assertEquals("target.field", rule.getTargetPath());
        assertEquals("123.45", rule.getSourcePath()); // Literal kept as is
        assertEquals(ruleString, rule.getOriginalRuleString());
    }

    @Test
    void parseSingleAssignRule_LiteralBooleanSource() throws MappingException {
        String ruleString = "target.field = true";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.ASSIGN, rule.getOperation());
        assertEquals("target.field", rule.getTargetPath());
        assertEquals("true", rule.getSourcePath()); // Literal kept as is
        assertEquals(ruleString, rule.getOriginalRuleString());
    }

    @Test
    void parseSingleAssignRule_LiteralNullSource() throws MappingException {
        String ruleString = "target.field = null";
        MappingRule rule = ruleParser.parseSingleRule(ruleString);
        assertEquals(MappingRule.Operation.ASSIGN, rule.getOperation());
        assertEquals("target.field", rule.getTargetPath());
        assertEquals("null", rule.getSourcePath()); // Literal kept as is
        assertEquals(ruleString, rule.getOriginalRuleString());
    }

    @Test
    void parseRules_SkipsEmptyLinesAndComments() throws MappingException {
        List<String> ruleStrings = Arrays.asList(
                "",
                " # This is a comment",
                "target.a = source.a",
                "  ",
                "\t# Another comment",
                "-target.b"
        );
        List<MappingRule> rules = ruleParser.parseRules(ruleStrings);
        assertEquals(2, rules.size());
        assertEquals(MappingRule.Operation.ASSIGN, rules.get(0).getOperation());
        assertEquals(MappingRule.Operation.DELETE, rules.get(1).getOperation());
    }

    @Test
    void parseRules_OrdersDeletesLast() throws MappingException {
        List<String> ruleStrings = Arrays.asList(
                "-target.b",
                "target.a = source.a",
                "-target.c",
                "target.map[\"k\"] = source.v",
                "target.list += item"
        );
        List<MappingRule> rules = ruleParser.parseRules(ruleStrings);
        assertEquals(5, rules.size());
        assertEquals(MappingRule.Operation.ASSIGN, rules.get(0).getOperation());
        assertEquals(MappingRule.Operation.MAP_PUT, rules.get(1).getOperation());
        assertEquals(MappingRule.Operation.APPEND, rules.get(2).getOperation());
        assertEquals(MappingRule.Operation.DELETE, rules.get(3).getOperation()); // First delete rule
        assertEquals("target.b", rules.get(3).getTargetPath());
        assertEquals(MappingRule.Operation.DELETE, rules.get(4).getOperation()); // Second delete rule
        assertEquals("target.c", rules.get(4).getTargetPath());
    }

    // --- Invalid Syntax Tests ---

    @Test
    void parseSingleRule_InvalidSyntax_DoubleEqualsAssign() {
        String ruleString = "target.field = = source.field";
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // **FIXED Assertion**
        assertTrue(e.getMessage().contains("Invalid assign rule syntax: source path starts with '='"));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_DoubleEqualsAppend() {
        String ruleString = "target.field += = source.field";
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // **FIXED Assertion**
        assertTrue(e.getMessage().contains("Invalid append rule syntax: source path starts with '='"));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_DoubleEqualsMapPut() {
        String ruleString = "target.map[\"k\"] = = source.field";
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // **FIXED Assertion**
        assertTrue(e.getMessage().contains("Invalid map put rule syntax: source path starts with '='"));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_MissingSourceAssign() {
        String ruleString = "target.field = ";
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // This should be caught by the final 'else' in the parser
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax: " + ruleString));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_MissingSourceAppend() {
        String ruleString = "target.field += ";
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // This should be caught by the final 'else' in the parser
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax: " + ruleString));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_MissingSourceMapPut() {
        String ruleString = "target.map[\"k\"] ="; // Missing source
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // This should be caught by the final 'else' in the parser
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax: " + ruleString));
        assertEquals(ruleString, e.getFailedRule());
    }


    @Test
    void parseSingleRule_InvalidSyntax_NoOperator() {
        String ruleString = "target.field source.field";
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax"));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_JustTarget() {
        String ruleString = "target.field";
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax"));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_MalformedMapPut() {
        String ruleString = "target.map[key = source.value"; // Missing closing bracket
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // This should be caught by the final 'else' as no pattern matches
        assertTrue(e.getMessage().contains("Invalid assignment rule syntax: " + ruleString));
        assertEquals(ruleString, e.getFailedRule());
    }

    @Test
    void parseSingleRule_InvalidSyntax_DeleteWithEquals() {
        String ruleString = "-target.field = source"; // Delete rule shouldn't have assignment
        MappingException e = assertThrows(MappingException.class, () -> ruleParser.parseSingleRule(ruleString));
        // **FIXED Assertion**
        assertTrue(e.getMessage().contains("Invalid delete rule syntax: target path cannot contain '+' or '='"));
        assertEquals(ruleString, e.getFailedRule());
    }
}