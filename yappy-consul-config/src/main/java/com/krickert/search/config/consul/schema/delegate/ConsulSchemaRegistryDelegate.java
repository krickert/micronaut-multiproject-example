package com.krickert.search.config.consul.schema.delegate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.ValidationResult;
import com.krickert.search.config.consul.schema.exception.SchemaDeleteException;
import com.krickert.search.config.consul.schema.exception.SchemaLoadException;
import com.krickert.search.config.consul.schema.exception.SchemaNotFoundException;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.networknt.schema.*;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
@Requires(property = "consul.client.enabled", value = "true", defaultValue = "true")
public class ConsulSchemaRegistryDelegate {

    private static final Logger log = LoggerFactory.getLogger(ConsulSchemaRegistryDelegate.class);
    private final String fullSchemaKvPrefix;

    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;
    private final SchemaValidationService schemaValidationService;
    private final JsonSchemaFactory schemaFactory;
    private final JsonSchema metaSchema;

    @Inject
    public ConsulSchemaRegistryDelegate(
            ConsulKvService consulKvService,
            ObjectMapper objectMapper, // Micronaut will inject its configured ObjectMapper
            SchemaValidationService schemaValidationService,
            @Value("${consul.client.config.path:config/pipeline}") String baseConfigPath) {
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper;
        this.schemaValidationService = schemaValidationService;

        SchemaValidatorsConfig metaSchemaLoadingConfig = getSchemaValidationConfig();

        // 1. Initialize the factory for Draft 7
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

        // 2. Load the draft7.json content from src/main/resources (root of classpath)
        String metaSchemaClasspathPath = "/draft7.json"; // Path from the root of the classpath
        JsonNode metaSchemaNode;

        try (InputStream metaSchemaStream = ConsulSchemaRegistryDelegate.class.getResourceAsStream(metaSchemaClasspathPath)) {
            if (metaSchemaStream == null) {
                log.error("CRITICAL: Could not find 'draft7.json' at classpath root: {}. " +
                        "Ensure the file is in 'src/main/resources/draft7.json'.", metaSchemaClasspathPath);
                throw new SchemaLoadException("Could not find 'draft7.json' at classpath root: " + metaSchemaClasspathPath);
            }
            log.info("Loading meta-schema from classpath: {}", metaSchemaClasspathPath);
            metaSchemaNode = this.objectMapper.readTree(metaSchemaStream);
            log.info("Successfully loaded and parsed meta-schema from: {}", metaSchemaClasspathPath);
        } catch (IOException e) {
            log.error("CRITICAL: Failed to read or parse 'draft7.json' from classpath (path: {}). Error: {}",
                    metaSchemaClasspathPath, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize ConsulSchemaRegistryDelegate: Could not read/parse 'draft7.json' from classpath.", e);
        }

        // 3. Create the JsonSchema instance for the meta-schema from its JsonNode content
        try {
            this.metaSchema = this.schemaFactory.getSchema(metaSchemaNode, metaSchemaLoadingConfig);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to create JsonSchema instance from 'draft7.json' content. Error: {}",
                    e.getMessage(), e);
            throw new RuntimeException("Failed to initialize ConsulSchemaRegistryDelegate: Could not create JsonSchema from 'draft7.json' content.", e);
        }

        String sanitizedBaseConfigPath = baseConfigPath.endsWith("/") ? baseConfigPath : baseConfigPath + "/";
        this.fullSchemaKvPrefix = sanitizedBaseConfigPath + "schemas/";
        log.info("ConsulSchemaRegistryDelegate initialized with Draft 7 meta-schema (loaded from classpath), using Consul KV prefix: {}", this.fullSchemaKvPrefix);

    }

    private SchemaValidatorsConfig getSchemaValidationConfig() {
        return SchemaValidatorsConfig.builder()
                .pathType(PathType.LEGACY)
                .errorMessageKeyword("message")
                .nullableKeywordEnabled(true)
                .build();
    }

    public Mono<Void> saveSchema(@NonNull String schemaId, @NonNull String schemaContent) {
        if (StringUtils.isEmpty(schemaId) || StringUtils.isEmpty(schemaContent)) {
            return Mono.error(new IllegalArgumentException("Schema ID and content cannot be empty."));
        }
        return validateSchemaSyntax(schemaContent)
                .flatMap(validationMessages -> {
                    if (!validationMessages.isEmpty()) {
                        String errors = validationMessages.stream()
                                .map(ValidationMessage::getMessage)
                                .collect(Collectors.joining("; "));
                        log.warn("Schema syntax validation failed for ID '{}': {}", schemaId, errors);
                        return Mono.error(new IllegalArgumentException("Schema content is not a valid JSON Schema: " + errors));
                    }
                    log.debug("Schema syntax validated successfully for ID: {}", schemaId);
                    String consulKey = getSchemaKey(schemaId);
                    return consulKvService.putValue(consulKey, schemaContent)
                            .flatMap(success -> {
                                if (Boolean.TRUE.equals(success)) {
                                    log.info("Successfully saved schema with ID '{}' to Consul key '{}'", schemaId, consulKey);
                                    return Mono.empty();
                                } else {
                                    log.error("Consul putValue failed for key '{}' (schema ID '{}')", consulKey, schemaId);
                                    return Mono.error(new RuntimeException("Failed to save schema to Consul for ID: " + schemaId));
                                }
                            });
                });
    }

