package com.krickert.search.config.consul.schema.test;

import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.schema.model.test.TestSchemaLoader;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class is responsible for seeding the schema registry with test schemas.
 * It is used in integration tests to ensure that the necessary schemas are available
 * in Consul before the tests run.
 */
@Singleton
@Requires(env = "test")
public class SchemaRegistrySeeder {
    private static final Logger log = LoggerFactory.getLogger(SchemaRegistrySeeder.class);
    private final ConsulSchemaRegistryDelegate schemaRegistryDelegate;

    @Inject
    public SchemaRegistrySeeder(ConsulSchemaRegistryDelegate schemaRegistryDelegate) {
        this.schemaRegistryDelegate = schemaRegistryDelegate;
    }

    /**
     * Seeds the schema registry with all JSON schemas found in the test resources.
     * This method scans the classpath for JSON files in the "schemas" directory and
     * registers each one with the schema registry.
     *
     * @return A Mono that completes when all schemas have been registered
     */
    public Mono<Void> seedSchemas() {
        log.info("Seeding schema registry with test schemas");

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
                    String schemaContent = TestSchemaLoader.loadSchemaContent(schemaFile);

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
     * Gets a list of all JSON files in the "schemas" directory of the test resources.
     *
     * @return A list of filenames (without the path)
     */
    private List<String> getSchemaFilesFromResources() {
        List<String> result = new ArrayList<>();

        try {
            // Try to get the physical path to the resources directory
            Path resourcesPath = Paths.get(getClass().getClassLoader().getResource("schemas").toURI());

            try (Stream<Path> paths = Files.walk(resourcesPath)) {
                result = paths
                        .filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(filename -> filename.endsWith(".json"))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            // If we can't get the physical path, try to list resources from the JAR
            log.warn("Could not access resources directory directly, trying alternative method", e);

            // This is a fallback approach that works with resources in JARs
            // Include all known schema files from the resources directory
            String[] defaultSchemas = {
                    "avro-schema-type.json",
                    "backward-compatibility-schema.json",
                    "comprehensive-schema-registry-artifact.json",
                    "comprehensive-schema-version-data.json",
                    "forward-compatibility-schema.json",
                    "full-compatibility-schema.json",
                    "json-schema-type.json",
                    "minimal-schema-registry-artifact.json",
                    "minimal-schema-version-data.json",
                    "pipeline-steps-schema.json",
                    "protobuf-schema-type.json",
                    "schema-subject-1.json",
                    "pipeline-step-config-schema.json",
                    "pipeline-config-schema.json",
                    "pipeline-module-config-schema.json",
                    "pipeline-cluster-config-schema.json"
            };

            for (String schema : defaultSchemas) {
                if (getClass().getClassLoader().getResource("schemas/" + schema) != null) {
                    result.add(schema);
                }
            }
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
