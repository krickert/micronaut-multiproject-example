package com.krickert.search.model.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MappingRuleTest {

    @Test
    void createAssignRule() {
        String target = "t.field";
        String source = "s.field";
        String original = "t.field = s.field";
        MappingRule rule = MappingRule.createAssignRule(target, source, original);

        assertEquals(MappingRule.Operation.ASSIGN, rule.getOperation());
        assertEquals(target, rule.getTargetPath());
        assertEquals(source, rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(original, rule.getOriginalRuleString());
        assertEquals(target, rule.getFullTargetPathSpecification());
        assertEquals(original, rule.toString());
    }

    @Test
    void createAppendRule() {
        String target = "t.list";
        String source = "s.item";
        String original = "t.list += s.item";
        MappingRule rule = MappingRule.createAppendRule(target, source, original);

        assertEquals(MappingRule.Operation.APPEND, rule.getOperation());
        assertEquals(target, rule.getTargetPath());
        assertEquals(source, rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(original, rule.getOriginalRuleString());
        assertEquals(target, rule.getFullTargetPathSpecification());
        assertEquals(original, rule.toString());
    }

    @Test
    void createMapPutRule() {
        String target = "t.map";
        String key = "theKey";
        String source = "s.value";
        String original = "t.map[\"theKey\"] = s.value";
        MappingRule rule = MappingRule.createMapPutRule(target, key, source, original);

        assertEquals(MappingRule.Operation.MAP_PUT, rule.getOperation());
        assertEquals(target, rule.getTargetPath());
        assertEquals(key, rule.getMapKey());
        assertEquals(source, rule.getSourcePath());
        assertEquals(original, rule.getOriginalRuleString());
        assertEquals("t.map[\"theKey\"]", rule.getFullTargetPathSpecification());
        assertEquals(original, rule.toString());
    }

    @Test
    void createDeleteRule() {
        String target = "t.field.to.del";
        String original = "-t.field.to.del";
        MappingRule rule = MappingRule.createDeleteRule(target, original);

        assertEquals(MappingRule.Operation.DELETE, rule.getOperation());
        assertEquals(target, rule.getTargetPath());
        assertNull(rule.getSourcePath());
        assertNull(rule.getMapKey());
        assertEquals(original, rule.getOriginalRuleString());
        assertEquals(target, rule.getFullTargetPathSpecification());
        assertEquals(original, rule.toString());
    }

    // Test constructor validations (though using static factories is preferred)
    @Test
    void constructorValidation_DeleteWithSourcePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            new MappingRule(MappingRule.Operation.DELETE, "t", "s", null, "-t");
        });
        assertTrue(e.getMessage().contains("Source path must be null for DELETE"));
    }

    @Test
    void constructorValidation_AssignWithoutSourcePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            new MappingRule(MappingRule.Operation.ASSIGN, "t", null, null, "t = ?");
        });
        assertTrue(e.getMessage().contains("Source path cannot be null for non-DELETE"));
    }

    @Test
    void constructorValidation_MapPutWithoutMapKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            new MappingRule(MappingRule.Operation.MAP_PUT, "t", "s", null, "t[] = s");
        });
        assertTrue(e.getMessage().contains("Map key cannot be null for MAP_PUT"));
    }

    @Test
    void constructorValidation_AssignWithMapKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            new MappingRule(MappingRule.Operation.ASSIGN, "t", "s", "key", "t = s");
        });
        assertTrue(e.getMessage().contains("Map key must be null for non-MAP_PUT"));
    }

    @Test
    void equalsAndHashCode() {
        String target = "t.field";
        String source = "s.field";
        String original = "t.field = s.field";

        MappingRule rule1 = MappingRule.createAssignRule(target, source, original);
        MappingRule rule2 = MappingRule.createAssignRule(target, source, original); // Same as rule1
        MappingRule rule3 = MappingRule.createAssignRule(target, source, "t.field=s.field"); // Different original string
        MappingRule rule4 = MappingRule.createAppendRule(target, source, original); // Different operation

        // Test equals contract: Reflexive, Symmetric, Transitive
        assertEquals(rule1, rule1); // Reflexive
        assertEquals(rule1, rule2); // Symmetric check part 1
        assertEquals(rule2, rule1); // Symmetric check part 2
        assertEquals(rule1.hashCode(), rule2.hashCode()); // Consistent with equals

        // Test inequality
        assertNotEquals(rule1, rule3); // Different original string
        assertNotEquals(rule1, rule4); // Different operation based on original string parsing
        assertNotEquals(rule1, null);  // Inequality with null
        assertNotEquals(rule1, new Object()); // Inequality with different type

        // Removed the problematic assertion: assertNotEquals(rule1.hashCode(), rule3.hashCode());
        // Hashcodes *might* collide even if objects are not equal.
    }
}