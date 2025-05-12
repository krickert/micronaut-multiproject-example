// In ConsulSchemaRegistryDelegate.java
package com.krickert.search.schema.registry.delegate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.schema.registry.exception.SchemaNotFoundException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage; // Correct import
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class ConsulSchemaRegistryDelegate {

    private static final Logger log = LoggerFactory.getLogger(ConsulSchemaRegistryDelegate.class);
    private final String fullSchemaKvPrefix;

    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;

    @Inject
    public ConsulSchemaRegistryDelegate(
            ConsulKvService consulKvService,
            ObjectMapper objectMapper,
            @Value("${consul.client.config.path:config/pipeline}") String baseConfigPath) {
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper.copy();
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

        String sanitizedBaseConfigPath = baseConfigPath.endsWith("/") ? baseConfigPath : baseConfigPath + "/";
        this.fullSchemaKvPrefix = sanitizedBaseConfigPath + "schemas/";
        log.info("ConsulSchemaRegistryDelegate initialized, using Consul KV prefix: {}", this.fullSchemaKvPrefix);
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

    // In ConsulSchemaRegistryDelegate.java
    public Mono<String> getSchemaContent(@NonNull String schemaId) {
        if (StringUtils.isEmpty(schemaId)) {
            return Mono.error(new IllegalArgumentException("Schema ID cannot be empty."));
        }
        String consulKey = getSchemaKey(schemaId);
        log.debug("Attempting to get schema content for ID '{}' from Consul key '{}'", schemaId, consulKey);

        return consulKvService.getValue(consulKey) // Returns Mono<Optional<String>>
                .flatMap(contentOpt -> {
                    if (contentOpt.isPresent() && !contentOpt.get().isBlank()) {
                        log.debug("Found schema content for ID: {}", schemaId);
                        return Mono.just(contentOpt.get());
                    } else {
                        log.warn("Schema not found (Optional empty or content blank) for ID '{}' at key '{}'", schemaId, consulKey);
                        return Mono.error(new SchemaNotFoundException("Schema not found for ID: " + schemaId + " at key: " + consulKey));
                    }
                })
                .switchIfEmpty(Mono.defer(() -> { // <-- ADDED THIS
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

        return consulKvService.getValue(consulKey)
                .flatMap(contentOpt -> {
                    if (contentOpt.isEmpty() || contentOpt.get().isBlank()) {
                        log.warn("Schema not found for deletion for ID '{}' at key '{}'", schemaId, consulKey);
                        return Mono.error(new SchemaNotFoundException("Cannot delete. Schema not found for ID: " + schemaId));
                    }
                    return consulKvService.deleteKey(consulKey)
                            .flatMap(success -> {
                                if (Boolean.TRUE.equals(success)) {
                                    log.info("Delete command successful for schema ID '{}' (key '{}')", schemaId, consulKey);
                                    return Mono.empty();
                                } else {
                                    log.error("Consul deleteKey returned false for schema ID '{}' (key '{}')", schemaId, consulKey);
                                    return Mono.error(new RuntimeException("Failed to delete schema from Consul for ID: " + schemaId));
                                }
                            });
                })
                .onErrorResume(e -> {
                    if (e instanceof SchemaNotFoundException) {
                        return Mono.error(e);
                    }
                    log.error("Error during delete operation for schema ID '{}' (key '{}'): {}", schemaId, consulKey, e.getMessage(), e);
                    return Mono.error(new RuntimeException("Error deleting schema from Consul for ID: " + schemaId, e));
                }).then();
    }

    public Mono<List<String>> listSchemaIds() {
        log.debug("Listing schema IDs from Consul prefix '{}'", fullSchemaKvPrefix);
        return consulKvService.getKeysWithPrefix(fullSchemaKvPrefix)
                .map(keys -> keys.stream()
                        .map(key -> key.startsWith(this.fullSchemaKvPrefix) ? key.substring(this.fullSchemaKvPrefix.length()) : key)
                        .map(key -> key.endsWith("/") ? key.substring(0, key.length() - 1) : key)
                        .filter(StringUtils::isNotEmpty)
                        .distinct()
                        .collect(Collectors.toList()))
                .doOnSuccess(ids -> log.debug("Found {} schema IDs", ids.size()))
                .onErrorResume(e -> {
                    log.error("Error listing schema keys from Consul: {}", e.getMessage(), e);
                    return Mono.just(Collections.emptyList());
                });
    }

    public Mono<Set<ValidationMessage>> validateSchemaSyntax(@NonNull String schemaContent) {
        return Mono.fromCallable(() -> {
            if (StringUtils.isEmpty(schemaContent)) {
                log.warn("Schema content is empty.");
                return Set.of(ValidationMessage.builder().message("Schema content cannot be empty.").build());
            }
            try {
                log.debug("Attempting to parse schema content: {}", schemaContent.length() > 200 ? schemaContent.substring(0, 200) + "..." : schemaContent);
                JsonNode schemaNode = objectMapper.readTree(schemaContent);

                // This call is expected to throw JsonSchemaException (or a subclass/related exception)
                // if the schema is fundamentally malformed according to the meta-schema or spec
                // (e.g., "type": 123 should cause an issue here if the library is strict).
                // For cases like unknown keywords ("invalid_prop": "object"}), it might only log a warning
                // and successfully return a JsonSchema instance if the library is configured to be lenient.
                JsonSchema schema = schemaFactory.getSchema(schemaNode); // Attempt to parse and create the schema object

                // If schemaFactory.getSchema() SUCCEEDS without throwing an exception,
                // we consider the schema definition "valid enough" for the library to have parsed it.
                // Any non-fatal issues (like unknown keywords) would have been logged as warnings by networknt-schema.
                // We are not further validating the 'schema' object against a meta-schema here because
                // JsonSchema.java you provided does not have a public instance method for that returning Set<ValidationMessage>.
                log.debug("Schema content successfully parsed by JsonSchemaFactory for: {}",
                        schemaContent.length() > 200 ? schemaContent.substring(0, 200) + "..." : schemaContent);
                return Collections.emptySet(); // No fatal parsing/structural errors were caught by exception.

            } catch (JsonProcessingException e) { // For malformed JSON (not valid JSON at all)
                log.warn("Invalid JSON syntax for schema content. Input: [{}], Error: {}", schemaContent, e.getMessage());
                return Set.of(ValidationMessage.builder().message("Invalid JSON syntax: " + e.getMessage()).build());
            } catch (Exception e) { // Catches JsonSchemaException or other schema parsing issues from getSchema()
                log.warn("Invalid JSON Schema structure or other parsing error for schema content. Input: [{}], ErrorType: {}, Error: {}",
                        schemaContent.length() > 200 ? schemaContent.substring(0, 200) + "..." : schemaContent,
                        e.getClass().getName(),
                        e.getMessage(),
                        e); // Log the actual exception
                return Set.of(ValidationMessage.builder().message("Invalid JSON Schema structure: " + e.getMessage()).build());
            }
        });
    }


    private String getSchemaKey(String schemaId) {
        return this.fullSchemaKvPrefix + schemaId;
    }
}