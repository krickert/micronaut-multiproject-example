package com.krickert.search.config.schema.model.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper class for loading JSON schemas for tests.
 * This class provides methods for loading schema content from resources
 * and preparing them for registration with a schema registry.
 */
public class ConsulSchemaRegistryTestHelper {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Loads schema content from a resource file.
     *
     * @param resourceName The name of the resource file (relative to the classpath)
     * @return The schema content as a string
     * @throws RuntimeException if the resource cannot be loaded
     */
    public static String loadSchemaContent(String resourceName) {
        return TestSchemaLoader.loadSchemaContent(resourceName);
    }

    /**
     * Loads schema content from a resource file and parses it as a JsonNode.
     *
     * @param resourceName The name of the resource file (relative to the classpath)
     * @return The schema content as a JsonNode
     * @throws RuntimeException if the resource cannot be loaded or parsed
     */
    public static JsonNode loadSchemaAsJsonNode(String resourceName) {
        return TestSchemaLoader.loadSchemaAsJsonNode(resourceName);
    }

    /**
     * Gets a list of all JSON files in the "json" directory of the test resources.
     *
     * @return A list of filenames (without the path)
     */
    public static List<String> getSchemaFilesFromResources() {
        List<String> result = new ArrayList<>();

        try {
            // Try to get the physical path to the resources directory
            Path resourcesPath = Paths.get(ConsulSchemaRegistryTestHelper.class.getClassLoader().getResource("json").toURI());

            try (Stream<Path> paths = Files.walk(resourcesPath)) {
                result = paths
                        .filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(filename -> filename.endsWith(".json"))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.err.println("Could not access resources directory directly, trying alternative method: " + e.getMessage());

            // This is a fallback approach that works with resources in JARs
            // It's not ideal but should work for most cases
            String[] defaultSchemas = {
                    "pipeline-step-config-schema.json",
                    "pipeline-config-schema.json",
                    "pipeline-module-config-schema.json",
                    "pipeline-cluster-config-schema.json"
            };

            for (String schema : defaultSchemas) {
                if (ConsulSchemaRegistryTestHelper.class.getClassLoader().getResource("json/" + schema) != null) {
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
    public static String getSchemaIdFromFilename(String filename) {
        return filename.replace(".json", "");
    }

    /**
     * Loads all schemas from the "json" directory in the resources and returns them as a map.
     *
     * @return A map of schema IDs to schema contents
     */
    public static Map<String, String> loadAllSchemas() {
        Map<String, String> schemas = new HashMap<>();
        List<String> schemaFiles = getSchemaFilesFromResources();

        System.out.println("Found " + schemaFiles.size() + " schema files to load");

        for (String schemaFile : schemaFiles) {
            String schemaId = getSchemaIdFromFilename(schemaFile);
            String schemaContent = loadSchemaContent(schemaFile);
            schemas.put(schemaId, schemaContent);
            System.out.println("Loaded schema: " + schemaId + " from file: " + schemaFile);
        }

        return schemas;
    }

    /**
     * Loads a specific schema from the resources.
     *
     * @param resourceName The name of the resource file (relative to the classpath)
     * @return A map entry with the schema ID and content
     */
    public static Map.Entry<String, String> loadSchema(String resourceName) {
        String schemaId = getSchemaIdFromFilename(resourceName);
        String schemaContent = loadSchemaContent(resourceName);
        return Map.entry(schemaId, schemaContent);
    }
}
