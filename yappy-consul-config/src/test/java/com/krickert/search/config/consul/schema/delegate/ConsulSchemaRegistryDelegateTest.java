package com.krickert.search.config.consul.schema.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.schema.exception.SchemaDeleteException;
import com.krickert.search.config.consul.schema.exception.SchemaNotFoundException;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.networknt.schema.ValidationMessage;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@MicronautTest(startApplication = false)
@Property(name = "consul.client.config.path", value = "test/config/pipeline")
class ConsulSchemaRegistryDelegateTest {

    private final String TEST_SCHEMA_ID = "test-schema-1";
    private final String VALID_SCHEMA_CONTENT_MINIMAL = "{\"type\": \"object\"}";
    private final String INVALID_JSON_CONTENT = "{ type: \"object\" }"; // Malformed JSON
    private final String STRUCTURALLY_INVALID_TYPE_VALUE = "{\"type\": 123}"; // Correct JSON, but structurally invalid schema
    private final String STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_PATTERN = "{\"type\": \"string\", \"pattern\": \"([\"}"; // Invalid regex
    private final String STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_REF = "{\"$ref\": \"#/definitions/nonExistent\"}"; // Unresolvable local ref, but networknt is lenient on this during syntax check
    private final String SCHEMA_WITH_UNKNOWN_KEYWORD = "{\"invalid_prop\": \"object\", \"type\": \"object\"}"; // Unknown keyword, networknt is lenient on this during syntax check
    ConsulKvService mockConsulKvService;
    @Inject
    ObjectMapper objectMapper;
    @Inject
    @Property(name = "consul.client.config.path")
    String baseConfigPath;
    ConsulSchemaRegistryDelegate delegate;
    private String expectedFullSchemaPrefix;

    @BeforeEach
    void setUp() {
        mockConsulKvService = Mockito.mock(ConsulKvService.class);
        // Re-initialize delegate for each test to ensure clean state and reflect any @BeforeEach changes to mocks
        delegate = new ConsulSchemaRegistryDelegate(mockConsulKvService, objectMapper, baseConfigPath);
        expectedFullSchemaPrefix = (baseConfigPath.endsWith("/") ? baseConfigPath : baseConfigPath + "/") + "schemas/";
    }

    private String getExpectedConsulKey(String schemaId) {
        return expectedFullSchemaPrefix + schemaId;
    }

