package com.krickert.search.config.consul.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.exception.SchemaNotFoundException;
import com.krickert.search.config.consul.exception.SchemaValidationException;
import com.networknt.schema.*;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Singleton
@Requires(beans = ConsulKvService.class)
public class SchemaService {

    private static final String SCHEMA_CONFIG_PREFIX = "config/pipeline/schemas/";
    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    private final SchemaValidatorsConfig schemaValidatorsConfig;
    private final Map<String, Optional<JsonSchema>> schemaCache = new ConcurrentHashMap<>();

    public SchemaService(ConsulKvService consulKvService, ObjectMapper objectMapper) {
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper.copy();
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schemaValidatorsConfig = new SchemaValidatorsConfig();
        this.schemaValidatorsConfig.setHandleNullableField(true);
    }

    /**
     * Reactive save. Checks empty, validates JSON, then puts.
     * Returns Mono<Void> on success, signals error otherwise.
     */
    public Mono<Void> saveSchema(@NonNull String serviceImplementationName, @NonNull String schemaJson) {
        // 1. Check empty first
        if (StringUtils.isEmpty(serviceImplementationName) || StringUtils.isEmpty(schemaJson)) {
            log.warn("Service implementation name or schema JSON is empty for PUT request.");
            return Mono.error(new IllegalArgumentException("Service implementation name and schema JSON cannot be empty."));
        }

        // 2. Validate JSON syntax
        try {
            objectMapper.readTree(schemaJson);
            log.debug("Schema JSON syntax is valid for {}", serviceImplementationName);
        } catch (JsonProcessingException e) {
            log.error("Invalid JSON provided for schema {}: {}", serviceImplementationName, e.getMessage());
            return Mono.error(new IllegalArgumentException("Schema definition is not valid JSON.", e));
        }

        // 3. Proceed with reactive put if JSON is valid
        String key = getSchemaConsulKey(serviceImplementationName);
        String encodedSchema = Base64.getEncoder().encodeToString(schemaJson.getBytes(StandardCharsets.UTF_8));

        return consulKvService.putValue(key, encodedSchema)
            .doOnSuccess(success -> {
                if (Boolean.TRUE.equals(success)) {
                    log.info("Saved schema for service implementation: {}", serviceImplementationName);
                    schemaCache.remove(serviceImplementationName); // Invalidate cache
                } else {
                    log.warn("Failed to save schema (putValue returned false) for service implementation: {}", serviceImplementationName);
                    // Signal an error if putValue returns false unexpectedly
                    throw new RuntimeException("Consul KV put operation failed for key: " + key);
                }
            })
            .doOnError(error -> log.error("Error saving schema for {}: {}", serviceImplementationName, error.getMessage()))
            .then(); // Convert Mono<Boolean> to Mono<Void> on success
    }

