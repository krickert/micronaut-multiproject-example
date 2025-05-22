package com.krickert.search.config.pipeline.model.test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provides sample JSON for pipeline configuration models.
 * Includes both minimal and comprehensive examples, as well as edge cases.
 */
public class SamplePipelineConfigJson {

    private static final Map<String, String> JSON_CACHE = new TreeMap<>();

    /**
     * Returns a minimal valid PipelineClusterConfig JSON.
     * Contains a single pipeline with a single step.
     */
    public static String getMinimalPipelineClusterConfigJson() {
        return getJsonResource("minimal-pipeline-cluster-config.json");
    }

    /**
     * Returns a comprehensive PipelineClusterConfig JSON.
     * Contains multiple pipelines with multiple steps, including fan-in and fan-out examples.
     */
    public static String getComprehensivePipelineClusterConfigJson() {
        return getJsonResource("comprehensive-pipeline-cluster-config.json");
    }

    /**
     * Returns a PipelineClusterConfig JSON for a search indexing pipeline.
     * This pipeline processes documents and indexes them for search.
     */
    public static String getSearchIndexingPipelineJson() {
        return getJsonResource("search-indexing-pipeline.json");
    }

    /**
     * Returns a PipelineClusterConfig JSON for a data science analysis pipeline.
     * This pipeline processes data for analysis and machine learning.
     */
    public static String getDataScienceAnalysisPipelineJson() {
        return getJsonResource("data-science-analysis-pipeline.json");
    }

    /**
     * Returns a PipelineClusterConfig JSON for a PDF parser pipeline.
     * This pipeline extracts text and metadata from PDF documents.
     */
    public static String getPdfParserPipelineJson() {
        return getJsonResource("pdf-parser-pipeline.json");
    }

    /**
     * Returns a PipelineClusterConfig JSON for a pipeline with no steps.
     * This is a valid edge case where a pipeline is first created with no pipeline steps.
     */
    public static String getEmptyPipelineJson() {
        return getJsonResource("empty-pipeline.json");
    }

    /**
     * Returns a PipelineClusterConfig JSON for a pipeline with orphan steps.
     * This is an edge case where a pipeline has steps that have no connections.
     */
    public static String getOrphanStepsPipelineJson() {
        return getJsonResource("orphan-steps-pipeline.json");
    }

    /**
     * Returns a PipelineClusterConfig JSON for a pipeline with just the running service registered.
     * This is an edge case for an initial seeded pipeline.
     */
    public static String getInitialSeededPipelineJson() {
        return getJsonResource("initial-seeded-pipeline.json");
    }

    /**
     * Returns a JSON schema for PipelineSteps.
     */
    public static String getPipelineStepsSchemaJson() {
        return getJsonResource("pipeline-steps-schema.json");
    }

    /**
     * Returns a JSON resource as a string.
     * Resources are loaded from the classpath and cached for performance.
     */
    private static String getJsonResource(String resourceName) {
        return JSON_CACHE.computeIfAbsent(resourceName, name -> {
            try (InputStream is = SamplePipelineConfigJson.class.getResourceAsStream("/json/" + name)) {
                if (is == null) {
                    throw new IllegalArgumentException("Resource not found: " + name);
                }
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load JSON resource: " + name, e);
            }
        });
    }
}
