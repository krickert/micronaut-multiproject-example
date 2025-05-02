package com.krickert.search.config.consul.seeding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.krickert.search.config.consul.model.ApplicationConfig;
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.io.ResourceLoader;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration; // Import Duration
import java.time.LocalDateTime;
import java.util.*;

/**
 * Seeds initial configuration data into Consul KV store when the application starts.
 * Reads configuration from a specified file and stores it in Consul KV store.
 * Uses a 'pipeline.seeded' flag to ensure it only runs once.
 * Auto-adds initial version (0) and lastUpdated metadata for seeded pipelines.
 */
@Singleton
// *** Use the corrected constant for @Requires ***
@Requires(property = ConsulDataSeeder.ENABLED_PROPERTY, value = "true", defaultValue = "false")
public class ConsulDataSeeder implements ApplicationEventListener<StartupEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulDataSeeder.class);

    private final ConsulKvService consulKvService;
    private final ResourceLoader resourceLoader;
    private final String seedFile;
    private final ApplicationConfig applicationConfig;
    private final PipelineConfig pipelineConfig;

    // --- *** CORRECTED CONSTANTS *** ---
    public static final String ENABLED_PROPERTY = "consul.data.seeding.enabled";
    public static final String FILE_PROPERTY = "consul.data.seeding.file";
    // --- *** END CORRECTIONS *** ---

    static final String SEEDED_FLAG_NAME = "pipeline.seeded";

    public ConsulDataSeeder(ConsulKvService consulKvService,
                           ResourceLoader resourceLoader,
                           // *** Use the corrected constant in @Value ***
                           @Value("${" + FILE_PROPERTY + ":classpath:seed-data.yaml}") String seedFile,
                           ApplicationConfig applicationConfig,
                           PipelineConfig pipelineConfig) {
        this.consulKvService = consulKvService;
        this.resourceLoader = resourceLoader;
        this.seedFile = seedFile;
        this.applicationConfig = applicationConfig;
        this.pipelineConfig = pipelineConfig;
        LOG.info("ConsulDataSeeder initialized with seed file: {}", seedFile);
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        LOG.info("ConsulDataSeeder processing StartupEvent...");
        try {
            LOG.info("Attempting to block on seedData()...");
            Boolean result = seedData().block(Duration.ofSeconds(30)); // Use existing block duration
            LOG.info("seedData().block() completed with result: {}", result); // Log after block returns

            if (result == Boolean.TRUE) {
                LOG.info("Consul data seeding process completed successfully or was already done (synchronous check).");
            } else {
                // This path means seeding was attempted but failed (including setting flag)
                LOG.error("Consul data seeding failed or timed out (result was not true). Failing startup.");
                throw new RuntimeException("ConsulDataSeeder failed to seed data or timed out.");
            }
        } catch (Exception e) {
            LOG.error("Exception during blocking Consul data seeding process via StartupEvent", e);
            throw new RuntimeException("ConsulDataSeeder failed to seed data due to exception.", e);
        }
        LOG.info("ConsulDataSeeder onApplicationEvent finished."); // Log at the very end
    }

    /**
     * Performs data seeding if needed (checks flag internally).
     * Returns true if seeding completed successfully OR if it was already seeded.
     * Returns false if seeding was attempted but failed.
     */
    public Mono<Boolean> seedData() {
        String seededFlagFullPath = consulKvService.getFullPath(SEEDED_FLAG_NAME);
        LOG.debug("Checking Consul KV store seeding status using flag '{}'...", seededFlagFullPath);

        return consulKvService.getValue(seededFlagFullPath)
               .defaultIfEmpty(Optional.empty())
               .flatMap(seededOpt -> {
                   if (seededOpt.isPresent() && "true".equals(seededOpt.get())) {
                       LOG.info("Configuration already seeded ('{}'=true found). Skipping seeding logic.", seededFlagFullPath);
                       applicationConfig.markAsEnabled();
                       pipelineConfig.markAsEnabled();
                       return Mono.just(true); // Success (already done)
                   } else {
                       LOG.info("Configuration not seeded or flag '{}' not true/found, proceeding with seeding.", seededFlagFullPath);
                       return doActualSeeding(); // Perform the seeding steps
                   }
               })
               .onErrorResume(e -> {
                   LOG.error("Error checking seed status using flag '{}'. Attempting to seed anyway.", seededFlagFullPath, e);
                   return doActualSeeding();
               });
    }

    private Mono<Boolean> doActualSeeding() {
        LOG.info("Starting actual seeding from file: {}", seedFile);
        Optional<InputStream> seedStreamOpt = resourceLoader.getResourceAsStream(seedFile);
        if (seedStreamOpt.isEmpty()) {
            LOG.error("Seed file not found: {}. Seeding failed.", seedFile);
            return Mono.just(false);
        }
        try (InputStream seedStream = seedStreamOpt.get()) {
            Properties seedProperties = loadPropertiesFromStream(seedStream);
            if (seedProperties.isEmpty()) {
                 LOG.warn("No properties loaded from seed file: {}. Setting seeded flag only.", seedFile);
                 return setSeededFlagAndMarkBeans(0, 0);
            }
            LOG.info("Loaded {} properties from seed file: {}", seedProperties.size(), seedFile);
            addDefaultPipelineMetadata(seedProperties); // Add version:0 and lastUpdated:now
            return seedProperties(seedProperties) // seedProperties just writes all keys
                    .flatMap(count -> setSeededFlagAndMarkBeans(count, seedProperties.size()));
        } catch (IOException e) {
            LOG.error("Error loading or processing seed file: {}", seedFile, e);
            return Mono.error(e);
        } catch (Exception e) {
            LOG.error("Unexpected error during seeding preparation for file: {}", seedFile, e);
            return Mono.just(false);
        }
    }

    private Properties loadPropertiesFromStream(InputStream inputStream) throws IOException {
         Properties seedProperties = new Properties();
         if (seedFile.endsWith(".yaml") || seedFile.endsWith(".yml")) {
             ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
             Map<String, Object> yamlMap = yamlMapper.readValue(inputStream, Map.class);
             Map<String, String> flattenedMap = new HashMap<>();
             flattenYamlMap(yamlMap, "", flattenedMap);
             seedProperties.putAll(flattenedMap);
         } else {
             seedProperties.load(inputStream);
         }
         return seedProperties;
    }

    private void flattenYamlMap(Map<String, Object> yamlMap, String prefix, Map<String, String> flattenedMap) {
        for (Map.Entry<String, Object> entry : yamlMap.entrySet()) {
            String currentKey = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenYamlMap((Map<String, Object>) value, currentKey, flattenedMap);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    String listKey = currentKey + "[" + i + "]";
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        flattenYamlMap((Map<String, Object>) item, listKey, flattenedMap);
                    } else {
                        flattenedMap.put(listKey, item != null ? item.toString() : "");
                    }
                }
            } else {
                flattenedMap.put(currentKey, value != null ? value.toString() : "");
            }
        }
    }

    private void addDefaultPipelineMetadata(Properties properties) {
         Set<String> pipelineNames = new HashSet<>();
         final String prefix = "pipeline.configs.";
         for (String key : properties.stringPropertyNames()) {
             if (key.startsWith(prefix)) {
                 String remaining = key.substring(prefix.length());
                 int dotIndex = remaining.indexOf('.');
                 if (dotIndex > 0) {
                     pipelineNames.add(remaining.substring(0, dotIndex));
                 }
             }
         }
         if (pipelineNames.isEmpty()) return;
         LOG.info("Found pipelines from seed file: {}. Ensuring default version (0) and lastUpdated metadata.", pipelineNames);
         String nowTimestamp = LocalDateTime.now().toString();
         for (String pipelineName : pipelineNames) {
             String versionKey = prefix + pipelineName + ".version";
             String lastUpdatedKey = prefix + pipelineName + ".lastUpdated";
             if (properties.getProperty(versionKey) == null) {
                 properties.setProperty(versionKey, "0"); // Initial version 0
                 LOG.debug("Added default metadata key: {} = 0", versionKey);
             }
             if (properties.getProperty(lastUpdatedKey) == null) {
                 properties.setProperty(lastUpdatedKey, nowTimestamp);
                 LOG.debug("Added default metadata key: {} = {}", lastUpdatedKey, nowTimestamp);
             }
         }
         LOG.info("Finished adding default metadata. Total properties to seed now: {}", properties.size());
     }

    private Mono<Long> seedProperties(Properties properties) {
        Map<String, String> valuesToSeed = new HashMap<>();
        properties.stringPropertyNames().forEach(key -> valuesToSeed.put(key, properties.getProperty(key)));
        if (valuesToSeed.isEmpty()) return Mono.just(0L);
        LOG.info("Attempting to seed {} properties...", valuesToSeed.size());
        return Flux.fromIterable(valuesToSeed.entrySet())
                .flatMap(entry -> {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    String fullPath = consulKvService.getFullPath(key);
                    LOG.trace("Seeding key: {} = {}", fullPath, value);
                    return consulKvService.putValue(fullPath, value);
                })
                .filter(Boolean::booleanValue)
                .count();
    }

    private Mono<Boolean> setSeededFlagAndMarkBeans(long seededCount, int totalAttempted) {
        // ... (existing logs) ...
        String seededFlagFullPath = consulKvService.getFullPath(SEEDED_FLAG_NAME);
        LOG.info("Setting seeded flag '{}' to true", seededFlagFullPath);
        return consulKvService.putValue(seededFlagFullPath, "true")
                // --- Add Logging Here ---
                .doOnSuccess(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        LOG.info("ConsulKvService.putValue for flag '{}' reported SUCCESS (returned true)", seededFlagFullPath);
                    } else {
                        LOG.warn("ConsulKvService.putValue for flag '{}' reported FAILURE (returned false/empty)", seededFlagFullPath);
                    }
                })
                // --- End Added Logging ---
                .flatMap(flagSetSuccess -> {
                    if (flagSetSuccess) {
                        LOG.info("Seeded flag set successfully. Marking local beans as enabled.");
                        applicationConfig.markAsEnabled();
                        pipelineConfig.markAsEnabled();
                        return Mono.just(true);
                    } else {
                        LOG.warn("Failed to set seeded flag '{}' in Consul (based on flatMap).", seededFlagFullPath);
                        return Mono.just(false); // Indicate failure to set flag
                    }
                })
                .defaultIfEmpty(false) // Explicitly return false if the putValue mono was empty
                .doOnError(e -> LOG.error("Error occurred during reactive chain for setting flag '{}'", seededFlagFullPath, e)) // Add error logging too
                .onErrorResume(e -> {
                    // Logged error above, now return false indicator
                    return Mono.just(false);
                });
    }
}