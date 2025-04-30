package com.krickert.search.config.consul.seeding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seeds initial configuration data into Consul KV store when the application starts.
 * This class loads configuration from a properties file and stores it in Consul KV store.
 */
@Singleton
@Requires(property = "consul.data.seeding.enabled", value = "true", defaultValue = "true")
public class ConsulDataSeeder implements ApplicationEventListener<StartupEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulDataSeeder.class);

    private final ConsulKvService consulKvService;
    private final ResourceLoader resourceLoader;
    private final String seedFile;
    private final boolean skipIfExists;

    /**
     * Creates a new ConsulDataSeeder with the specified services and configuration.
     *
     * @param consulKvService the service for interacting with Consul KV store
     * @param resourceLoader the loader for loading resources
     * @param seedFile the path to the seed file
     * @param skipIfExists whether to skip seeding if the key already exists
     */
    public ConsulDataSeeder(ConsulKvService consulKvService,
                           ResourceLoader resourceLoader,
                           @Value("${consul.data.seeding.file:seed-data.yaml}") String seedFile,
                           @Value("${consul.data.seeding.skip-if-exists:true}") boolean skipIfExists) {
        this.consulKvService = consulKvService;
        this.resourceLoader = resourceLoader;
        this.seedFile = seedFile;
        this.skipIfExists = skipIfExists;
        LOG.info("ConsulDataSeeder initialized with seed file: {}, skipIfExists: {}", seedFile, skipIfExists);
    }

    /**
     * Handles the StartupEvent by seeding initial configuration data.
     *
     * @param event the startup event
     */
    @Override
    public void onApplicationEvent(StartupEvent event) {
        LOG.info("Application startup detected, checking if Consul KV store needs seeding");

        // Load the seed file
        Optional<InputStream> seedStream = resourceLoader.getResourceAsStream(seedFile);
        if (seedStream.isEmpty()) {
            LOG.warn("Seed file not found: {}", seedFile);
            return;
        }

        try {
            // Check if the seed file is YAML or properties
            if (seedFile.endsWith(".yaml") || seedFile.endsWith(".yml")) {
                // Parse YAML file
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                Map<String, Object> yamlMap = yamlMapper.readValue(seedStream.get(), Map.class);

                // Flatten the YAML structure
                Map<String, String> flattenedMap = new HashMap<>();
                flattenYamlMap(yamlMap, "", flattenedMap);

                LOG.info("Loaded {} properties from YAML seed file: {}", flattenedMap.size(), seedFile);

                // Convert to Properties
                Properties seedProperties = new Properties();
                seedProperties.putAll(flattenedMap);

                // Seed the properties into Consul KV store
                seedProperties(seedProperties)
                        .subscribe(
                                count -> LOG.info("Successfully seeded {} properties into Consul KV store", count),
                                error -> LOG.error("Error seeding properties into Consul KV store", error)
                        );
            } else {
                // Load properties from the seed file (for backward compatibility)
                Properties seedProperties = new Properties();
                seedProperties.load(seedStream.get());
                LOG.info("Loaded {} properties from properties seed file: {}", seedProperties.size(), seedFile);

                // Seed the properties into Consul KV store
                seedProperties(seedProperties)
                        .subscribe(
                                count -> LOG.info("Successfully seeded {} properties into Consul KV store", count),
                                error -> LOG.error("Error seeding properties into Consul KV store", error)
                        );
            }
        } catch (IOException e) {
            LOG.error("Error loading seed file: {}", seedFile, e);
        }
    }

    /**
     * Flattens a hierarchical YAML map into a flat map with dot-separated keys.
     *
     * @param yamlMap the hierarchical map from YAML
     * @param prefix the current key prefix
     * @param flattenedMap the resulting flattened map
     */
    private void flattenYamlMap(Map<String, Object> yamlMap, String prefix, Map<String, String> flattenedMap) {
        for (Map.Entry<String, Object> entry : yamlMap.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                // Recursively flatten nested maps
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                flattenYamlMap(nestedMap, key, flattenedMap);
            } else if (value instanceof List) {
                // Handle lists
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        // Recursively flatten nested maps in lists
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nestedMap = (Map<String, Object>) item;
                        flattenYamlMap(nestedMap, key + "[" + i + "]", flattenedMap);
                    } else {
                        // Add list item directly
                        flattenedMap.put(key + "[" + i + "]", item != null ? item.toString() : "");
                    }
                }
            } else {
                // Add simple key-value pair
                flattenedMap.put(key, value != null ? value.toString() : "");
            }
        }
    }

    /**
     * Seeds properties into Consul KV store.
     *
     * @param properties the properties to seed
     * @return a Mono that completes when all properties have been seeded
     */
    private Mono<Integer> seedProperties(Properties properties) {
        AtomicInteger count = new AtomicInteger(0);

        return Flux.fromIterable(properties.stringPropertyNames())
                .flatMap(key -> {
                    String value = properties.getProperty(key);

                    // Check if the key already exists
                    if (skipIfExists) {
                        return consulKvService.getValue(consulKvService.getFullPath(key))
                                .flatMap(existingValue -> {
                                    if (existingValue.isPresent()) {
                                        LOG.debug("Key already exists, skipping: {}", key);
                                        return Mono.just(false);
                                    } else {
                                        LOG.debug("Seeding key: {} = {}", key, value);
                                        return consulKvService.putValue(consulKvService.getFullPath(key), value);
                                    }
                                });
                    } else {
                        LOG.debug("Seeding key: {} = {}", key, value);
                        return consulKvService.putValue(consulKvService.getFullPath(key), value);
                    }
                })
                .filter(Boolean::booleanValue) // Only count successful puts
                .doOnNext(success -> count.incrementAndGet())
                .then(Mono.just(count.get()));
    }
}
