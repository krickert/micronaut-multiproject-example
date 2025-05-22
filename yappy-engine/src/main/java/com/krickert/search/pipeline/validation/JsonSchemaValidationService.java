//   // Suggested location: yappy-engine/src/main/java/com/krickert/search/pipeline/validation/JsonSchemaValidationService.java
//   package com.krickert.search.pipeline.validation;
//
//   import com.fasterxml.jackson.databind.JsonNode;
//   import com.krickert.search.config.pipeline.model.SchemaReference;
//   import com.krickert.search.pipeline.schema.SchemaRegistryClient;
//   import com.networknt.schema.JsonSchema;
//   import com.networknt.schema.JsonSchemaFactory;
//   import com.networknt.schema.SchemaValidatorsConfig;
//   import com.networknt.schema.SpecVersion;
//   import com.networknt.schema.ValidationMessage;
//   import jakarta.inject.Singleton;
//   import org.slf4j.Logger;
//   import org.slf4j.LoggerFactory;
//
//   import java.util.Collections;
//   import java.util.Optional;
//   import java.util.Set;
//   import java.util.stream.Collectors;
//
//   @Singleton
//   public class JsonSchemaValidationService {
//
//       private static final Logger LOG = LoggerFactory.getLogger(JsonSchemaValidationService.class);
//       private final SchemaRegistryClient schemaRegistryClient;
//       private final JsonSchemaFactory schemaFactory;
//
//       public JsonSchemaValidationService(SchemaRegistryClient schemaRegistryClient) {
//           this.schemaRegistryClient = schemaRegistryClient;
//           // Specify the JSON Schema version you are using (e.g., Draft 7)
//           this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
//       }
//
//       public record ValidationResult(boolean isValid, Set<String> messages) {
//           public static ValidationResult success() {
//               return new ValidationResult(true, Collections.emptySet());
//           }
//       }
//
//       public ValidationResult validate(JsonNode configToValidate, SchemaReference schemaRef) {
//           if (schemaRef == null) {
//               LOG.debug("No schema reference provided for validation. Assuming valid or no schema required.");
//               return ValidationResult.success(); // Or handle as an error if a schema is always expected
//           }
//           if (configToValidate == null) {
//               LOG.warn("Config to validate is null for schema: {}", schemaRef.toIdentifier());
//               return new ValidationResult(false, Set.of("Configuration to validate is null."));
//           }
//
//           Optional<String> schemaStringOpt = schemaRegistryClient.getSchema(schemaRef);
//
//           if (schemaStringOpt.isEmpty()) {
//               String message = String.format("Schema '%s' not found in registry. Cannot validate configuration.", schemaRef.toIdentifier());
//               LOG.warn(message);
//               return new ValidationResult(false, Set.of(message));
//           }
//
//           try {
//               JsonSchema schema = schemaFactory.getSchema(schemaStringOpt.get());
//               SchemaValidatorsConfig config = new SchemaValidatorsConfig();
//               // config.setFailFast(true); // Optional: stop on first error
//
//               Set<ValidationMessage> validationMessages = schema.validate(configToValidate, config);
//
//               if (validationMessages.isEmpty()) {
//                   LOG.debug("Custom config successfully validated against schema: {}", schemaRef.toIdentifier());
//                   return ValidationResult.success();
//               } else {
//                   Set<String> errorMessages = validationMessages.stream()
//                           .map(ValidationMessage::getMessage)
//                           .collect(Collectors.toSet());
//                   LOG.warn("Custom config validation failed against schema: {}. Errors: {}", schemaRef.toIdentifier(), errorMessages);
//                   return new ValidationResult(false, errorMessages);
//               }
//           } catch (Exception e) {
//               String message = String.format("Error during JSON schema validation for schema '%s': %s", schemaRef.toIdentifier(), e.getMessage());
//               LOG.error(message, e);
//               return new ValidationResult(false, Set.of(message));
//           }
//       }
//   }
//