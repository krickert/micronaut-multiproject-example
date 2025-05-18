package com.krickert.search.config.consul.schema.util;

import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class for initializing the schema registry with schemas from test resources.
 * This class is used to load schemas from the pipeline-config-models-test-utils project
 * and register them with the ConsulSchemaRegistryDelegate.
 */
@Singleton
public class SchemaRegistryInitializer {
    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryInitializer.class);
    private final ConsulSchemaRegistryDelegate schemaRegistryDelegate;

    @Inject
    public SchemaRegistryInitializer(ConsulSchemaRegistryDelegate schemaRegistryDelegate) {
        this.schemaRegistryDelegate = schemaRegistryDelegate;
    }

    /**
     * Initializes the schema registry with all JSON schemas found in the test resources.
     * This method scans the classpath for JSON files in the "json" directory and
     * registers each one with the schema registry.
     *
     * @return A Mono that completes when all schemas have been registered
     */
    public Mono<Void> initializeSchemas() {
        log.info("Initializing schema registry with schemas from test resources");

        // Get all JSON files from the test resources
        List<String> schemaFiles = getSchemaFilesFromResources();

        if (schemaFiles.isEmpty()) {
            log.warn("No schema files found in resources");
            return Mono.empty();
        }

        log.info("Found {} schema files to register", schemaFiles.size());

        // Register each schema
        return Flux.fromIterable(schemaFiles)
                .flatMap(schemaFile -> {
                    String schemaId = getSchemaIdFromFilename(schemaFile);
                    String schemaContent = loadSchemaContent(schemaFile);

                    log.info("Registering schema: {} from file: {}", schemaId, schemaFile);

                    return schemaRegistryDelegate.saveSchema(schemaId, schemaContent)
                            .onErrorResume(e -> {
                                log.error("Failed to register schema: {} from file: {}", schemaId, schemaFile, e);
                                return Mono.empty();
                            });
                })
                .then();
    }

    /**
     * Gets a list of all JSON files in the "json" directory of the test resources.
     *
     * @return A list of filenames (without the path)
     */
    private List<String> getSchemaFilesFromResources() {
        List<String> result = new ArrayList<>();

        try {
            // Try to get the physical path to the resources directory
            URL resourceUrl = getClass().getClassLoader().getResource("json");
            if (resourceUrl != null) {
                try {
                    URI resourceUri = resourceUrl.toURI();
                    Path resourcesPath = Paths.get(resourceUri);

                    try (Stream<Path> paths = Files.walk(resourcesPath)) {
                        result = paths
                                .filter(Files::isRegularFile)
                                .map(Path::getFileName)
                                .map(Path::toString)
                                .filter(filename -> filename.endsWith(".json"))
                                .collect(Collectors.toList());
                    }
                } catch (URISyntaxException | IOException e) {
                    log.warn("Could not access resources directory directly, trying alternative method", e);
                    // Fall through to the fallback approach
                }
            }

            // If we couldn't get the physical path or no files were found, try to list resources from the JAR
            if (result.isEmpty()) {
                log.warn("No schema files found using direct file access, trying alternative method");

                // This is a fallback approach that works with resources in JARs
                // It's not ideal but should work for most cases
                String[] defaultSchemas = {
                        "pipeline-steps-schema.json",
                        "minimal-pipeline-cluster-config.json",
                        "comprehensive-pipeline-cluster-config.json",
                        "json-schema-type.json",
                        "avro-schema-type.json",
                        "protobuf-schema-type.json",
                        "backward-compatibility-schema.json",
                        "forward-compatibility-schema.json",
                        "full-compatibility-schema.json"
                };

                for (String schema : defaultSchemas) {
                    if (getClass().getClassLoader().getResource("json/" + schema) != null) {
                        result.add(schema);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error getting schema files from resources", e);
        }

        return result;
    }

    /**
     * Converts a filename to a schema ID by removing the .json extension.
     *
     * @param filename The filename
     * @return The schema ID
     */
    private String getSchemaIdFromFilename(String filename) {
        return filename.replace(".json", "");
    }

}