    /** Reactive get schema JSON - unchanged */
    public Mono<String> getSchemaJson(@NonNull String serviceImplementationName) {
        String key = getSchemaConsulKey(serviceImplementationName);
        log.debug("Attempting to get schema JSON for key: {}", key);
        return consulKvService.getValue(key)
            .flatMap(encodedSchemaOpt -> {
                if (encodedSchemaOpt.isPresent()) {
                    try {
                        byte[] decodedBytes = Base64.getDecoder().decode(encodedSchemaOpt.get());
                        String schemaJson = new String(decodedBytes, StandardCharsets.UTF_8);
                        log.debug("Successfully decoded schema for key: {}", key);
                        return Mono.just(schemaJson);
                    } catch (IllegalArgumentException e) {
                        log.error("Failed to decode Base64 schema for key '{}': {}", key, e.getMessage());
                        return Mono.error(new RuntimeException("Failed to decode schema for " + serviceImplementationName, e));
                    }
                } else {
                    log.warn("Schema not found in Consul for key: {}", key);
                    return Mono.error(new SchemaNotFoundException(serviceImplementationName));
                }
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("Schema not found for key '{}' (upstream Mono was empty)", key);
                return Mono.error(new SchemaNotFoundException(serviceImplementationName));
            }));
    }

    /** Reactive get compiled schema - unchanged */
     public Mono<Optional<JsonSchema>> getSchema(@NonNull String serviceImplementationName) {
         // Cache logic remains the same
         if (schemaCache.containsKey(serviceImplementationName)) {
             log.debug("Returning cached schema (present={}) for {}", schemaCache.get(serviceImplementationName).isPresent(), serviceImplementationName);
             return Mono.just(schemaCache.get(serviceImplementationName));
         }
         log.debug("Schema cache miss for {}, fetching from source.", serviceImplementationName);
         return getSchemaJson(serviceImplementationName)
             .flatMap(schemaJson -> {
                 try {
                     log.debug("Compiling schema JSON for {}", serviceImplementationName);
                     JsonSchema schema = schemaFactory.getSchema(schemaJson, schemaValidatorsConfig);
                     Optional<JsonSchema> result = Optional.of(schema);
                     schemaCache.put(serviceImplementationName, result);
                     log.debug("Compiled and cached schema for {}", serviceImplementationName);
                     return Mono.just(result);
                 } catch (Exception e) {
                      log.error("Failed to compile fetched schema for {}, proceeding without cache update", serviceImplementationName, e);
                      return Mono.error(new RuntimeException("Failed to compile schema for " + serviceImplementationName, e));
                 }
             })
             .onErrorResume(SchemaNotFoundException.class, e -> {
                 log.debug("Schema not found for {}, caching empty Optional.", serviceImplementationName);
                 Optional<JsonSchema> result = Optional.empty();
                 schemaCache.put(serviceImplementationName, result);
                 return Mono.just(result);
             });
     }

    /**
     * Reactive delete. Checks existence, then deletes.
     * Returns Mono<Void> on success, signals error otherwise.
     */
    public Mono<Void> deleteSchema(@NonNull String serviceImplementationName) {
        String key = getSchemaConsulKey(serviceImplementationName);
        log.debug("Attempting reactive delete schema with key: {}", key);

        return consulKvService.getValue(key) // Check existence first
            .flatMap(valueOpt -> {
                if (valueOpt.isPresent()) {
                    // Key exists, attempt deletion reactively
                    return consulKvService.deleteKey(key)
                        .flatMap(deleted -> {
                            if (Boolean.TRUE.equals(deleted)) {
                                log.info("Deleted schema for service implementation: {}", serviceImplementationName);
                                schemaCache.remove(serviceImplementationName); // Invalidate cache
                                return Mono.empty(); // Success -> Complete Mono<Void>
                            } else {
                                log.error("ConsulKvService.deleteKey returned false for existing key: {}", key);
                                return Mono.error(new RuntimeException("Failed to delete existing schema key: " + key));
                            }
                        });
                } else {
                    // Key doesn't exist, signal NotFound
                    log.warn("Schema not found for deletion: {}", serviceImplementationName);
                    return Mono.error(new SchemaNotFoundException(serviceImplementationName));
                }
            });
    }

    /**
     * Reactive validation. Returns Mono<Void> on success/no-schema,
     * signals Mono.error(SchemaValidationException) on validation failure.
     */
    public Mono<Void> validateConfigurationReactive(@NonNull String serviceImplementationName, @NonNull Map<String, Object> configParams) {
        // --- Existing Log ---
        log.debug(">>> Entering reactive service validation for: {}", serviceImplementationName);

        // --- NEW: Log the input configuration parameters ---
        // Use DEBUG level as this could be verbose. Careful if params contain sensitive info.
        log.debug("Input configuration parameters for [{}]: {}", serviceImplementationName, configParams);

        return getSchema(serviceImplementationName) // Returns Mono<Optional<JsonSchema>>
                .flatMap(schemaOpt -> {
                    if (schemaOpt.isPresent()) {
                        // Schema exists, perform validation
                        JsonSchema schema = schemaOpt.get();
                        // --- Existing NEW Log ---
                        log.debug("Using JsonSchema object [{}] (loaded for [{}]) for validation", schema.hashCode(), serviceImplementationName);

                        try {
                            log.debug(">>> Validating config against schema for {}", serviceImplementationName);
                            JsonNode configNode = objectMapper.valueToTree(configParams);

                            // --- ADD THIS LOG: Log the JsonNode structure ---
                            log.debug("Validating JsonNode structure for [{}]: {}", serviceImplementationName, configNode.toString());

                            // --- ADD THIS LOG: Log the compiled schema's internal node ---
                            // This might show if 'required' or 'type' are represented internally
                            log.debug("Compiled schema node details for [{}]: {}", serviceImplementationName, schema.getSchemaNode().toString());

                            // --- Your existing validation call ---
                            Set<ValidationMessage> messages = schema.validate(configNode);

                            // --- Existing NEW Log --- *CRITICAL*
                            log.debug("Raw validation messages returned for [{}] (Count: {}): {}",
                                    serviceImplementationName, messages.size(), messages);

                            // --- NEW: Log the JsonNode representation if helpful (can be verbose) ---
                            // try {
                            //     log.trace("Validating config JsonNode for [{}]: {}", serviceImplementationName, objectMapper.writeValueAsString(configNode));
                            // } catch (JsonProcessingException e) {
                            //     log.trace("Could not serialize configNode to string for logging: {}", e.getMessage());
                            // }
                            // --- END NEW ---

                            // --- Your existing logic based on results ---
                            if (messages.isEmpty()) {
                                // --- Existing Log ---
                                log.debug(">>> Validation successful for {}", serviceImplementationName);
                                return Mono.empty(); // Success -> Complete Mono<Void>
                            } else {
                                // Validation failed
                                Set<String> messageStrings = messages.stream()
                                        .map(ValidationMessage::getMessage)
                                        .collect(Collectors.toSet());
                                // --- Existing Log (already good) ---
                                log.warn(">>> Schema validation failed for {}: {}", serviceImplementationName, messageStrings);
                                // Signal validation error
                                return Mono.error(new SchemaValidationException(serviceImplementationName, messageStrings));
                            }
                        } catch (Exception e) { // Catch broader exceptions during validation if needed
                            // --- Existing Log (already good) ---
                            log.error(">>> Error during validation execution for {}: {}", serviceImplementationName, e.getMessage(), e);
                            // Propagate as a runtime error
                            return Mono.error(new RuntimeException("Internal error during schema validation execution for " + serviceImplementationName, e));
                        }
                    } else {
                        // No schema found, implicitly valid
                        // --- Existing Log ---
                        log.debug(">>> No schema found for {}, validation implicitly passes.", serviceImplementationName);
                        return Mono.empty(); // Success -> Complete Mono<Void>
                    }
                });
        // Errors from getSchema (like compilation error, NotFound) will propagate if not caught by its own onErrorResume
    }


     private String getSchemaConsulKey(String serviceImplementationName) {
         return SCHEMA_CONFIG_PREFIX + serviceImplementationName.replaceAll("[^a-zA-Z0-9.\\-_]", "_");
     }
}