package com.krickert.search.schema.registry.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
public class BasicJsonValidatorLibraryTest {

    @Inject
    ResourceResolver resourceResolver;

    @Test
    void validateSchemaAgainstDraft7MetaSchema() throws Exception {
        // Load the schema to be validated
        Optional<InputStream> schemaStreamOpt = resourceResolver.getResourceAsStream("classpath:my-schema.json");
        assertTrue(schemaStreamOpt.isPresent(), "Schema file not found");
        InputStream schemaStream = schemaStreamOpt.get();

        // Load the Draft 7 meta-schema
        Optional<InputStream> metaSchemaStreamOpt = resourceResolver.getResourceAsStream("classpath:draft7.json");
        assertTrue(metaSchemaStreamOpt.isPresent(), "Draft 7 meta-schema file not found");
        InputStream metaSchemaStream = metaSchemaStreamOpt.get();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode schemaNode = mapper.readTree(schemaStream);
        JsonNode metaSchemaNode = mapper.readTree(metaSchemaStream);

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema metaSchema = factory.getSchema(metaSchemaNode);

        Set<ValidationMessage> errors = metaSchema.validate(schemaNode);

        assertTrue(errors.isEmpty(), () -> "Schema validation errors: " + errors);
    }
}