    @Nested
    @DisplayName("saveSchema Tests")
    class SaveSchemaTests {
        @Test
        void saveSchema_success() {
            when(mockConsulKvService.putValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID)), eq(VALID_SCHEMA_CONTENT_MINIMAL)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, VALID_SCHEMA_CONTENT_MINIMAL))
                    .verifyComplete();
        }

        @Test
        void saveSchema_emptyId_throwsIllegalArgumentException() {
            StepVerifier.create(delegate.saveSchema("", VALID_SCHEMA_CONTENT_MINIMAL))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }

        @Test
        void saveSchema_emptyContent_throwsIllegalArgumentException() {
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, ""))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }

        @Test
        void saveSchema_invalidJsonSyntax_throwsIllegalArgumentException() {
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, INVALID_JSON_CONTENT))
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(IllegalArgumentException.class);
                        assertThat(throwable.getMessage()).contains("Schema content is not a valid JSON Schema");
                        assertThat(throwable.getMessage()).contains("Invalid JSON syntax");
                    })
                    .verify();
        }

        @Test
        void saveSchema_structurallyInvalidPattern_throwsIllegalArgumentException() {
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_PATTERN))
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(IllegalArgumentException.class);
                        assertThat(throwable.getMessage()).contains("Schema content is not a valid JSON Schema");
                        // Check for the specific error from the validator
                        assertThat(throwable.getMessage()).contains("pattern must be a valid ECMA-262 regular expression");
                    })
                    .verify();
        }

        @Test
        @DisplayName("Given structurally invalid schema (bad type value), saveSchema fails validation early")
        void saveSchema_structurallyInvalidTypeValue_throwsIllegalArgumentException() {
            // "{\"type\": 123}" WILL now cause validateSchemaSyntax to return errors.
            // So, saveSchema should fail before trying to save.
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, STRUCTURALLY_INVALID_TYPE_VALUE))
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(IllegalArgumentException.class);
                        assertThat(throwable.getMessage()).contains("Schema content is not a valid JSON Schema");
                        assertThat(throwable.getMessage()).contains("does not have a value in the enumeration"); // Specific error
                    })
                    .verify();
        }


        @Test
        @DisplayName("Given schema with unresolvable local $ref, saveSchema succeeds as syntax check is lenient")
        void saveSchema_lenientValidSchemaWithBadRef_savesSuccessfully() {
            // The networknt validator, during the syntax check phase (meta-schema validation),
            // does not typically fail for unresolvable local $refs if the overall structure is JSON.
            // It might fail during actual data validation against such a schema if $ref resolution is strict there.
            when(mockConsulKvService.putValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID)), eq(STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_REF)))
                    .thenReturn(Mono.just(true));
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_REF))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Given schema with unknown keyword, saveSchema succeeds as syntax check is lenient")
        void saveSchema_lenientValidSchemaWithUnknownKeyword_savesSuccessfully() {
            // Similar to bad $refs, unknown keywords are often ignored by the meta-schema validation
            // unless specific configurations are set to disallow them.
            when(mockConsulKvService.putValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID)), eq(SCHEMA_WITH_UNKNOWN_KEYWORD)))
                    .thenReturn(Mono.just(true));
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, SCHEMA_WITH_UNKNOWN_KEYWORD))
                    .verifyComplete();
        }

        @Test
        void saveSchema_consulPutReturnsFalse_throwsRuntimeException() {
            when(mockConsulKvService.putValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID)), eq(VALID_SCHEMA_CONTENT_MINIMAL)))
                    .thenReturn(Mono.just(false));
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, VALID_SCHEMA_CONTENT_MINIMAL))
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(RuntimeException.class);
                        assertThat(throwable.getMessage()).isEqualTo("Failed to save schema to Consul for ID: " + TEST_SCHEMA_ID);
                    })
                    .verify();
        }

        @Test
        void saveSchema_consulPutErrors_throwsRuntimeException() {
            when(mockConsulKvService.putValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID)), eq(VALID_SCHEMA_CONTENT_MINIMAL)))
                    .thenReturn(Mono.error(new RuntimeException("Consul error")));
            StepVerifier.create(delegate.saveSchema(TEST_SCHEMA_ID, VALID_SCHEMA_CONTENT_MINIMAL))
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(RuntimeException.class);
                        assertThat(throwable.getMessage()).isEqualTo("Consul error");
                    })
                    .verify();
        }
    }

    @Nested
    @DisplayName("getSchemaContent Tests")
    class GetSchemaContentTests {
        @Test
        void getSchemaContent_success() {
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(Optional.of(VALID_SCHEMA_CONTENT_MINIMAL)));
            StepVerifier.create(delegate.getSchemaContent(TEST_SCHEMA_ID))
                    .expectNext(VALID_SCHEMA_CONTENT_MINIMAL)
                    .verifyComplete();
        }

        @Test
        void getSchemaContent_notFound_whenConsulReturnsOptionalEmpty_throwsSchemaNotFoundException() {
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(Optional.empty()));
            StepVerifier.create(delegate.getSchemaContent(TEST_SCHEMA_ID))
                    .expectError(SchemaNotFoundException.class)
                    .verify();
        }

        @Test
        void getSchemaContent_notFound_whenConsulReturnsMonoEmpty_throwsSchemaNotFoundException() {
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.empty());
            StepVerifier.create(delegate.getSchemaContent(TEST_SCHEMA_ID))
                    .expectError(SchemaNotFoundException.class)
                    .verify();
        }

        @Test
        void getSchemaContent_consulValueBlank_throwsSchemaNotFoundException() {
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(Optional.of("   ")));
            StepVerifier.create(delegate.getSchemaContent(TEST_SCHEMA_ID))
                    .expectError(SchemaNotFoundException.class)
                    .verify();
        }

        @Test
        void getSchemaContent_emptyId_throwsIllegalArgumentException() {
            StepVerifier.create(delegate.getSchemaContent(""))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("deleteSchema Tests")
    class DeleteSchemaTests {
        @Test
        void deleteSchema_success() {
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(Optional.of(VALID_SCHEMA_CONTENT_MINIMAL)));
            when(mockConsulKvService.deleteKey(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(true));
            StepVerifier.create(delegate.deleteSchema(TEST_SCHEMA_ID))
                    .verifyComplete();
        }

        @Test
        void deleteSchema_notFound_throwsSchemaNotFoundException() {
            // This covers when getValue returns an empty Optional
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(Optional.empty()));
            StepVerifier.create(delegate.deleteSchema(TEST_SCHEMA_ID))
                    .expectError(SchemaNotFoundException.class)
                    .verify();
        }

        @Test
        void deleteSchema_notFound_whenGetValueIsMonoEmpty_throwsSchemaNotFoundException() {
            // This covers when getValue itself is an empty Mono
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.empty());
            StepVerifier.create(delegate.deleteSchema(TEST_SCHEMA_ID))
                    .expectError(SchemaNotFoundException.class)
                    .verify();
        }

        @Test
        void deleteSchema_consulDeleteReturnsFalse_throwsSchemaDeleteException() { // Renamed for clarity
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(Optional.of(VALID_SCHEMA_CONTENT_MINIMAL)));
            when(mockConsulKvService.deleteKey(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(false)); // Mock deleteKey to return false

            StepVerifier.create(delegate.deleteSchema(TEST_SCHEMA_ID))
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(SchemaDeleteException.class);
                        // Assert the message of the SchemaDeleteException
                        assertThat(throwable.getMessage()).isEqualTo("Error deleting schema from Consul for ID: " + TEST_SCHEMA_ID);
                        // Assert the message of the cause (the RuntimeException for unsuccessful delete)
                        assertThat(throwable.getCause()).isInstanceOf(RuntimeException.class);
                        assertThat(throwable.getCause().getMessage()).isEqualTo("Failed to delete schema from Consul (delete command unsuccessful) for ID: " + TEST_SCHEMA_ID);
                    })
                    .verify();
        }

        @Test
        void deleteSchema_consulDeleteErrors_throwsSchemaDeleteException() { // Renamed for clarity
            when(mockConsulKvService.getValue(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.just(Optional.of(VALID_SCHEMA_CONTENT_MINIMAL)));
            // This is the original error we are mocking from the deleteKey operation
            RuntimeException consulDeleteOperationException = new RuntimeException("Consul delete operation error");
            when(mockConsulKvService.deleteKey(eq(getExpectedConsulKey(TEST_SCHEMA_ID))))
                    .thenReturn(Mono.error(consulDeleteOperationException));

            StepVerifier.create(delegate.deleteSchema(TEST_SCHEMA_ID))
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(SchemaDeleteException.class);
                        // Assert the message of the SchemaDeleteException
                        assertThat(throwable.getMessage()).isEqualTo("Error deleting schema from Consul for ID: " + TEST_SCHEMA_ID);
                        // Assert that the cause is the original mocked exception
                        assertThat(throwable.getCause()).isSameAs(consulDeleteOperationException);
                    })
                    .verify();
        }

        @Test
        void deleteSchema_emptyId_throwsIllegalArgumentException() {
            StepVerifier.create(delegate.deleteSchema(""))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("listSchemaIds Tests")
    class ListSchemaIdsTests {
        @Test
        void listSchemaIds_success_multipleItems() {
            List<String> keysFromConsul = List.of(
                    expectedFullSchemaPrefix + "id1",
                    expectedFullSchemaPrefix + "id2",
                    expectedFullSchemaPrefix + "id3/sub" // Keep this to test path stripping
            );
            // Expected IDs after stripping prefix
            List<String> expectedIds = List.of("id1", "id2", "id3/sub");

            when(mockConsulKvService.getKeysWithPrefix(eq(expectedFullSchemaPrefix)))
                    .thenReturn(Mono.just(keysFromConsul));

            StepVerifier.create(delegate.listSchemaIds())
                    .expectNextMatches(ids -> {
                        assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedIds);
                        return true;
                    })
                    .verifyComplete();
        }


        @Test
        void listSchemaIds_success_empty() {
            when(mockConsulKvService.getKeysWithPrefix(eq(expectedFullSchemaPrefix)))
                    .thenReturn(Mono.just(Collections.emptyList()));
            StepVerifier.create(delegate.listSchemaIds())
                    .expectNextMatches(List::isEmpty)
                    .verifyComplete();
        }

        @Test
        void listSchemaIds_consulGetKeysFails_returnsEmptyListAndLogsError() {
            // The delegate's onErrorResume should catch this and return an empty list
            when(mockConsulKvService.getKeysWithPrefix(eq(expectedFullSchemaPrefix)))
                    .thenReturn(Mono.error(new RuntimeException("Consul error")));
            StepVerifier.create(delegate.listSchemaIds())
                    .expectNextMatches(List::isEmpty) // Expecting empty list due to onErrorResume
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("validateSchemaSyntax Tests")
    class ValidateSchemaSyntaxTests {
        @Test
        void validateSchemaSyntax_validSchema_returnsEmptySet() {
            StepVerifier.create(delegate.validateSchemaSyntax(VALID_SCHEMA_CONTENT_MINIMAL))
                    .expectNextMatches(Set::isEmpty)
                    .verifyComplete();
        }

        @Test
        void validateSchemaSyntax_invalidJson_returnsErrorMessages() {
            StepVerifier.create(delegate.validateSchemaSyntax(INVALID_JSON_CONTENT))
                    .expectNextMatches(messages -> {
                        assertThat(messages).hasSize(1);
                        assertThat(messages.iterator().next().getMessage()).contains("Invalid JSON syntax");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Given structurally invalid schema (bad type value), expect specific validation errors")
        void validateSchemaSyntax_structurallyInvalid_wrongTypeValue_returnsErrorMessages() {
            System.out.println("Testing with wrong type value: " + STRUCTURALLY_INVALID_TYPE_VALUE);
            StepVerifier.create(delegate.validateSchemaSyntax(STRUCTURALLY_INVALID_TYPE_VALUE))
                    .expectNextMatches(messages -> {
                        System.out.println("Actual messages (wrong type value): " + messages);
                        assertThat(messages).as("Validation messages for wrong type value '{\"type\": 123}'").isNotEmpty();
                        assertThat(messages).extracting(ValidationMessage::getMessage)
                                .anySatisfy(message -> assertThat(message).contains("does not have a value in the enumeration"))
                                .anySatisfy(message -> assertThat(message).contains("integer found, array expected"));
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        void validateSchemaSyntax_structurallyInvalidSchema_badPattern_returnsErrorMessages() {
            System.out.println("Testing with bad pattern: " + STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_PATTERN);
            StepVerifier.create(delegate.validateSchemaSyntax(STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_PATTERN))
                    .expectNextMatches(messages -> {
                        System.out.println("Actual messages (bad pattern): " + messages);
                        assertThat(messages).as("Validation messages for bad pattern").hasSize(1);
                        ValidationMessage vm = messages.iterator().next();
                        // The message from networknt for bad pattern is quite direct
                        assertThat(vm.getMessage()).contains("pattern must be a valid ECMA-262 regular expression");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Given schema with unresolvable local $ref, expect empty messages as syntax check is lenient on this")
        void validateSchemaSyntax_structurallyInvalidSchema_badRef_returnsEmptyMessages() {
            // The networknt validator's meta-schema validation (syntax check) is typically lenient
            // on unresolvable local $refs. It might only fail if $ref itself is malformed.
            // Actual data validation against such a schema would likely fail later if strict $ref resolution is enabled.
            System.out.println("Testing with bad ref: " + STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_REF);
            StepVerifier.create(delegate.validateSchemaSyntax(STRUCTURALLY_INVALID_SCHEMA_WITH_BAD_REF))
                    .expectNextMatches(messages -> {
                        System.out.println("Actual messages (bad ref): " + messages);
                        assertThat(messages).as("Validation messages for unresolvable local $ref should be empty for syntax check").isEmpty();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Given schema with an unknown keyword, expect empty messages as syntax check is lenient on this")
        void validateSchemaSyntax_unknownKeyword_returnsEmptyMessages() {
            // By default, unknown keywords are often ignored during meta-schema validation.
            System.out.println("Testing with unknown keyword: " + SCHEMA_WITH_UNKNOWN_KEYWORD);
            StepVerifier.create(delegate.validateSchemaSyntax(SCHEMA_WITH_UNKNOWN_KEYWORD))
                    .expectNextMatches(messages -> {
                        System.out.println("Messages for SCHEMA_WITH_UNKNOWN_KEYWORD: " + messages);
                        assertThat(messages).as("Validation messages for unknown keyword should be empty for syntax check").isEmpty();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        void validateSchemaSyntax_emptyContent_returnsErrorMessages() {
            StepVerifier.create(delegate.validateSchemaSyntax(""))
                    .expectNextMatches(messages -> {
                        assertThat(messages).hasSize(1);
                        assertThat(messages.iterator().next().getMessage()).isEqualTo("Schema content cannot be empty.");
                        return true;
                    })
                    .verifyComplete();
        }
    }
}