    public Mono<String> getSchemaContent(@NonNull String schemaId) {
        if (StringUtils.isEmpty(schemaId)) {
            return Mono.error(new IllegalArgumentException("Schema ID cannot be empty."));
        }
        String consulKey = getSchemaKey(schemaId);
        log.debug("Attempting to get schema content for ID '{}' from Consul key '{}'", schemaId, consulKey);
        return consulKvService.getValue(consulKey)
                .flatMap(contentOpt -> {
                    if (contentOpt.isPresent() && !contentOpt.get().isBlank()) {
                        log.debug("Found schema content for ID: {}", schemaId);
                        return Mono.just(contentOpt.get());
                    } else {
                        log.warn("Schema not found (Optional empty or content blank) for ID '{}' at key '{}'", schemaId, consulKey);
                        return Mono.error(new SchemaNotFoundException("Schema not found for ID: " + schemaId + " at key: " + consulKey));
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Schema not found (source Mono from getValue was empty) for ID '{}' at key '{}'", schemaId, consulKey);
                    return Mono.error(new SchemaNotFoundException("Schema not found for ID: " + schemaId + " at key: " + consulKey));
                }));
    }

    public Mono<Void> deleteSchema(@NonNull String schemaId) {
        if (StringUtils.isEmpty(schemaId)) {
            return Mono.error(new IllegalArgumentException("Schema ID cannot be empty."));
        }
        String consulKey = getSchemaKey(schemaId);
        log.info("Attempting to delete schema with ID '{}' from Consul key '{}'", schemaId, consulKey);

        return consulKvService.getValue(consulKey) // Mono<Optional<String>>
                .filter(contentOpt -> contentOpt.isPresent() && !contentOpt.get().isBlank())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Schema not found for deletion (getValue was empty or blank) for ID '{}' at key '{}'", schemaId, consulKey);
                    return Mono.error(new SchemaNotFoundException("Cannot delete. Schema not found for ID: " + schemaId));
                }))
                .flatMap(contentOpt -> // contentOpt is guaranteed to be present and not blank here
                        consulKvService.deleteKey(consulKey) // Mono<Boolean>
                                .flatMap(success -> {
                                    if (Boolean.TRUE.equals(success)) {
                                        log.info("Successfully deleted schema with ID '{}' from Consul key '{}'", schemaId, consulKey);
                                        return Mono.empty(); // Signal successful deletion completion
                                    } else {
                                        log.error("Consul deleteKey command returned false for key '{}' (schema ID '{}')", consulKey, schemaId);
                                        return Mono.error(new RuntimeException("Failed to delete schema from Consul (delete command unsuccessful) for ID: " + schemaId));
                                    }
                                })
                                .onErrorResume(deleteError -> { // Catch errors specifically from deleteKey operation
                                    log.error("Error during Consul deleteKey operation for schema ID '{}' (key '{}'): {}", schemaId, consulKey, deleteError.getMessage(), deleteError);
                                    return Mono.error(new SchemaDeleteException("Error deleting schema from Consul for ID: " + schemaId, deleteError)); // Wrap the original error
                                })
                )
                .then(); // Ensures Mono<Void> and propagates errors (including wrapped ones or SchemaNotFoundException)
    }


    public Mono<List<String>> listSchemaIds() {
        log.debug("Listing schema IDs from Consul prefix '{}'", fullSchemaKvPrefix);
        return consulKvService.getKeysWithPrefix(fullSchemaKvPrefix)
                .map(keys -> {
                    if (keys == null) { // Defensive null check
                        return Collections.<String>emptyList();
                    }
                    return keys.stream()
                            .map(key -> key.startsWith(this.fullSchemaKvPrefix) ? key.substring(this.fullSchemaKvPrefix.length()) : key)
                            .map(key -> key.endsWith("/") ? key.substring(0, key.length() - 1) : key)
                            .filter(StringUtils::isNotEmpty)
                            .distinct()
                            .sorted() // Explicitly sort for consistent results
                            .collect(Collectors.toList());
                })
                .doOnSuccess(ids -> log.debug("Found {} schema IDs", ids.size()))
                .onErrorResume(e -> {
                    log.error("Error listing schema keys from Consul under prefix '{}': {}", fullSchemaKvPrefix, e.getMessage(), e);
                    return Mono.just(Collections.<String>emptyList());
                });
    }

    public Mono<Set<ValidationMessage>> validateSchemaSyntax(@NonNull String schemaContent) {
        if (StringUtils.isEmpty(schemaContent)) {
            return Mono.just(Set.of(ValidationMessage.builder().message("Schema content cannot be empty.").build()));
        }
        
        // First check if it's valid JSON
        return schemaValidationService.isValidJson(schemaContent)
                .flatMap(isValid -> {
                    if (!isValid) {
                        return Mono.just(Set.of(ValidationMessage.builder().message("Invalid JSON syntax").build()));
                    }
                    
                    // Now validate it as a JSON Schema using the centralized service
                    // We need to check if it's a valid JSON Schema v7
                    return Mono.fromCallable(() -> {
                        try {
                            JsonNode schemaNodeToValidate = objectMapper.readTree(schemaContent);
                            
                            // Use the pre-loaded this.metaSchema to validate the user's schema (schemaNodeToValidate)
                            ExecutionContext executionContext = this.metaSchema.createExecutionContext();
                            
                            ValidationContext metaSchemaVC = this.metaSchema.getValidationContext();
                            PathType pathTypeForRoot = metaSchemaVC.getConfig().getPathType();
                            if (pathTypeForRoot == null) {
                                pathTypeForRoot = PathType.LEGACY; // Fallback, should be set by metaSchemaLoadingConfig
                            }
                            
                            Set<ValidationMessage> messages = this.metaSchema.validate(
                                    executionContext,
                                    schemaNodeToValidate,      // The node to validate (the user's schema)
                                    schemaNodeToValidate,      // The root node of the instance (which is the user's schema itself)
                                    new JsonNodePath(pathTypeForRoot) // Instance location starts at root
                            );
                            
                            if (messages.isEmpty()) {
                                log.trace("Schema syntax appears valid and compliant with meta-schema.");
                                return Collections.<ValidationMessage>emptySet();
                            } else {
                                String errors = messages.stream()
                                        .map(ValidationMessage::getMessage)
                                        .collect(Collectors.joining("; "));
                                log.warn("Invalid JSON Schema structure (failed meta-schema validation) for content. Errors: {}", errors);
                                return messages;
                            }
                            
                        } catch (Exception e) {
                            log.warn("Error during schema syntax validation: {}", e.getMessage(), e);
                            return Set.of(ValidationMessage.builder().message("Error during schema syntax validation: " + e.getMessage()).build());
                        }
                    });
                });
    }

    /**
     * Validates a JSON content against a JSON Schema.
     *
     * @param jsonContent   The JSON content to validate
     * @param schemaContent The JSON Schema content to validate against
     * @return A Mono that emits a Set of ValidationMessage objects if validation fails, or an empty Set if validation succeeds
     */
    public Mono<Set<ValidationMessage>> validateContentAgainstSchema(@NonNull String jsonContent, @NonNull String schemaContent) {
        return Mono.fromCallable(() -> {
            if (StringUtils.isEmpty(jsonContent)) {
                return Set.of(ValidationMessage.builder().message("JSON content cannot be empty.").build());
            }
            if (StringUtils.isEmpty(schemaContent)) {
                return Set.of(ValidationMessage.builder().message("Schema content cannot be empty.").build());
            }

            try {
                // Parse the JSON content and schema
                JsonNode jsonNode = objectMapper.readTree(jsonContent);
                JsonNode schemaNode = objectMapper.readTree(schemaContent);

                // Create a JsonSchema instance from the schema content
                JsonSchema schema = schemaFactory.getSchema(schemaNode, getSchemaValidationConfig());

                // Validate the JSON content against the schema
                Set<ValidationMessage> messages = schema.validate(jsonNode);

                if (messages.isEmpty()) {
                    log.trace("JSON content is valid against the schema.");
                    return Collections.emptySet();
                } else {
                    String errors = messages.stream()
                            .map(ValidationMessage::getMessage)
                            .collect(Collectors.joining("; "));
                    log.warn("JSON content validation failed against schema. Errors: {}", errors);
                    return messages;
                }
            } catch (JsonProcessingException e) {
                log.warn("Invalid JSON syntax: {}", e.getMessage());
                return Set.of(ValidationMessage.builder().message("Invalid JSON syntax: " + e.getMessage()).build());
            } catch (Exception e) {
                log.warn("Error during JSON content validation: {}", e.getMessage(), e);
                return Set.of(ValidationMessage.builder().message("Error during JSON content validation: " + e.getMessage()).build());
            }
        });
    }

    private String getSchemaKey(String schemaId) {
        return this.fullSchemaKvPrefix + schemaId;
    }
}
