package com.krickert.search.config.consul.service;

 import com.fasterxml.jackson.core.JsonProcessingException;
 import com.fasterxml.jackson.databind.JsonNode;
 import com.fasterxml.jackson.databind.ObjectMapper;
 import com.krickert.search.config.consul.exception.SchemaNotFoundException;
 import com.krickert.search.config.consul.exception.SchemaValidationException;
 import com.krickert.search.config.consul.service.ConsulKvService;
 import com.networknt.schema.JsonSchema;
 import com.networknt.schema.JsonSchemaFactory;
 import com.networknt.schema.SchemaValidatorsConfig;
 import com.networknt.schema.SpecVersion;
 import com.networknt.schema.ValidationMessage;
 import io.micronaut.context.annotation.Requires;
 import io.micronaut.core.annotation.NonNull;
 import io.micronaut.core.util.StringUtils;
 import jakarta.inject.Singleton;
 import lombok.extern.slf4j.Slf4j;
 import reactor.core.publisher.Mono;

 import java.nio.charset.StandardCharsets;
 import java.util.*;
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
      * Stores the JSON schema definition for a given service implementation name in Consul.
      * The schema is stored Base64 encoded.
      *
      * @param serviceImplementationName The unique name identifying the service implementation.
      * @param schemaJson                The JSON schema as a string.
      * @return Mono<Boolean> indicating success.
      */
     public Mono<Boolean> saveSchema(@NonNull String serviceImplementationName, @NonNull String schemaJson) {
         if (StringUtils.isEmpty(serviceImplementationName) || StringUtils.isEmpty(schemaJson)) {
             return Mono.error(new IllegalArgumentException("Service implementation name and schema JSON cannot be empty."));
         }
         // Validate if the input is valid JSON before saving
         try {
             objectMapper.readTree(schemaJson);
         } catch (JsonProcessingException e) {
             log.error("Invalid JSON provided for schema {}: {}", serviceImplementationName, e.getMessage());
             return Mono.error(new IllegalArgumentException("Schema definition is not valid JSON.", e));
         }

         String key = getSchemaConsulKey(serviceImplementationName);
         String encodedSchema = Base64.getEncoder().encodeToString(schemaJson.getBytes(StandardCharsets.UTF_8));

         return consulKvService.putValue(key, encodedSchema)
                 .doOnSuccess(success -> {
                     if (Boolean.TRUE.equals(success)) {
                         log.info("Saved schema for service implementation: {}", serviceImplementationName);
                         // Invalidate cache on successful save
                         schemaCache.remove(serviceImplementationName);
                     } else {
                         log.warn("Failed to save schema for service implementation: {}", serviceImplementationName);
                     }
                 });
     }
    // --- Ensure they return Mono<Void> or signal specific errors. ---


     /**
      * Retrieves the JSON schema definition string for a service implementation.
      * Returns Mono<String> emitting the schema JSON if found.
      * Signals SchemaNotFoundException if not found in Consul.
      * Signals RuntimeException on decoding errors or other Consul issues.
      */
     public Mono<String> getSchemaJson(@NonNull String serviceImplementationName) {
         String key = getSchemaConsulKey(serviceImplementationName);
         log.debug("Attempting to get schema JSON for key: {}", key);

         // Fetch Mono<Optional<String>> from Consul KV Service
         return consulKvService.getValue(key)
                 .flatMap(encodedSchemaOpt -> {
                     // This lambda executes if the getValue Mono emits the Optional
                     if (encodedSchemaOpt.isPresent()) {
                         // Value found in Consul
                         try {
                             byte[] decodedBytes = Base64.getDecoder().decode(encodedSchemaOpt.get());
                             String schemaJson = new String(decodedBytes, StandardCharsets.UTF_8);
                             log.debug("Successfully decoded schema for key: {}", key);
                             // Return Mono emitting the String result
                             return Mono.just(schemaJson);
                         } catch (IllegalArgumentException e) {
                             log.error("Failed to decode Base64 schema for key '{}': {}", key, e.getMessage());
                             // Signal specific error for decoding failure
                             return Mono.error(new RuntimeException("Failed to decode schema for " + serviceImplementationName, e));
                         }
                     } else {
                         // Value not found in Consul (Optional was empty)
                         log.warn("Schema not found in Consul for key: {}", key);
                         // Signal the specific not found exception
                         return Mono.error(new SchemaNotFoundException(serviceImplementationName));
                     }
                 })
                 .switchIfEmpty(Mono.defer(() -> {
                     // This part executes ONLY if the initial consulKvService.getValue(key) Mono completes without emitting anything
                     // (e.g., if the service method itself returned empty Mono, which is unlikely for KV lookup)
                     log.warn("Schema not found for key '{}' (upstream Mono was empty)", key);
                     // Still signal SchemaNotFoundException
                     return Mono.error(new SchemaNotFoundException(serviceImplementationName));
                 }));
     }

     // Method to retrieve the compiled schema (used by validateConfig)
     // Ensures it returns Mono<Optional<JsonSchema>> correctly
     public Mono<Optional<JsonSchema>> getSchema(@NonNull String serviceImplementationName) {
         if (schemaCache.containsKey(serviceImplementationName)) {
             return Mono.just(schemaCache.get(serviceImplementationName));
         }
         // Use the now correct getSchemaJson logic
         return getSchemaJson(serviceImplementationName)
             .flatMap(schemaJson -> {
                 // Compile the schema if JSON is successfully retrieved
                 try {
                     JsonSchema schema = schemaFactory.getSchema(schemaJson, schemaValidatorsConfig);
                     Optional<JsonSchema> result = Optional.of(schema);
                     schemaCache.put(serviceImplementationName, result); // Cache success
                     log.debug("Compiled and cached schema for {}", serviceImplementationName);
                     return Mono.just(result);
                 } catch (Exception e) {
                      log.error("Failed to compile fetched schema for {}, proceeding without cache update", serviceImplementationName, e);
                      // Treat compile failure as an internal error
                      return Mono.error(new RuntimeException("Failed to compile schema for " + serviceImplementationName, e));
                 }
             })
             .onErrorResume(SchemaNotFoundException.class, e -> {
                 // If getSchemaJson signaled NotFound, cache empty and return empty Optional Mono
                 log.debug("Schema not found for {}, caching empty Optional.", serviceImplementationName);
                 Optional<JsonSchema> result = Optional.empty();
                 schemaCache.put(serviceImplementationName, result);
                 return Mono.just(result);
             });
             // Let other RuntimeExceptions propagate
     }



     /**
      * Deletes a schema. Checks for existence first to signal SchemaNotFoundException correctly.
      *
      * @param serviceImplementationName The service implementation name.
      * @return Mono<Void> completing on successful deletion, or signaling error (e.g., SchemaNotFoundException).
      */
     public Mono<Void> deleteSchema(@NonNull String serviceImplementationName) {
         String key = getSchemaConsulKey(serviceImplementationName);
         log.debug("Attempting to delete schema with key: {}", key);

         // 1. Check if key exists using getValue (returns Mono<Optional<String>>)
         return consulKvService.getValue(key)
                 .flatMap(valueOpt -> {
                     if (valueOpt.isPresent()) {
                         // 2. Key exists, attempt deletion using deleteKey (returns Mono<Boolean>)
                         return consulKvService.deleteKey(key)
                                 .flatMap(deleted -> {
                                     if (Boolean.TRUE.equals(deleted)) {
                                         // Deletion successful
                                         log.info("Deleted schema for service implementation: {}", serviceImplementationName);
                                         schemaCache.remove(serviceImplementationName); // Invalidate cache
                                         return Mono.empty(); // Success -> Complete Mono<Void>
                                     } else {
                                         // deleteKey returned false, indicating an unexpected error during delete attempt
                                         log.error("ConsulKvService.deleteKey returned false for existing key: {}", key);
                                         return Mono.error(new RuntimeException("Failed to delete existing schema key: " + key));
                                     }
                                 });
                     } else {
                         // 3. Key doesn't exist, signal NotFound
                         log.warn("Schema not found for deletion: {}", serviceImplementationName);
                         return Mono.error(new SchemaNotFoundException(serviceImplementationName));
                     }
                 });
         // Let RuntimeExceptions from initial getValue or subsequent deleteKey error propagate
     }

    // Example: Corrected validateConfig
     public Mono<Void> validateConfig(@NonNull String serviceImplementationName, Map<String, Object> configParams) {
         if (configParams == null) {
             configParams = Collections.emptyMap();
         }
         final Map<String, Object> finalConfigParams = configParams; // For lambda

         return getSchema(serviceImplementationName) // Uses the corrected getSchema
                 .flatMap(schemaOpt -> {
                     if (schemaOpt.isPresent()) {
                         JsonSchema schema = schemaOpt.get();
                         try {
                             JsonNode configNode = objectMapper.valueToTree(finalConfigParams);
                             Set<ValidationMessage> messages = schema.validate(configNode);
                             if (messages.isEmpty()) {
                                 return Mono.empty(); // Valid
                             } else {
                                 Set<String> messageStrings = messages.stream()
                                         .map(ValidationMessage::getMessage)
                                         .collect(Collectors.toSet());
                                 log.warn("Schema validation failed for {}: {}", serviceImplementationName, messageStrings);
                                 return Mono.error(new SchemaValidationException(serviceImplementationName, messageStrings)); // Signal validation error
                             }
                         } catch (Exception e) {
                             log.error("Error during config validation execution for {}: {}", serviceImplementationName, e.getMessage());
                             return Mono.error(new RuntimeException("Internal error during schema validation for " + serviceImplementationName, e)); // Signal internal error
                         }
                     } else {
                         log.debug("No schema found for {}, validation implicitly passes.", serviceImplementationName);
                         return Mono.empty(); // No schema -> Valid
                     }
                 });
    }


     private String getSchemaConsulKey(String serviceImplementationName) {
         return SCHEMA_CONFIG_PREFIX + serviceImplementationName.replaceAll("[^a-zA-Z0-9._-]", "_");
     }
 }