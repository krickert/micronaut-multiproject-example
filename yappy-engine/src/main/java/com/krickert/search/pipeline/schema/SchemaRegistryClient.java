   // Suggested location: yappy-engine/src/main/java/com/krickert/search/pipeline/schema/SchemaRegistryClient.java
   package com.krickert.search.pipeline.schema;

   import com.krickert.search.config.pipeline.model.SchemaReference; // Assuming this is the correct package
   import io.micronaut.core.annotation.Nullable;

   import java.util.Optional;

   /**
    * Client interface for interacting with a schema registry
    * to fetch schema definitions.
    */
   public interface SchemaRegistryClient {

       /**
        * Fetches a schema definition based on its reference.
        *
        * @param schemaRef The reference (subject/artifactId, version, and optional group) to the schema.
        * @return An Optional containing the schema content as a String if found, otherwise empty.
        */
       Optional<String> getSchema(SchemaReference schemaRef);

       /**
        * Fetches a schema definition by artifact ID, optionally with a specific version and group.
        * If version is null, the latest version is typically fetched.
        * If groupId is null, a default group might be assumed by the implementation.
        *
        * @param artifactId The unique identifier for the artifact/schema.
        * @param version    The specific version of the schema (can be "latest", a number, or null).
        * @param groupId    The group ID for the schema (can be null).
        * @return An Optional containing the schema content as a String if found, otherwise empty.
        */
       Optional<String> getSchema(String artifactId, @Nullable String version, @Nullable String groupId);
   }
   