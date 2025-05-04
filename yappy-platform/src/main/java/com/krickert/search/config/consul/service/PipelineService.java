// File: micronaut-multiproject-example-new/consul-config-service/src/main/java/com/krickert/search/config/consul/service/PipelineService.java
package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.event.ConfigChangeNotifier;
import com.krickert.search.config.consul.exception.PipelineNotFoundException;
import com.krickert.search.config.consul.exception.PipelineVersionConflictException;
import com.krickert.search.config.consul.exception.SchemaValidationException;
import com.krickert.search.config.consul.model.*;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service layer for managing pipeline configurations.
 * Encapsulates the business logic previously in PipelineController.
 */
@Singleton
public class PipelineService {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineConfig pipelineConfigManager; // Renamed for clarity vs. DTO
    private final ConsulKvService consulKvService;
    private final ConfigChangeNotifier configChangeNotifier;

    @Inject
    public PipelineService(
            PipelineConfig pipelineConfigManager,
            ConsulKvService consulKvService,
            ConfigChangeNotifier configChangeNotifier) {
        this.pipelineConfigManager = pipelineConfigManager;
        this.consulKvService = consulKvService;
        this.configChangeNotifier = configChangeNotifier;
        LOG.info("PipelineService initialized");
    }

    /**
     * Lists all pipeline names.
     *
     * @return a Mono containing a list of pipeline names
     */
    public Mono<List<String>> listPipelines() {
        LOG.debug("Listing all pipeline names");
        try {
            String configPrefix = consulKvService.getFullPath("pipeline.configs.");
            return consulKvService.getKeysWithPrefix(configPrefix)
                    .map(keys -> {
                        Set<String> pipelineNames = new HashSet<>();
                        if (keys != null && !keys.isEmpty()) {
                            for (String key : keys) {
                                String relativeKey = key.startsWith(configPrefix) ? key.substring(configPrefix.length()) : key;
                                String[] parts = relativeKey.split("[./]");
                                if (parts.length >= 1 && !parts[0].isEmpty()) {
                                    pipelineNames.add(parts[0]);
                                }
                            }
                        }
                        LOG.debug("Found {} distinct potential pipeline names from keys", pipelineNames.size());
                        pipelineNames.addAll(pipelineConfigManager.getPipelines().keySet());
                        List<String> sortedPipelineNames = pipelineNames.stream().sorted().collect(Collectors.toList());
                        LOG.debug("Returning sorted list of {} pipelines", sortedPipelineNames.size());
                        return sortedPipelineNames;
                    })
                    .onErrorResume(e -> {
                         LOG.error("Error retrieving pipeline names from Consul", e);
                         List<String> memoryKeys = pipelineConfigManager.getPipelines().keySet().stream().sorted().collect(Collectors.toList());
                         LOG.warn("Returning {} pipeline names from memory cache only due to Consul error", memoryKeys.size());
                         return Mono.just(memoryKeys);
                    });
        } catch (Exception e) {
            LOG.error("Unexpected synchronous error listing pipelines", e);
            return Mono.error(e);
        }
    }

    /**
     * Creates a new pipeline.
     *
     * @param request the request containing the pipeline name
     * @return a Mono containing the created pipeline configuration DTO
     */
    public Mono<PipelineConfigDto> createPipeline(CreatePipelineRequest request) {
         // Wrap the logic that might throw synchronous exceptions in Mono.defer
        return Mono.defer(() -> {
            String pipelineName = request.getName();
            LOG.info("Creating pipeline: {}", pipelineName);

            if (pipelineName == null || pipelineName.trim().isEmpty()) {
                throw new IllegalArgumentException("Pipeline name cannot be empty");
            }

            if (pipelineConfigManager.getPipelines().containsKey(pipelineName)) {
                LOG.warn("Pipeline already exists in cache: {}", pipelineName);
                throw new PipelineVersionConflictException(pipelineName, 0, 1, LocalDateTime.now());
            }

            PipelineConfigDto newPipeline = new PipelineConfigDto(pipelineName);

            // addOrUpdatePipeline can also throw PipelineVersionConflictException synchronously
            return pipelineConfigManager.addOrUpdatePipeline(newPipeline)
                .flatMap(success -> {
                    if (success) {
                        LOG.info("Successfully created and saved pipeline: {}", pipelineName);
                        String configKeyPrefix = consulKvService.getFullPath("pipeline.configs." + pipelineName);
                        configChangeNotifier.notifyConfigChange(configKeyPrefix);
                        PipelineConfigDto createdDto = pipelineConfigManager.getPipeline(pipelineName);
                        return createdDto != null ? Mono.just(createdDto) : Mono.error(new RuntimeException("Created pipeline disappeared"));
                    } else {
                        LOG.error("Failed to save pipeline configuration: {}", pipelineName);
                        return Mono.error(new RuntimeException("Failed to save pipeline configuration"));
                    }
                });
        })
        // --- Error Handling ---
        // Allow specific exceptions (thrown synchronously above or emitted by inner chain) to propagate.
        .onErrorResume(PipelineVersionConflictException.class, Mono::error)
        .onErrorResume(IllegalArgumentException.class, Mono::error)
        // Catch any other unexpected errors last
        .onErrorResume(e -> {
            if (!(e instanceof PipelineVersionConflictException || e instanceof IllegalArgumentException)) {
                LOG.error("Unexpected error during pipeline creation: {}", request.getName(), e);
            }
            // Let Micronaut's default handler manage unexpected RuntimeExceptions
            return Mono.error(e);
        });
    }

