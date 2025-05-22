   // Suggested location: yappy-engine/src/main/java/com/krickert/search/pipeline/schema/apicurio/ApicurioSchemaRegistryClient.java
   package com.krickert.search.pipeline.schema.apicurio;

   import com.krickert.search.config.pipeline.model.SchemaReference;
   import com.krickert.search.pipeline.schema.SchemaRegistryClient;
   import io.micronaut.context.annotation.Requires;
   import io.micronaut.context.annotation.Value;
   import io.micronaut.core.annotation.Nullable;
   import io.micronaut.http.HttpRequest;
   import io.micronaut.http.client.HttpClient;
   import io.micronaut.http.client.annotation.Client;
   import io.micronaut.http.client.exceptions.HttpClientResponseException;
   import io.micronaut.http.uri.UriBuilder;
   import jakarta.inject.Singleton;
   import org.slf4j.Logger;
   import org.slf4j.LoggerFactory;

   import java.net.URI;
   import java.util.Optional;

   @Singleton
   @Requires(property = "kafka.schema.registry.type", value = "apicurio") // Or a new property like "config.schema.registry.type"
   public class ApicurioSchemaRegistryClient implements SchemaRegistryClient {

       private static final Logger LOG = LoggerFactory.getLogger(ApicurioSchemaRegistryClient.class);
       private final HttpClient httpClient;
       private final String registryUrl;
       private final String defaultGroupId;

       public ApicurioSchemaRegistryClient(
               @Client HttpClient httpClient, // Generic HTTP client
               @Value("${apicurio.registry.url}") String registryUrl, // Reuse existing property
               @Value("${apicurio.registry.default-group-id:default}") String defaultGroupId) {
           this.httpClient = httpClient;
           // Ensure registryUrl doesn't end with a slash for consistent UriBuilder usage
           this.registryUrl = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
           this.defaultGroupId = defaultGroupId;
           LOG.info("ApicurioSchemaRegistryClient initialized with URL: {} and default group: {}", this.registryUrl, this.defaultGroupId);
       }

       @Override
       public Optional<String> getSchema(SchemaReference schemaRef) {
           String versionStr = (schemaRef.version() != null) ? schemaRef.version().toString() : "latest";
           // Assuming schemaRef.subject() is the artifactId. Apicurio doesn't strongly use subject in the same way as Confluent for versions.
           // Group ID could also be part of SchemaReference if needed, or we use a default.
           return getSchema(schemaRef.subject(), versionStr, this.defaultGroupId);
       }

       @Override
       public Optional<String> getSchema(String artifactId, @Nullable String version, @Nullable String groupId) {
           if (artifactId == null || artifactId.isBlank()) {
               LOG.warn("Artifact ID is null or blank, cannot fetch schema.");
               return Optional.empty();
           }
           String effectiveGroupId = (groupId == null || groupId.isBlank()) ? this.defaultGroupId : groupId;
           String effectiveVersion = (version == null || version.isBlank()) ? "latest" : version;

           // Path for Apicurio Registry API v2: /apis/registry/v2/groups/{groupId}/artifacts/{artifactId}/versions/{version}
           // Or for latest: /apis/registry/v2/groups/{groupId}/artifacts/{artifactId} (Apicurio often redirects this or has /versions/latest)
           // Let's try the explicit version path first.
           UriBuilder uriBuilder = UriBuilder.of(this.registryUrl)
                   .path("apis").path("registry").path("v2").path("groups")
                   .path(effectiveGroupId).path("artifacts").path(artifactId);

           if ("latest".equalsIgnoreCase(effectiveVersion)) {
               // Apicurio might use /versions/latest or just the artifact path for the latest content
               // Let's try the direct artifact path which often returns the latest version's content
               // or use /versions/latest explicitly if preferred.
               // For simplicity and common behavior, /versions/latest is more explicit.
                uriBuilder.path("versions").path("latest");
           } else {
               uriBuilder.path("versions").path(effectiveVersion);
           }
           // The content itself is usually at the root of this versioned resource, not under a /content sub-path for GET.
           // For Apicurio, getting the version resource directly often returns the schema content.

           URI uri = uriBuilder.build();
           LOG.debug("Fetching schema from Apicurio: {}", uri);

           try {
               HttpRequest<?> request = HttpRequest.GET(uri).accept("application/json", "application/x-protobuf", "application/x-avro"); // Accept common schema types
               String schemaContent = httpClient.toBlocking().retrieve(request, String.class);
               return Optional.ofNullable(schemaContent);
           } catch (HttpClientResponseException e) {
               if (e.getStatus().getCode() == 404) {
                   LOG.warn("Schema not found in Apicurio. Group: '{}', ArtifactId: '{}', Version: '{}'. URI: {}. Status: {}",
                           effectiveGroupId, artifactId, effectiveVersion, uri, e.getStatus());
               } else {
                   LOG.error("Error fetching schema from Apicurio. Group: '{}', ArtifactId: '{}', Version: '{}'. URI: {}. Status: {}, Response: {}",
                           effectiveGroupId, artifactId, effectiveVersion, uri, e.getStatus(), e.getResponse().getBody(String.class).orElse("N/A"), e);
               }
               return Optional.empty();
           } catch (Exception e) {
               LOG.error("Generic error fetching schema from Apicurio. Group: '{}', ArtifactId: '{}', Version: '{}'. URI: {}.",
                       effectiveGroupId, artifactId, effectiveVersion, uri, e);
               return Optional.empty();
           }
       }
   }
   