    /**
     * Gets a specific pipeline configuration.
     * Signals PipelineNotFoundException if not found.
     *
     * @param pipelineName the name of the pipeline to retrieve
     * @return a Mono containing the pipeline configuration DTO, or signaling PipelineNotFoundException.
     */
     public Mono<PipelineConfigDto> getPipeline(String pipelineName) {
         LOG.info("Getting pipeline: {}", pipelineName);
         return Mono.defer(() -> { // Defer synchronous calls
             try {
                 PipelineConfigDto pipeline = pipelineConfigManager.getPipeline(pipelineName);
                 if (pipeline != null) {
                     LOG.debug("Found pipeline '{}' in manager (cache or loaded from Consul)", pipelineName);
                     return Mono.just(pipeline);
                 } else {
                     LOG.debug("Pipeline '{}' not found in manager after checking cache and Consul.", pipelineName);
                     // Throw the specific exception synchronously; defer will turn it into Mono.error
                     throw new PipelineNotFoundException("Pipeline not found: " + pipelineName);
                 }
             } catch (PipelineNotFoundException pnfe) {
                 throw pnfe; // Re-throw specifically to be caught by onErrorResume below
             } catch (Exception e) {
                 LOG.error("Error retrieving pipeline: {}", pipelineName, e);
                 // Wrap other errors
                 throw new RuntimeException("Error retrieving pipeline: " + e.getMessage(), e);
             }
         })
         // --- Error Handling ---
         .onErrorResume(PipelineNotFoundException.class, Mono::error) // Let not found propagate
         .onErrorResume(e -> { // Handle other unexpected errors
              if (!(e instanceof PipelineNotFoundException)) {
                  LOG.error("Unexpected error in getPipeline: {}", pipelineName, e);
              }
              // Let default handler manage other runtime errors
              return Mono.error(e);
           });
     }

    /**
     * Deletes a pipeline configuration.
     *
     * @param pipelineName the name of the pipeline to delete
     * @return a Mono that emits true if deletion was successful, signals error otherwise.
     */
     public Mono<Boolean> deletePipeline(String pipelineName) {
         LOG.info("Deleting pipeline: {}", pipelineName);

         // Use getPipeline which returns Mono<PipelineConfigDto> or signals PipelineNotFoundException
         return getPipeline(pipelineName)
             .flatMap(existingPipeline -> { // Only executes if getPipeline is successful
                 String configKeyPrefix = consulKvService.getFullPath("pipeline.configs." + pipelineName);
                 LOG.debug("Attempting to delete keys with prefix: {}", configKeyPrefix);
                 return consulKvService.deleteKeysWithPrefix(configKeyPrefix)
                         .flatMap(success -> {
                             if (success) {
                                 LOG.info("Successfully deleted pipeline keys from Consul: {}", pipelineName);
                                 pipelineConfigManager.getPipelines().remove(pipelineName);
                                 LOG.debug("Removed pipeline '{}' from memory cache", pipelineName);
                                 configChangeNotifier.notifyConfigChange(configKeyPrefix);
                                 return Mono.just(true);
                             } else {
                                 LOG.error("Failed to delete pipeline keys from Consul: {}", pipelineName);
                                 return Mono.error(new RuntimeException("Failed to delete pipeline from Consul KV"));
                             }
                         });
             })
             // --- Error Handling ---
             // Allow PipelineNotFoundException from getPipeline to propagate
             .onErrorResume(PipelineNotFoundException.class, Mono::error)
             // Handle other unexpected errors from deleteKeysWithPrefix or subsequent logic
             .onErrorResume(e -> {
                  if (!(e instanceof PipelineNotFoundException)) {
                      LOG.error("Unexpected error during pipeline deletion: {}", pipelineName, e);
                  }
                  // Let Micronaut's default handler manage unexpected RuntimeExceptions
                  return Mono.error(e);
              });
     }


    /**
     * Updates a pipeline configuration.
     *
     * @param pipelineName    the name of the pipeline to update
     * @param updatedPipeline the updated pipeline configuration DTO (must contain correct expected version)
     * @return a Mono containing the updated pipeline configuration DTO
     */
    public Mono<PipelineConfigDto> updatePipeline(String pipelineName, PipelineConfigDto updatedPipeline) {
        LOG.info("Updating pipeline: {}", pipelineName);

        // Use getPipeline which now returns Mono<PipelineConfigDto> or signals PipelineNotFoundException
        return getPipeline(pipelineName)
            .flatMap(existingPipeline -> // Only executes if getPipeline is successful
                 // Wrap the potentially synchronous throwing call in Mono.defer
                 Mono.defer(() -> {
                     // --- Input Validation ---
                     if (!pipelineName.equals(updatedPipeline.getName())) {
                         LOG.warn("Pipeline name in path ({}) doesn't match name in body ({}). Using name from path.",
                                 pipelineName, updatedPipeline.getName());
                         updatedPipeline.setName(pipelineName);
                     }
                     // --- >>> SERVICE CONFIGURATION VALIDATION <<< ---
                     for (Map.Entry<String, PipeStepConfigurationDto> entry : updatedPipeline.getServices().entrySet()) {
                         String serviceName = entry.getKey();
                         PipeStepConfigurationDto serviceConfig = entry.getValue();

                         if (serviceConfig.getJsonConfig() != null) {
                             JsonConfigOptions jsonOptions = serviceConfig.getJsonConfig();
                             LOG.debug("Validating JsonConfigOptions for service: {}", serviceName);
                             // Call validateConfig. It might throw JsonProcessingException internally if JSON is malformed,
                             // or return false if schema validation fails.
                             // Let DefaultSchemaServiceConfig handle logging the specific errors.
                             if (!jsonOptions.validateConfig()) {
                                 String validationErrors = jsonOptions.getValidationErrors(); // Get the specific error message(s)
                                 LOG.warn("Schema validation failed for service {} in pipeline {}: {}",
                                         serviceName, pipelineName, validationErrors);
                                 // Throw exception with the specific error message from the validator
                                 throw new SchemaValidationException(
                                         "Validation failed for service " + serviceName + ": " + validationErrors,
                                         // Pass the detailed message in the Set as well if desired, or keep it simple
                                         Set.of(validationErrors != null ? validationErrors : "Validation failed.")
                                 );
                             }
                             // If validateConfig throws an internal parsing exception (like JsonParseException),
                             // it should ideally be caught and handled within DefaultSchemaServiceConfig or allowed
                             // to propagate up to be caught by a more general handler (like CombinedSchemaExceptionHandler's base case).
                             // Removing the broad catch block here allows the specific SchemaValidationException to propagate cleanly.

                             LOG.debug("JsonConfigOptions validation passed for service: {}", serviceName);
                         }
                         // TODO: Add validation for other config types if necessary
                     }
                     // --- >>> END SERVICE CONFIGURATION VALIDATION <<< ---

                     // --- Attempt Update ---
                     return pipelineConfigManager.addOrUpdatePipeline(updatedPipeline)
                             .flatMap(success -> {
                             if (success) {
                                 LOG.info("Successfully updated and persisted pipeline: {}", pipelineName);
                                 String configKeyPrefix = consulKvService.getFullPath("pipeline.configs." + pipelineName);
                                 configChangeNotifier.notifyConfigChange(configKeyPrefix);
                                 PipelineConfigDto resultDto = pipelineConfigManager.getPipeline(pipelineName);
                                 return resultDto != null ? Mono.just(resultDto) : Mono.error(new RuntimeException("Updated pipeline disappeared unexpectedly"));
                             } else {
                                 LOG.error("Update persistence attempt for pipeline '{}' returned false.", pipelineName);
                                 // When CAS operation fails, it's likely due to a version conflict
                                 // Get the latest version from Consul to include in the exception
                                 PipelineConfigDto latestPipeline = pipelineConfigManager.getPipeline(pipelineName);
                                 return Mono.error(new PipelineVersionConflictException(
                                     pipelineName,
                                     updatedPipeline.getPipelineVersion(),
                                     latestPipeline != null ? latestPipeline.getPipelineVersion() : updatedPipeline.getPipelineVersion() + 1,
                                     latestPipeline != null ? latestPipeline.getPipelineLastUpdated() : LocalDateTime.now()
                                 ));
                             }
                         });
                 })
            )
            // --- Error Handling for the entire operation ---
            .onErrorResume(PipelineNotFoundException.class, Mono::error)
            .onErrorResume(PipelineVersionConflictException.class, Mono::error)
            .onErrorResume(IllegalArgumentException.class, Mono::error)
            .onErrorResume(e -> { // Catch-all for truly unexpected errors
                 if (!(e instanceof PipelineNotFoundException ||
                       e instanceof PipelineVersionConflictException ||
                       e instanceof IllegalArgumentException)) {
                     LOG.error("Unexpected error during pipeline update: {}", pipelineName, e);
                 }
                 // Let Micronaut's default handler manage unexpected errors
                 return Mono.error(e);
             });
    }
}
